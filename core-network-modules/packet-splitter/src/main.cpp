// ============================================================================
// YZF Core Network Module: packet-splitter (大包拆分器)
// ----------------------------------------------------------------------------
// 功能:
//   1. 监测服务端实时上行带宽 (NetStatsEvent, 每秒一次)。
//   2. 当上行带宽超过阈值时进入"整形模式", 收紧拆包参数并对突发包限速,
//      避免瞬间大包冲击带宽造成卡顿/掉线/不同步。
//   3. 收到 SplitRequest 事件 (网关取消了超长消息包) 后, 把消息切成小包,
//      按固定节奏逐个发回网关 (split.send), 平滑突发流量。
//   4. 所有参数可通过本目录 config.hjson 调整; 模块崩溃会被网关自动重启。
//
// 协议: NDJSON over stdin/stdout (YZF_GATEWAY=netgateway)。
// 依赖: 仅 C++17 标准库, 无第三方依赖。
// ============================================================================

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <deque>
#include <iostream>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

// ----------------------------------------------------------------------------
// 极简 JSON 字段提取 (网关输出为单行紧凑 JSON, 直接按键定位即可)。
// 支持字符串值与数字值, 自动处理转义。
// ----------------------------------------------------------------------------

static std::string json_unescape(const std::string& raw){
    std::string out;
    out.reserve(raw.size());
    for(size_t i = 0; i < raw.size(); ++i){
        char c = raw[i];
        if(c != '\\' || i + 1 >= raw.size()){
            out.push_back(c);
            continue;
        }
        char next = raw[++i];
        switch(next){
            case 'n': out.push_back('\n'); break;
            case 'r': out.push_back('\r'); break;
            case 't': out.push_back('\t'); break;
            case '"': out.push_back('"'); break;
            case '\\': out.push_back('\\'); break;
            case '/': out.push_back('/'); break;
            case 'u':{
                // \uXXXX: 解析 4 位十六进制, 基本平面字符直接按 UTF-8 编码。
                if(i + 4 < raw.size()){
                    unsigned code = 0;
                    bool ok = true;
                    for(int k = 1; k <= 4; ++k){
                        char h = raw[i + k];
                        code <<= 4;
                        if(h >= '0' && h <= '9') code |= (unsigned)(h - '0');
                        else if(h >= 'a' && h <= 'f') code |= (unsigned)(h - 'a' + 10);
                        else if(h >= 'A' && h <= 'F') code |= (unsigned)(h - 'A' + 10);
                        else { ok = false; break; }
                    }
                    if(ok){
                        i += 4;
                        if(code < 0x80){
                            out.push_back((char)code);
                        }else if(code < 0x800){
                            out.push_back((char)(0xC0 | (code >> 6)));
                            out.push_back((char)(0x80 | (code & 0x3F)));
                        }else{
                            out.push_back((char)(0xE0 | (code >> 12)));
                            out.push_back((char)(0x80 | ((code >> 6) & 0x3F)));
                            out.push_back((char)(0x80 | (code & 0x3F)));
                        }
                        break;
                    }
                }
                out.push_back('u');
                break;
            }
            default: out.push_back(next); break;
        }
    }
    return out;
}

// 定位 "key": 冒号后的值起始位置 (容忍冒号后任意空白)。
// 只匹配作为键出现的位置 (前面是 { 或 , 且后面是 :), 避免误匹配同名字符串值。
static size_t json_value_start(const std::string& line, const std::string& key){
    std::string needle = "\"" + key + "\"";
    size_t pos = 0;
    while(true){
        pos = line.find(needle, pos);
        if(pos == std::string::npos) return std::string::npos;
        char prev = pos == 0 ? '{' : line[pos - 1];
        if(prev == '{' || prev == ','){
            size_t p = pos + needle.size();
            while(p < line.size() && (line[p] == ' ' || line[p] == '\t')) ++p;
            if(p < line.size() && line[p] == ':'){
                ++p;
                while(p < line.size() && (line[p] == ' ' || line[p] == '\t')) ++p;
                return p;
            }
        }
        pos += needle.size();
    }
}

// 提取字符串字段值; 未找到返回 false。
static bool json_get_string(const std::string& line, const std::string& key, std::string& out){
    size_t pos = json_value_start(line, key);
    if(pos == std::string::npos || pos >= line.size() || line[pos] != '"') return false;
    ++pos;
    std::string raw;
    while(pos < line.size()){
        char c = line[pos];
        if(c == '\\' && pos + 1 < line.size()){
            raw.push_back(c);
            raw.push_back(line[pos + 1]);
            pos += 2;
            continue;
        }
        if(c == '"') break;
        raw.push_back(c);
        ++pos;
    }
    out = json_unescape(raw);
    return true;
}

// 提取数字字段值; 未找到返回 false。
static bool json_get_number(const std::string& line, const std::string& key, double& out){
    size_t pos = json_value_start(line, key);
    if(pos == std::string::npos) return false;
    size_t end = pos;
    while(end < line.size() && (isdigit((unsigned char)line[end]) || line[end] == '-' || line[end] == '.' || line[end] == 'e' || line[end] == 'E' || line[end] == '+')){
        ++end;
    }
    if(end == pos) return false;
    try{
        out = std::stod(line.substr(pos, end - pos));
    }catch(...){
        return false;
    }
    return true;
}

static std::string json_escape(const std::string& value){
    std::string out;
    out.reserve(value.size() + 8);
    for(char c : value){
        switch(c){
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if((unsigned char)c < 0x20){
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", (unsigned)(unsigned char)c);
                    out += buf;
                }else{
                    out.push_back(c);
                }
        }
    }
    return out;
}

// ----------------------------------------------------------------------------
// 模块配置与运行状态
// ----------------------------------------------------------------------------

struct Config{
    // 拆包参数 (普通模式)
    int split_threshold = 200;      // 消息超过该字符数视为大包
    int split_chunk_size = 100;     // 每个分片最大字符数
    int split_interval_ms = 60;     // 分片发送间隔

    // 整形模式 (带宽压力大时自动切换)
    long long bandwidth_high_bps = 150000;  // 上行带宽超过该值进入整形模式 (bytes/s)
    long long bandwidth_low_bps = 80000;    // 回落到该值以下退出整形模式
    int shaped_threshold = 120;     // 整形模式下的拆包阈值
    int shaped_chunk_size = 80;     // 整形模式下的分片大小
    int shaped_interval_ms = 100;   // 整形模式下的分片间隔

    // 突发抑制: 整形模式下对大流量包类型限速 (包/秒)
    int burst_limit_snapshot = 25;  // BlockSnapshotCallPacket 限速
    int burst_limit_sync = 30;      // SyncCallPacket 限速
    bool burst_control = true;

    int stats_log_every = 30;       // 每 N 秒向 stderr 打印一次状态
};

struct ChunkJob{
    std::string kind;      // sendMessage / infoMessage / announce
    std::string text;
    std::chrono::steady_clock::time_point send_at;
};

static Config g_config;
static std::mutex g_out_mutex;
static std::deque<ChunkJob> g_chunk_queue;
static std::mutex g_queue_mutex;
static std::atomic<bool> g_shaping{false};
static std::atomic<long long> g_upload_bps{0};
static std::atomic<long long> g_split_total{0};
static std::atomic<bool> g_burst_rules_sent{false};
static std::atomic<bool> g_running{true};

static void log_stderr(const std::string& msg){
    std::fprintf(stderr, "[packet-splitter] %s\n", msg.c_str());
    std::fflush(stderr);
}

static void send_line_locked(const std::string& line){
    // 调用方必须持有 g_out_mutex
    std::fwrite(line.data(), 1, line.size(), stdout);
    std::fputc('\n', stdout);
    std::fflush(stdout);
}

static void send_line(const std::string& line){
    std::lock_guard<std::mutex> lock(g_out_mutex);
    send_line_locked(line);
}

// ----------------------------------------------------------------------------
// 本地配置读取 (config.hjson, 与 netmodule.hjson 同目录)
// ----------------------------------------------------------------------------

static std::string read_whole_file(const std::string& path){
    FILE* f = std::fopen(path.c_str(), "rb");
    if(!f) return "";
    std::string content;
    char buf[4096];
    size_t n;
    while((n = std::fread(buf, 1, sizeof(buf), f)) > 0){
        content.append(buf, n);
    }
    std::fclose(f);
    return content;
}

static void load_local_config(){
    // 模块工作目录即模块文件夹 (网关以模块文件夹为 cwd 启动)。
    std::string content = read_whole_file("config.hjson");
    if(content.empty()) content = read_whole_file("config.json");
    if(content.empty()) return;

    // 去掉 hjson 注释行, 便于按数字键提取。
    std::string cleaned;
    size_t start = 0;
    while(start < content.size()){
        size_t nl = content.find('\n', start);
        std::string line = content.substr(start, (nl == std::string::npos ? content.size() : nl) - start);
        size_t hash = line.find('#');
        if(hash != std::string::npos) line = line.substr(0, hash);
        cleaned += line;
        cleaned += '\n';
        if(nl == std::string::npos) break;
        start = nl + 1;
    }
    // hjson 允许不带引号的键, 统一补上引号方便提取。
    std::string normalized;
    for(size_t i = 0; i < cleaned.size(); ++i){
        char c = cleaned[i];
        if((isalpha((unsigned char)c) || c == '_') && (normalized.empty() || normalized.back() == '\n' || normalized.back() == '{' || normalized.back() == ',' || normalized.back() == ' ')){
            size_t j = i;
            while(j < cleaned.size() && (isalnum((unsigned char)cleaned[j]) || cleaned[j] == '_')) ++j;
            normalized.push_back('"');
            normalized.append(cleaned, i, j - i);
            normalized.push_back('"');
            i = j - 1;
            continue;
        }
        normalized.push_back(c);
    }

    auto get_int = [&](const std::string& key, int& target){
        std::string needle = "\"" + key + "\":";
        size_t pos = normalized.find(needle);
        if(pos == std::string::npos) return;
        try{ target = std::stoi(normalized.substr(pos + needle.size())); }catch(...){}
    };
    auto get_ll = [&](const std::string& key, long long& target){
        std::string needle = "\"" + key + "\":";
        size_t pos = normalized.find(needle);
        if(pos == std::string::npos) return;
        try{ target = std::stoll(normalized.substr(pos + needle.size())); }catch(...){}
    };

    get_int("splitThreshold", g_config.split_threshold);
    get_int("splitChunkSize", g_config.split_chunk_size);
    get_int("splitIntervalMs", g_config.split_interval_ms);
    get_ll("bandwidthHighBps", g_config.bandwidth_high_bps);
    get_ll("bandwidthLowBps", g_config.bandwidth_low_bps);
    get_int("shapedThreshold", g_config.shaped_threshold);
    get_int("shapedChunkSize", g_config.shaped_chunk_size);
    get_int("shapedIntervalMs", g_config.shaped_interval_ms);
    get_int("burstLimitSnapshot", g_config.burst_limit_snapshot);
    get_int("burstLimitSync", g_config.burst_limit_sync);
    int burst = g_config.burst_control ? 1 : 0;
    get_int("burstControl", burst);
    g_config.burst_control = (burst != 0);
    get_int("statsLogEvery", g_config.stats_log_every);
    log_stderr("已加载本地配置 config.hjson");
}

// ----------------------------------------------------------------------------
// 整形模式切换
// ----------------------------------------------------------------------------

static void apply_shaping(bool shaping){
    if(g_shaping.exchange(shaping) == shaping) return;

    if(shaping){
        log_stderr("带宽压力升高, 进入整形模式 (收紧拆包参数 + 突发限速)");
    }else{
        log_stderr("带宽压力回落, 退出整形模式 (恢复常规拆包参数)");
    }

    int threshold = shaping ? g_config.shaped_threshold : g_config.split_threshold;
    int chunk = shaping ? g_config.shaped_chunk_size : g_config.split_chunk_size;
    int interval = shaping ? g_config.shaped_interval_ms : g_config.split_interval_ms;

    char buf[256];
    std::snprintf(buf, sizeof(buf),
        "{\"type\":\"splitPolicy\",\"fields\":{\"mode\":\"external\",\"threshold\":\"%d\",\"chunkSize\":\"%d\",\"intervalMs\":\"%d\"}}",
        threshold, chunk, interval);
    send_line(buf);

    if(shaping && g_config.burst_control && !g_burst_rules_sent.exchange(true)){
        std::snprintf(buf, sizeof(buf),
            "{\"type\":\"rateLimit\",\"fields\":{\"event\":\"send\",\"packet\":\"BlockSnapshotCallPacket\",\"perSecond\":\"%d\",\"burst\":\"%d\"}}",
            g_config.burst_limit_snapshot, g_config.burst_limit_snapshot);
        send_line(buf);
        std::snprintf(buf, sizeof(buf),
            "{\"type\":\"rateLimit\",\"fields\":{\"event\":\"send\",\"packet\":\"SyncCallPacket\",\"perSecond\":\"%d\",\"burst\":\"%d\"}}",
            g_config.burst_limit_sync, g_config.burst_limit_sync);
        send_line(buf);
        log_stderr("已下发突发限速规则 (BlockSnapshot/Sync)");
    }
}

// ----------------------------------------------------------------------------
// 大包拆分: 把消息切成小包入队, 由节奏线程按间隔发出
// ----------------------------------------------------------------------------

static void handle_split_request(const std::string& line){
    std::string message;
    if(!json_get_string(line, "message", message) || message.empty()) return;

    std::string kind = "sendMessage";
    std::string kind_field;
    if(json_get_string(line, "kind", kind_field) && !kind_field.empty()) kind = kind_field;

    bool shaping = g_shaping.load();
    int chunk_size = shaping ? g_config.shaped_chunk_size : g_config.split_chunk_size;
    int interval_ms = shaping ? g_config.shaped_interval_ms : g_config.split_interval_ms;

    std::vector<std::string> chunks;
    // 按 UTF-8 边界切分, 避免把多字节字符切成两半导致乱码。
    size_t start = 0;
    while(start < message.size()){
        size_t end = std::min(message.size(), start + (size_t)chunk_size);
        // 回退到 UTF-8 字符边界 (非 10xxxxxx 续字节)。
        while(end > start && end < message.size() && ((unsigned char)message[end] & 0xC0) == 0x80){
            --end;
        }
        if(end <= start) end = std::min(message.size(), start + (size_t)chunk_size);
        chunks.push_back(message.substr(start, end - start));
        start = end;
    }

    auto now = std::chrono::steady_clock::now();
    {
        std::lock_guard<std::mutex> lock(g_queue_mutex);
        for(size_t i = 0; i < chunks.size(); ++i){
            ChunkJob job;
            job.kind = kind;
            job.text = std::move(chunks[i]);
            job.send_at = now + std::chrono::milliseconds(interval_ms * (long long)i);
            g_chunk_queue.push_back(std::move(job));
        }
    }
    g_split_total.fetch_add(1);
}

// ----------------------------------------------------------------------------
// 节奏线程: 按时间把队列里的分片逐个发回网关
// ----------------------------------------------------------------------------

static void pacer_thread(){
    while(g_running.load()){
        ChunkJob job;
        bool has_job = false;
        {
            std::lock_guard<std::mutex> lock(g_queue_mutex);
            if(!g_chunk_queue.empty()){
                auto now = std::chrono::steady_clock::now();
                if(g_chunk_queue.front().send_at <= now){
                    job = std::move(g_chunk_queue.front());
                    g_chunk_queue.pop_front();
                    has_job = true;
                }
            }
        }
        if(has_job){
            // 用动态字符串拼接, 避免固定缓冲区截断大分片。
            std::string line = std::string("{\"type\":\"split.send\",\"fields\":{\"kind\":\"")
                + json_escape(job.kind) + "\",\"text\":\"" + json_escape(job.text) + "\"}}";
            send_line(line);
        }else{
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
    }
}

// ----------------------------------------------------------------------------
// 事件处理
// ----------------------------------------------------------------------------

static void handle_net_stats(const std::string& line){
    double upload = 0;
    if(!json_get_number(line, "uploadBps", upload)) return;
    long long bps = (long long)upload;
    g_upload_bps.store(bps);

    if(!g_shaping.load() && bps >= g_config.bandwidth_high_bps){
        apply_shaping(true);
    }else if(g_shaping.load() && bps <= g_config.bandwidth_low_bps){
        apply_shaping(false);
    }
}

static void handle_line(const std::string& line){
    std::string type;
    if(!json_get_string(line, "type", type)) return;

    if(type == "event"){
        std::string event;
        if(!json_get_string(line, "event", event)) return;
        if(event == "NetStatsEvent"){
            handle_net_stats(line);
        }else if(event == "SplitRequest"){
            handle_split_request(line);
        }
        // 其余事件 (ReceivePacketEvent/PlayerChatEvent 等) 目前仅观察, 不处理。
    }else if(type == "hello"){
        bool ok = line.find("\"ok\":true") != std::string::npos;
        if(ok){
            std::string client_id;
            json_get_string(line, "clientId", client_id);
            log_stderr("已连接网关: " + client_id);
            // 订阅拆包委托与带宽统计。
            send_line("{\"type\":\"subscribe\",\"fields\":{\"event\":\"SplitRequest\"}}");
            send_line("{\"type\":\"subscribe\",\"fields\":{\"event\":\"NetStatsEvent\"}}");
            // 以 external 模式接管拆包决策。
            char buf[256];
            std::snprintf(buf, sizeof(buf),
                "{\"type\":\"splitPolicy\",\"fields\":{\"mode\":\"external\",\"threshold\":\"%d\",\"chunkSize\":\"%d\",\"intervalMs\":\"%d\"}}",
                g_config.split_threshold, g_config.split_chunk_size, g_config.split_interval_ms);
            send_line(buf);
            log_stderr("已接管大包拆分 (external 模式)");
        }
    }else if(type == "shutdown"){
        g_running.store(false);
    }
}

// ----------------------------------------------------------------------------
// 主流程
// ----------------------------------------------------------------------------

int main(){
#ifdef _WIN32
    // Windows 控制台 stdio 走字节流即可, 无需宽字符。
    setvbuf(stdin, nullptr, _IOFBF, 65536);
    setvbuf(stdout, nullptr, _IOFBF, 65536);
#endif

    log_stderr("packet-splitter 核心网络模块启动中...");
    load_local_config();

    std::thread pacer(pacer_thread);
    pacer.detach();

    // 状态心跳日志线程
    std::thread heartbeat([](){
        int counter = 0;
        while(g_running.load()){
            std::this_thread::sleep_for(std::chrono::seconds(1));
            if(++counter < g_config.stats_log_every) continue;
            counter = 0;
            size_t queued;
            {
                std::lock_guard<std::mutex> lock(g_queue_mutex);
                queued = g_chunk_queue.size();
            }
            char buf[192];
            std::snprintf(buf, sizeof(buf), "状态: 上行=%lld B/s 整形=%s 已拆包=%lld 待发分片=%zu",
                (long long)g_upload_bps.load(), g_shaping.load() ? "是" : "否",
                (long long)g_split_total.load(), queued);
            log_stderr(buf);
        }
    });
    heartbeat.detach();

    // 主循环: 逐行读 stdin (NDJSON)
    std::string line;
    while(g_running.load() && std::getline(std::cin, line)){
        if(line.empty()) continue;
        if(!line.empty() && line.back() == '\r') line.pop_back();
        handle_line(line);
    }

    g_running.store(false);
    log_stderr("stdin 已关闭, 模块退出。");
    return 0;
}
