package mindustry.yzf;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.func.Cons;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Timer;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.events.ReceivePacketEvent;
import mindustry.events.SendPacketEvent;
import mindustry.game.EventType.PlayerChatEvent;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.gen.AnnounceCallPacket;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.InfoMessageCallPacket;
import mindustry.gen.Player;
import mindustry.gen.SendChatMessageCallPacket;
import mindustry.gen.SendMessageCallPacket;
import mindustry.net.Packet;
import mindustry.net.YZFNetworkMetrics;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * External network module gateway.
 *
 * Lets programs written in ANY language (Go, C++, Rust, Python...) participate in the
 * game's send/receive packet pipeline with high efficiency. Transport modes:
 *
 * 1. HTTP REST API   - stateless interactions (broadcast/command/kick/filters/status).
 * 2. TCP NDJSON      - persistent bidirectional channel; subscribe to packet/chat/join
 *                      events and receive them in real time, send actions back.
 * 3. Embedded process- the gateway spawns an external binary and speaks the same
 *                      NDJSON protocol over stdin/stdout (no socket needed at all).
 *
 * Core network modules live in config/yzf/netmods/ - each sub-folder is ONE core
 * network module (not a regular plugin) described by netmodule.hjson. They are loaded
 * embedded via stdin/stdout, ordered by priority. When no module is present the server
 * simply keeps its vanilla (or modded) networking behavior - the gateway never forces a
 * dependency on any specific module, so more core modules can be added later without the
 * server depending on one in particular.
 *
 * Pipeline features (all opt-in, driven by modules or netgateway.hjson):
 * - rateLimit: per-packet-type token buckets evaluated locally on the send path to
 *   suppress bursts (no cross-process round trip per packet).
 * - splitPolicy: oversized text packets are cancelled on the send path and re-sent as a
 *   stream of small chunks spread over time ("internal" scheduler) or delegated to an
 *   external module ("external", module returns split.send actions). This avoids a single
 *   large packet hammering bandwidth, causing lag/desync/disconnects.
 * - NetStatsEvent: periodic bandwidth / TPS / per-type packet counters pushed to
 *   subscribers so modules can make shaping decisions from real traffic.
 */
public final class YZFNetGateway{
    private static final int DISPATCH_QUEUE_CAPACITY = 16384;
    private static final int MAX_EVENT_LINE_CHARS = 1024 * 1024;

    private final YZFPaths paths;
    private final mindustry.server.ServerControl serverControl;

    // Configuration (from config/yzf/config/netgateway.hjson)
    private volatile boolean enabled;
    private volatile boolean httpEnabled;
    private volatile int httpPort;
    private volatile String httpAddress;
    private volatile boolean tcpEnabled;
    private volatile int tcpPort;
    private volatile String tcpAddress;
    private volatile String token;
    private volatile boolean observeSend;
    private volatile boolean observeReceive;
    private volatile boolean observeChat;
    private volatile boolean observeJoins;

    // Split policy configuration
    private volatile String splitMode = "off";      // off | internal | external
    private volatile int splitThreshold = 200;      // message char length that triggers splitting
    private volatile int splitChunkSize = 100;      // max chars per chunk
    private volatile int splitIntervalMs = 60;      // delay between chunks (internal mode)
    private volatile int splitChunksPerTick = 4;    // max chunks dispatched per tick (internal mode)
    private volatile boolean netmodsAutoRestart = true;

    // Runtime state
    private HttpServer httpServer;
    private ServerSocket tcpServerSocket;
    private Thread tcpAcceptThread;
    private Thread dispatchThread;
    private Thread processManagerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ObjectMap<String, GatewayClient> clients = new ObjectMap<>();
    private final Seq<EmbeddedProcess> processes = new Seq<>();
    private final Seq<ProcessDefinition> processDefinitions = new Seq<>();

    // Core network modules (config/yzf/netmods/<module>/)
    private final Seq<NetModuleDefinition> netModules = new Seq<>();
    private final Seq<EmbeddedProcess> netModuleProcesses = new Seq<>();
    // File-watch hot reload for netmods; null when hotReload is disabled.
    private YZFNetModHotReloadWatcher netModHotReloadWatcher;
    // moduleId -> binary fingerprint ("lastModified:length") recorded when the module
    // process was spawned, used to detect a recompiled/replaced binary.
    private final ObjectMap<String, String> netModuleBinaryFingerprints = new ObjectMap<>();
    // moduleId -> fingerprint of build-relevant source files, recorded after a successful
    // compile / at spawn time, used to detect source changes that need a hot compile.
    private final ObjectMap<String, String> netModuleBuildFingerprints = new ObjectMap<>();
    // moduleId -> fingerprint of metadata/config files, recorded after a successful
    // compile / at spawn time, used to detect config changes.
    private final ObjectMap<String, String> netModuleMetaFingerprints = new ObjectMap<>();
    // Module ids currently being hot-compiled. Prevents the file watcher (which also
    // sees the freshly built binary appear) from kicking off a concurrent compile of
    // the same module.
    private final java.util.Set<String> netModulesCompiling = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean netmodsHotReload = true;

    // Packet filters: "S:"/"R:" + packet simple-name -> drop.
    private final ObjectSet<String> dropFilters = new ObjectSet<>();
    // Per-type rate limit buckets, evaluated locally on the send path.
    private final ConcurrentHashMap<String, RateBucket> rateBuckets = new ConcurrentHashMap<>();
    // Pending split chunks awaiting dispatch (internal mode).
    private final ConcurrentLinkedQueue<SplitChunk> pendingChunks = new ConcurrentLinkedQueue<>();
    // Packet counters per type (sampled into NetStatsEvent).
    private final ConcurrentHashMap<String, AtomicLong> packetCounters = new ConcurrentHashMap<>();

    // Async event queue (game thread -> dispatcher thread).
    private final ConcurrentLinkedQueue<String> dispatchQueue = new ConcurrentLinkedQueue<>();
    private final AtomicLong dispatchQueueSize = new AtomicLong();
    private final AtomicLong droppedEvents = new AtomicLong();
    private final AtomicLong deliveredEvents = new AtomicLong();
    private final AtomicLong actionsHandled = new AtomicLong();
    private final AtomicLong splitPackets = new AtomicLong();
    private final AtomicLong rateLimitedPackets = new AtomicLong();

    private Timer.Task statsTask;
    private Timer.Task chunkTask;
    private Timer.Task netmodRestartTask;

    // Event subscriptions (arc handlers so they can be removed on shutdown).
    private Cons<SendPacketEvent> sendHandler;
    private Cons<ReceivePacketEvent> receiveHandler;
    private Cons<PlayerChatEvent> chatHandler;
    private Cons<PlayerJoin> joinHandler;
    private Cons<PlayerLeave> leaveHandler;

    public YZFNetGateway(YZFPaths paths, mindustry.server.ServerControl serverControl){
        this.paths = paths;
        this.serverControl = serverControl;
    }

    /** Reads the live external access policy so hot reloads are respected. */
    private YZFExternalAccessConfig access(){
        return MindustryYZF.externalAccess();
    }

    public synchronized void start(){
        if(running.get()) return;
        loadConfig();
        if(!enabled){
            Log.info("[NetGateway] 外部网络模块网关已禁用（config/yzf/config/netgateway.hjson -> enabled: false）。");
            return;
        }
        running.set(true);

        installEventHandlers();
        scanNetModules();

        if(httpEnabled){
            try{
                startHttp();
            }catch(IOException error){
                YZFErrorLog.high("netgateway", "Failed to start gateway HTTP endpoint", error);
            }
        }
        if(tcpEnabled){
            try{
                startTcp();
            }catch(IOException error){
                YZFErrorLog.high("netgateway", "Failed to start gateway TCP channel", error);
            }
        }
        if(!processDefinitions.isEmpty()){
            startProcessManager();
        }
        if(!netModules.isEmpty()){
            startNetModuleManager();
        }

        if(netmodsHotReload){
            netModHotReloadWatcher = new YZFNetModHotReloadWatcher(this, paths.root.child(netmodsDirName));
            if(!netModHotReloadWatcher.start()){
                netModHotReloadWatcher = null;
            }
        }

        dispatchThread = new Thread(this::dispatchLoop, "YZFNetGateway-Dispatch");
        dispatchThread.setDaemon(true);
        dispatchThread.start();

        startStatsLoop();
        startChunkPump();

        Log.info("[NetGateway] 外部网络模块网关已启动。http=@:@ tcp=@:@ 核心网络模块=@ 内嵌进程=@ 拆包模式=@ 观察[发送=@ 接收=@ 聊天=@ 进出=@]",
            httpEnabled ? httpAddress : "-", httpEnabled ? httpPort : 0,
            tcpEnabled ? tcpAddress : "-", tcpEnabled ? tcpPort : 0,
            netModules.size, processDefinitions.size, splitMode,
            observeSend, observeReceive, observeChat, observeJoins);
        if(netModules.isEmpty()){
            Log.info("[NetGateway] 未发现核心网络模块（config/yzf/netmods/ 为空），保持原版/改版网络行为。");
        }
    }

    public synchronized void shutdown(){
        if(!running.get()) return;
        running.set(false);

        if(netModHotReloadWatcher != null){
            netModHotReloadWatcher.stop();
            netModHotReloadWatcher = null;
        }
        if(statsTask != null){ statsTask.cancel(); statsTask = null; }
        if(chunkTask != null){ chunkTask.cancel(); chunkTask = null; }
        if(netmodRestartTask != null){ netmodRestartTask.cancel(); netmodRestartTask = null; }

        if(httpServer != null){
            httpServer.stop(0);
            httpServer = null;
        }
        if(tcpServerSocket != null){
            try{
                tcpServerSocket.close();
            }catch(IOException ignored){
            }
            tcpServerSocket = null;
        }
        synchronized(clients){
            for(GatewayClient client : clients.values()){
                client.closeQuietly();
            }
            clients.clear();
        }
        synchronized(processes){
            for(EmbeddedProcess process : processes){
                process.destroy();
            }
            processes.clear();
        }
        synchronized(netModuleProcesses){
            for(EmbeddedProcess process : netModuleProcesses){
                process.destroy();
            }
            netModuleProcesses.clear();
        }
        if(sendHandler != null){ Events.remove(SendPacketEvent.class, sendHandler); sendHandler = null; }
        if(receiveHandler != null){ Events.remove(ReceivePacketEvent.class, receiveHandler); receiveHandler = null; }
        if(chatHandler != null){ Events.remove(PlayerChatEvent.class, chatHandler); chatHandler = null; }
        if(joinHandler != null){ Events.remove(PlayerJoin.class, joinHandler); joinHandler = null; }
        if(leaveHandler != null){ Events.remove(PlayerLeave.class, leaveHandler); leaveHandler = null; }
        dispatchQueue.clear();
        dispatchQueueSize.set(0);
        pendingChunks.clear();
        Log.info("[NetGateway] 外部网络模块网关已关闭。");
    }

    /** Reload configuration from disk and restart transports. */
    public synchronized String reload(){
        boolean wasRunning = running.get();
        shutdown();
        if(!wasRunning) return "网关当前未运行，已重新读取配置。";
        start();
        return "网关已重新加载配置并重启。";
    }

    public String status(){
        StringBuilder builder = new StringBuilder();
        builder.append("启用: ").append(enabled ? "是" : "否").append('\n');
        builder.append("运行中: ").append(running.get() ? "是" : "否").append('\n');
        builder.append("HTTP: ").append(httpEnabled ? httpAddress + ":" + httpPort : "禁用").append('\n');
        builder.append("TCP: ").append(tcpEnabled ? tcpAddress + ":" + tcpPort : "禁用").append('\n');
        builder.append("核心网络模块: ").append(netModules.size).append(" 个定义, ").append(netModuleProcesses.size).append(" 个运行中").append('\n');
        builder.append("核心模块热加载: ").append(netmodsHotReload ? "启用" : "禁用")
            .append(netModHotReloadWatcher != null && netModHotReloadWatcher.running() ? " (监听中)" : "").append('\n');
        builder.append("内嵌进程: ").append(processDefinitions.size).append(" 个定义, ").append(processes.size).append(" 个运行中").append('\n');
        builder.append("在线外部客户端: ").append(clientCount()).append('\n');
        builder.append("拆包模式: ").append(splitMode)
            .append(" 阈值=").append(splitThreshold).append(" 分片=").append(splitChunkSize)
            .append(" 间隔=").append(splitIntervalMs).append("ms").append('\n');
        builder.append("限速规则: ").append(rateBuckets.size()).append(" 条").append('\n');
        builder.append("观察开关: 发送=").append(observeSend).append(" 接收=").append(observeReceive)
            .append(" 聊天=").append(observeChat).append(" 进出=").append(observeJoins).append('\n');
        builder.append("丢包过滤器: ").append(dropFilters.size).append(" 条").append('\n');
        builder.append("已拆包: ").append(splitPackets.get())
            .append(" 已限速丢弃: ").append(rateLimitedPackets.get()).append('\n');
        builder.append("事件统计: 已投递=").append(deliveredEvents.get())
            .append(" 队列满丢弃=").append(droppedEvents.get())
            .append(" 队列当前=").append(dispatchQueueSize.get()).append('\n');
        builder.append("动作处理总数: ").append(actionsHandled.get());
        return builder.toString();
    }

    // ============================== configuration ==============================

    private void loadConfig(){
        Fi file = paths.configDir.child("netgateway.hjson");
        if(!file.exists()){
            file.writeString(
                "# YZF 外部网络模块网关配置。\n" +
                "# 让任意语言（Go/C++/Rust/Python...）编写的外部模块参与游戏收发包。\n" +
                "# enabled: 网关总开关。\n" +
                "# http: HTTP REST API（无状态互动：广播/命令/踢人/过滤器/状态）。\n" +
                "# tcp: NDJSON 实时双向通道（订阅收发包/聊天/进出事件并回传动作）。\n" +
                "# processes: 内嵌外部进程，网关直接启动这些程序并通过 stdin/stdout NDJSON 通信。\n" +
                "#   示例: { name: \"go-filter\", command: \"./netmods/go-filter.exe\", args: [], enabled: true }\n" +
                "# netmods: 核心网络模块相关开关。\n" +
                "#   dir: 核心网络模块目录（默认 netmods）；autoRestart: 崩溃后是否自动重启。\n" +
                "#   hotReload: 是否自动监听目录变化并热加载（重新编译/新增/删除模块后自动生效，无需重启服务端）。\n" +
                "# splitPolicy: 大包拆分策略。\n" +
                "#   mode: off=关闭 internal=网关内部定时分片 external=委托外部模块拆分。\n" +
                "#   threshold: 超过该字符长度的消息包会被拆分。\n" +
                "#   chunkSize: 每个分片最大字符数。\n" +
                "#   intervalMs: internal 模式下相邻分片的发送间隔（毫秒），用于平滑突发。\n" +
                "#   chunksPerTick: internal 模式每个节拍最多发出的分片数。\n" +
                "# token: 网关独立令牌；留空则使用 external-access.hjson 的令牌。\n" +
                "# observe: 事件观察开关（推送给订阅方；过滤器拦截不受此影响）。\n" +
                "enabled: true\n" +
                "http: { enabled: true, address: \"localhost\", port: 7100 }\n" +
                "tcp: { enabled: true, address: \"localhost\", port: 7101 }\n" +
                "processes: []\n" +
                "netmods: { dir: \"netmods\", autoRestart: true, hotReload: true }\n" +
                "splitPolicy: { mode: \"internal\", threshold: 200, chunkSize: 100, intervalMs: 60, chunksPerTick: 4 }\n" +
                "token: \"\"\n" +
                "observe: { sendPackets: false, receivePackets: true, chat: true, joins: true }\n"
            );
        }

        enabled = true;
        httpEnabled = false; httpPort = 7100; httpAddress = "localhost";
        tcpEnabled = false; tcpPort = 7101; tcpAddress = "localhost";
        token = "";
        observeSend = false; observeReceive = true; observeChat = true; observeJoins = true;
        splitMode = "off"; splitThreshold = 200; splitChunkSize = 100; splitIntervalMs = 60; splitChunksPerTick = 4;
        netmodsAutoRestart = true;
        netmodsHotReload = true;
        String netmodsDir = "netmods";
        processDefinitions.clear();

        try{
            Jval root = Jval.read(YZFText.readTextSmart(file));
            enabled = root.getBool("enabled", true);
            Jval http = root.get("http");
            if(http != null && http.isObject()){
                httpEnabled = http.getBool("enabled", true);
                httpAddress = http.getString("address", "localhost").trim();
                httpPort = http.getInt("port", 7100);
            }
            Jval tcp = root.get("tcp");
            if(tcp != null && tcp.isObject()){
                tcpEnabled = tcp.getBool("enabled", true);
                tcpAddress = tcp.getString("address", "localhost").trim();
                tcpPort = tcp.getInt("port", 7101);
            }
            token = root.getString("token", "").trim();
            Jval observe = root.get("observe");
            if(observe != null && observe.isObject()){
                observeSend = observe.getBool("sendPackets", false);
                observeReceive = observe.getBool("receivePackets", true);
                observeChat = observe.getBool("chat", true);
                observeJoins = observe.getBool("joins", true);
            }
            Jval netmods = root.get("netmods");
            if(netmods != null && netmods.isObject()){
                String dir = netmods.getString("dir", "netmods").trim();
                if(!dir.isEmpty()) netmodsDir = dir;
                netmodsAutoRestart = netmods.getBool("autoRestart", true);
                netmodsHotReload = netmods.getBool("hotReload", true);
            }
            Jval split = root.get("splitPolicy");
            if(split != null && split.isObject()){
                splitMode = split.getString("mode", "off").trim().toLowerCase(Locale.ROOT);
                splitThreshold = Math.max(16, split.getInt("threshold", 200));
                splitChunkSize = Math.max(16, split.getInt("chunkSize", 100));
                splitIntervalMs = Math.max(5, split.getInt("intervalMs", 60));
                splitChunksPerTick = Math.max(1, split.getInt("chunksPerTick", 4));
            }
            Jval procs = root.get("processes");
            if(procs != null && procs.isArray()){
                for(Jval item : procs.asArray()){
                    if(item == null || !item.isObject()) continue;
                    String name = item.getString("name", "").trim();
                    String command = item.getString("command", "").trim();
                    if(name.isEmpty() || command.isEmpty()) continue;
                    ProcessDefinition definition = new ProcessDefinition(name, command);
                    Jval args = item.get("args");
                    if(args != null && args.isArray()){
                        for(Jval arg : args.asArray()) definition.args.add(arg.asString());
                    }
                    definition.enabled = item.getBool("enabled", true);
                    processDefinitions.add(definition);
                }
            }
            this.netmodsDirName = netmodsDir;
        }catch(Throwable error){
            YZFErrorLog.high("netgateway", "Invalid netgateway.hjson; gateway disabled", error);
            enabled = false;
        }
    }

    private String netmodsDirName = "netmods";

    private String effectiveToken(){
        if(!token.isEmpty()) return token;
        YZFExternalAccessConfig policy = access();
        if(policy == null) return "";
        String accessAuth = policy.authorization();
        return accessAuth == null ? "" : accessAuth.substring("Bearer ".length());
    }

    private boolean authenticated(InetAddress address, String presented){
        if(YZFText.blank(effectiveToken())) return true;
        if(presented == null) presented = "";
        if(presented.startsWith("Bearer ")) presented = presented.substring("Bearer ".length());
        return java.security.MessageDigest.isEqual(
            effectiveToken().getBytes(StandardCharsets.UTF_8),
            presented.trim().getBytes(StandardCharsets.UTF_8));
    }

    // ============================== core network modules (netmods/) ==============================

    /** Scans config/yzf/<netmodsDir>/ for core network module folders. */
    private void scanNetModules(){
        netModules.clear();
        Fi dir = paths.root.child(netmodsDirName);
        dir.mkdirs();
        Fi readme = dir.child("README.txt");
        if(!readme.exists()){
            readme.writeString(
                "YZF 核心网络模块目录（core network modules）\n" +
                "每个子文件夹 = 一个核心网络模块（不同于普通插件），支持 C++/Go/Rust 等任意语言。\n" +
                "模块文件夹内需要:\n" +
                "  netmodule.hjson  - 模块元数据 { id, name, version, priority, enabled, command, args: [...] }\n" +
                "  command 指向可执行文件（相对本模块文件夹或绝对路径）。\n" +
                "网关按 priority 升序启动（数字越小越先启动），通过 stdin/stdout NDJSON 通信。\n" +
                "没有模块时服务端保持原版/改版网络行为，网关不强制依赖任何特定模块。\n" +
                "\n" +
                "热编译（只放源码，自动编译 + 热加载，无需重启服务端）:\n" +
                "  在 netmodule.hjson 中配置 build 段:\n" +
                "    build: { type: \"cpp\", source: \"src/main.cpp\" }   # C++ (自动定位 MSVC vcvars64)\n" +
                "    build: { type: \"go\" }                              # Go  (go build -o <command> .)\n" +
                "    build: { script: \"build.bat\" }                     # 自定义构建脚本\n" +
                "  修改源码 (.c/.cpp/.h/.go/...) 后网关自动: 停止旧模块 -> 编译 -> 启动新模块。\n" +
                "  编译失败时保留旧版本继续运行。首次启动缺少二进制时也会自动编译。\n" +
                "\n" +
                "热加载命令:\n" +
                "  yzf net mods               - 查看模块列表与运行状态\n" +
                "  yzf net rescan             - 重新扫描目录，热添加新放入的模块\n" +
                "  yzf net restart <id|all>   - 热重启模块\n" +
                "  yzf net stopmod <id>       - 热停止某个模块\n" +
                "模块进程意外退出时网关会每 10 秒检查并自动重启（netmods.autoRestart）。\n"
            );
        }
        if(!dir.exists() || !dir.isDirectory()) return;
        for(Fi moduleDir : dir.list()){
            if(!moduleDir.isDirectory()) continue;
            Fi meta = moduleDir.child("netmodule.hjson");
            if(!meta.exists()) meta = moduleDir.child("netmodule.json");
            if(!meta.exists()){
                Log.warn("[NetGateway] 核心网络模块目录缺少 netmodule.hjson，已跳过: @", moduleDir.name());
                continue;
            }
            try{
                Jval root = Jval.read(YZFText.readTextSmart(meta));
                NetModuleDefinition definition = new NetModuleDefinition(moduleDir);
                definition.id = root.getString("id", moduleDir.name()).trim();
                definition.name = root.getString("name", definition.id).trim();
                definition.version = root.getString("version", "1.0.0").trim();
                definition.priority = root.getInt("priority", 100);
                definition.enabled = root.getBool("enabled", true);
                definition.command = root.getString("command", "").trim();
                Jval args = root.get("args");
                if(args != null && args.isArray()){
                    for(Jval arg : args.asArray()) definition.args.add(arg.asString());
                }
                // 热编译配置 build: { type, source, script }
                //   type   - 编译类型: "go" | "cpp" | "c" | "cxx"；留空则尝试 build.bat/build.sh。
                //   source - cpp 类型的主源文件（如 "src/main.cpp"）。
                //   script - 自定义构建脚本（如 "build.bat"），配置后优先于内置编译器。
                Jval build = root.get("build");
                if(build != null && build.isObject()){
                    definition.buildType = build.getString("type", "").trim();
                    definition.buildSource = build.getString("source", "").trim();
                    definition.buildScript = build.getString("script", "").trim();
                }
                if(definition.command.isEmpty()){
                    Log.warn("[NetGateway] 核心网络模块缺少 command，已跳过: @", definition.id);
                    continue;
                }
                netModules.add(definition);
            }catch(Throwable error){
                YZFErrorLog.high("netgateway", "Invalid netmodule.hjson in " + moduleDir.name(), error);
            }
        }
        // Sort by priority ascending so lower numbers start first.
        netModules.sort((a, b) -> Integer.compare(a.priority, b.priority));
    }

    private void startNetModuleManager(){
        processManagerThread = new Thread(() -> {
            for(NetModuleDefinition definition : netModules){
                if(!running.get()) return;
                if(!definition.enabled){
                    Log.info("[NetGateway] 核心网络模块已跳过（禁用）: @", definition.id);
                    continue;
                }
                startNetModuleWithBuild(definition, true);
            }
            if(netmodsAutoRestart){
                scheduleNetmodRestartCheck();
            }
        }, "YZFNetGateway-NetMods");
        processManagerThread.setDaemon(true);
        processManagerThread.start();
    }

    /**
     * Starts a core module, compiling it from source first when it has a build config
     * and the binary is missing (initial deploy: source-only folders work out of the box).
     * Idempotent: if the module is already running (e.g. the file watcher started it
     * while the initial pass was compiling a slower sibling), this does not spawn again.
     */
    private void startNetModuleWithBuild(NetModuleDefinition definition, boolean logErrors){
        if(isNetModuleRunning(definition.id)) return;
        if(hasBuildConfig(definition)){
            File commandFile = resolveCommandFile(definition);
            if(!commandFile.exists()){
                Log.info("[NetGateway] 核心网络模块 @ 缺少二进制，先热编译源码...", definition.id);
                boolean built = compileNetModule(definition);
                if(!built && !commandFile.exists()){
                    if(logErrors){
                        Log.err("[NetGateway] 核心网络模块 @ 首次编译失败，模块未启动。", definition.id);
                    }
                    return;
                }
            }
        }
        if(isNetModuleRunning(definition.id)) return;
        spawnNetModule(definition);
    }

    /** True when a live process for the given core module id is registered. */
    private boolean isNetModuleRunning(String moduleId){
        synchronized(netModuleProcesses){
            for(EmbeddedProcess process : netModuleProcesses){
                if(process.name.equals(moduleId) && process.process.isAlive()) return true;
            }
        }
        return false;
    }

    private void scheduleNetmodRestartCheck(){
        if(netmodRestartTask != null) return;
        netmodRestartTask = Timer.schedule(() -> {
            if(!running.get()) return;
            // Collect dead modules under the lock, then restart outside it so a
            // (potentially slow) hot compile never blocks netModuleProcesses access.
            Seq<String> dead = new Seq<>();
            synchronized(netModuleProcesses){
                for(EmbeddedProcess process : netModuleProcesses.copy()){
                    if(!process.process.isAlive()){
                        netModuleProcesses.remove(process);
                        unregisterClient(process.client);
                        dead.add(process.name);
                    }
                }
            }
            for(String id : dead){
                NetModuleDefinition definition = netModules.find(d -> d.id.equals(id));
                if(definition != null && definition.enabled){
                    Log.warn("[NetGateway] 核心网络模块已退出，尝试重启: @", id);
                    startNetModuleWithBuild(definition, true);
                }
            }
        }, 10f, 10f);
    }

    private void spawnNetModule(NetModuleDefinition definition){
        File commandFile = new File(definition.command);
        if(!commandFile.isAbsolute()){
            commandFile = new File(definition.dir.file(), definition.command);
        }
        Process process;
        EmbeddedProcess embedded;
        // Check-and-start under the process lock so concurrent callers (initial manager
        // thread, file watcher, rescan/restart commands) can never double-spawn a module.
        synchronized(netModuleProcesses){
            if(isNetModuleRunning(definition.id)){
                Log.info("[NetGateway] 核心网络模块 @ 已在运行，跳过重复启动。", definition.id);
                return;
            }
            try{
                Seq<String> commandLine = new Seq<>();
                commandLine.add(commandFile.getAbsolutePath());
                commandLine.addAll(definition.args);
                ProcessBuilder builder = new ProcessBuilder(commandLine.toArray(String.class));
                builder.directory(definition.dir.file());
                builder.environment().put("YZF_PROTOCOL", "ndjson-stdio");
                builder.environment().put("YZF_GATEWAY", "netgateway");
                builder.environment().put("YZF_MODULE_ID", definition.id);
                builder.environment().put("YZF_TOKEN", effectiveToken());
                process = builder.start();
            }catch(Throwable error){
                YZFErrorLog.high("netgateway", "Failed to spawn core network module " + definition.id, error);
                return;
            }
            // Record fingerprints at spawn time so the file watcher can detect
            // source changes (-> hot compile), config changes (-> restart), and a
            // replaced binary (-> restart) without treating the first load as a change.
            netModuleBinaryFingerprints.put(definition.id, binaryFingerprint(commandFile));
            netModuleBuildFingerprints.put(definition.id, buildFingerprint(definition));
            netModuleMetaFingerprints.put(definition.id, metaFingerprint(definition));
            embedded = new EmbeddedProcess(definition.id, process);
            embedded.client = new GatewayClient("netmod:" + definition.id, null);
            embedded.client.output = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            embedded.client.subscribedAll = true;
            embedded.client.authenticated = true;
            embedded.client.isCoreModule = true;
            registerClient(embedded.client);
            netModuleProcesses.add(embedded);
        }
        embedded.client.sendLine("{\"type\":\"hello\",\"gateway\":\"MindustryYZF\",\"version\":\"" + escape(MindustryYZF.version) + "\",\"ok\":true,\"clientId\":\"" + escape(embedded.client.id) + "\",\"moduleId\":\"" + escape(definition.id) + "\",\"coreModule\":true}");

        Thread reader = new Thread(() -> {
            try(BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))){
                String line;
                while(running.get() && (line = input.readLine()) != null){
                    if(YZFText.blank(line)) continue;
                    if(line.length() > MAX_EVENT_LINE_CHARS) continue;
                    handleAction(embedded.client, line);
                }
            }catch(IOException ignored){
            }finally{
                unregisterClient(embedded.client);
            }
        }, "YZFNetGateway-NetMod-" + definition.id);
        reader.setDaemon(true);
        reader.start();

        Thread stderr = new Thread(() -> {
            try(BufferedReader input = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))){
                String line;
                while((line = input.readLine()) != null){
                    Log.info("[NetMod:@] @", definition.id, line);
                }
            }catch(IOException ignored){
            }
        }, "YZFNetGateway-NetMod-" + definition.id + "-stderr");
        stderr.setDaemon(true);
        stderr.start();

        Log.info("[NetGateway] 核心网络模块已启动: @ v@ (priority @)", definition.id, definition.version, definition.priority);
    }

    // ============================== netmod hot management ==============================

    /** Lists core network modules with their live process state. */
    public synchronized String listNetModules(){
        StringBuilder builder = new StringBuilder();
        builder.append("核心网络模块目录: ").append(paths.root.child(netmodsDirName).absolutePath()).append('\n');
        if(netModules.isEmpty()){
            builder.append("(无 — 服务端保持原版行为。把「netmodule.hjson + 可执行文件」放入子文件夹后执行 yzf net rescan 即可热添加)");
            return builder.toString();
        }
        synchronized(netModuleProcesses){
            for(NetModuleDefinition definition : netModules){
                boolean runningNow = false;
                for(EmbeddedProcess process : netModuleProcesses){
                    if(process.name.equals(definition.id) && process.process.isAlive()) runningNow = true;
                }
                builder.append("  ").append(definition.id)
                    .append(" v").append(definition.version)
                    .append(" priority=").append(definition.priority)
                    .append(definition.enabled ? "" : " [已禁用]")
                    .append(runningNow ? " [运行中]" : " [已停止]")
                    .append(" command=").append(definition.command)
                    .append('\n');
            }
        }
        return builder.toString();
    }

    /** Hot-add: rescan the netmods folder and spawn newly added modules without restart. */
    public synchronized String rescanNetModules(){
        if(!running.get()) return "网关未运行，无法热添加模块。";
        Seq<String> runningIds = new Seq<>();
        synchronized(netModuleProcesses){
            for(EmbeddedProcess process : netModuleProcesses) runningIds.add(process.name);
        }
        scanNetModules();
        Seq<String> added = new Seq<>();
        for(NetModuleDefinition definition : netModules){
            if(!definition.enabled) continue;
            if(runningIds.contains(definition.id)) continue;
            startNetModuleWithBuild(definition, true);
            added.add(definition.id);
        }
        return added.isEmpty() ? "重扫完成，未发现新模块。" : "已热添加模块: " + String.join(", ", added.toArray(String.class));
    }

    /** Hot-replace: stop module(s) and spawn them again (recompiling from source when a build config exists). */
    public synchronized String restartNetModule(String moduleId){
        if(!running.get()) return "网关未运行。";
        if(YZFText.blank(moduleId)) return "用法: yzf net restart <moduleId|all>";
        Seq<String> targets = new Seq<>();
        if(moduleId.equalsIgnoreCase("all")){
            synchronized(netModuleProcesses){
                for(EmbeddedProcess process : netModuleProcesses) targets.add(process.name);
            }
        }else{
            targets.add(moduleId);
        }
        scanNetModules();
        Seq<String> restarted = new Seq<>();
        for(String id : targets){
            stopNetModuleProcess(id);
            NetModuleDefinition definition = netModules.find(d -> d.id.equals(id));
            if(definition != null && definition.enabled){
                // Force a rebuild so `yzf net restart` picks up source edits too.
                if(hasBuildConfig(definition)){
                    compileNetModule(definition);
                }
                if(resolveCommandFile(definition).exists()){
                    spawnNetModule(definition);
                    restarted.add(id);
                }else{
                    Log.err("[NetGateway] 模块 @ 没有可用二进制，重启失败。", id);
                }
            }
        }
        return restarted.isEmpty() ? "没有可重启的模块（不存在或已禁用）。" : "已热重启模块: " + String.join(", ", restarted.toArray(String.class));
    }

    /** Hot-remove: stop a running core module without restarting the server. */
    public synchronized String stopNetModule(String moduleId){
        if(!running.get()) return "网关未运行。";
        if(YZFText.blank(moduleId)) return "用法: yzf net stopmod <moduleId>";
        boolean stopped = stopNetModuleProcess(moduleId);
        return stopped ? "已热移除模块: " + moduleId : "未找到运行中的模块: " + moduleId;
    }

    private boolean stopNetModuleProcess(String moduleId){
        EmbeddedProcess target = null;
        synchronized(netModuleProcesses){
            for(EmbeddedProcess process : netModuleProcesses){
                if(process.name.equals(moduleId)){
                    target = process;
                    break;
                }
            }
            if(target != null) netModuleProcesses.remove(target);
        }
        if(target == null) return false;
        target.destroy();
        unregisterClient(target.client);
        Log.info("[NetGateway] 核心网络模块已停止: @", moduleId);
        return true;
    }

    // ============================== netmod file-watch hot reload / hot build ==============================

    /** File extensions that count as build-relevant sources (trigger a hot compile). */
    private static boolean isBuildExtension(String name){
        return name.endsWith(".c") || name.endsWith(".cc") || name.endsWith(".cpp") || name.endsWith(".cxx")
            || name.endsWith(".h") || name.endsWith(".hpp") || name.endsWith(".hxx")
            || name.endsWith(".go") || name.endsWith(".rs") || name.endsWith(".zig")
            || name.endsWith(".mod") || name.endsWith(".sum")
            || name.endsWith(".bat") || name.endsWith(".sh") || name.endsWith(".mk") || name.endsWith(".cmake");
    }

    /** Whether a file participates in hot-build change detection. Public for the watcher. */
    public static boolean isBuildRelevantFile(String name){
        if(name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return isBuildExtension(lower) || lower.equals("makefile") || lower.equals("go.mod") || lower.equals("go.sum");
    }

    private static boolean hasBuildConfig(NetModuleDefinition definition){
        return !YZFText.blank(definition.buildType) || !YZFText.blank(definition.buildScript);
    }

    /**
     * Applies netmods folder changes detected by the file watcher. Runs on the watcher's
     * worker thread, never on the game thread, so compiling and restarting modules
     * cannot block the game loop.
     *
     * Diff semantics:
     * - module folder added                      -> compile (if it has a build config) + start
     * - module folder removed / metadata disabled -> stop
     * - source files changed                      -> stop, HOT COMPILE, start (new binary)
     * - config/metadata changed                   -> restart (recompile when build configured)
     * - binary replaced externally                -> restart
     */
    public void onNetModFilesChanged(){
        if(!running.get()) return;

        Seq<String> toStop = new Seq<>();
        Seq<NetModuleDefinition> toBuild = new Seq<>();
        Seq<NetModuleDefinition> toRestart = new Seq<>();
        Seq<NetModuleDefinition> toStart = new Seq<>();

        synchronized(this){
            ObjectMap<String, EmbeddedProcess> runningNow = new ObjectMap<>();
            synchronized(netModuleProcesses){
                for(EmbeddedProcess process : netModuleProcesses.copy()){
                    if(process.process.isAlive()){
                        runningNow.put(process.name, process);
                    }else{
                        netModuleProcesses.remove(process);
                    }
                }
            }

            scanNetModules();

            for(NetModuleDefinition definition : netModules){
                // A compile of this module is already in flight; skip until it settles
                // so the watcher (which also sees the new binary appear) cannot start a
                // second concurrent compile of the same module.
                if(netModulesCompiling.contains(definition.id)) continue;
                EmbeddedProcess current = runningNow.get(definition.id);
                boolean buildable = hasBuildConfig(definition);
                if(current == null){
                    if(definition.enabled){
                        if(buildable) toBuild.add(definition);
                        else toStart.add(definition);
                    }
                    continue;
                }
                if(!definition.enabled){
                    toStop.add(definition.id);
                    continue;
                }
                // Source files changed since the last successful build -> hot compile.
                String buildFp = buildFingerprint(definition);
                String recordedBuildFp = netModuleBuildFingerprints.get(definition.id);
                if(buildable && recordedBuildFp != null && !buildFp.equals(recordedBuildFp)){
                    toBuild.add(definition);
                    continue;
                }
                // netmodule.hjson / config.* changed -> restart (compile when buildable).
                String metaFp = metaFingerprint(definition);
                String recordedMetaFp = netModuleMetaFingerprints.get(definition.id);
                if(recordedMetaFp != null && !metaFp.equals(recordedMetaFp)){
                    if(buildable) toBuild.add(definition);
                    else toRestart.add(definition);
                    continue;
                }
                // Binary replaced externally -> plain restart.
                String currentFingerprint = binaryFingerprint(resolveCommandFile(definition));
                String recorded = netModuleBinaryFingerprints.get(definition.id);
                if(recorded == null || !recorded.equals(currentFingerprint)){
                    toRestart.add(definition);
                }
            }

            for(String id : runningNow.keys()){
                if(netModules.find(d -> d.id.equals(id)) == null){
                    toStop.add(id);
                }
            }
        }

        if(toStop.isEmpty() && toBuild.isEmpty() && toRestart.isEmpty() && toStart.isEmpty()) return;

        for(String id : toStop){
            stopNetModuleProcess(id);
            Log.info("[NetGateway] 热移除核心网络模块: @", id);
        }

        for(NetModuleDefinition definition : toBuild){
            boolean wasRunning = stopNetModuleProcess(definition.id);
            boolean built = compileNetModule(definition);
            File commandFile = resolveCommandFile(definition);
            if(built && commandFile.exists()){
                netModuleBuildFingerprints.put(definition.id, buildFingerprint(definition));
                netModuleMetaFingerprints.put(definition.id, metaFingerprint(definition));
                spawnNetModule(definition);
                Log.info("[NetGateway] 热编译完成并已重启核心网络模块: @", definition.id);
            }else if(wasRunning && commandFile.exists()){
                spawnNetModule(definition);
                Log.warn("[NetGateway] 热编译失败，已用旧版本重启核心网络模块: @", definition.id);
            }else{
                Log.err("[NetGateway] 热编译失败且无可用二进制，核心网络模块保持停止: @", definition.id);
            }
        }

        for(NetModuleDefinition definition : toRestart){
            stopNetModuleProcess(definition.id);
            spawnNetModule(definition);
            Log.info("[NetGateway] 热重启核心网络模块（检测到二进制/配置变更）: @", definition.id);
        }

        for(NetModuleDefinition definition : toStart){
            spawnNetModule(definition);
            Log.info("[NetGateway] 热添加核心网络模块: @", definition.id);
        }
    }

    // ============================== netmod hot compile ==============================

    private static File resolveCommandFile(NetModuleDefinition definition){
        File commandFile = new File(definition.command);
        if(!commandFile.isAbsolute()) commandFile = new File(definition.dir.file(), definition.command);
        return commandFile;
    }

    /**
     * Hot-compiles a core network module from source. Returns true when a usable binary
     * exists at the module's command path afterwards.
     *
     * Build strategy (first match wins):
     * 1. build.script configured (e.g. "build.bat") -> run it in the module folder.
     * 2. build.type "go"   -> go build -o <output> .
     *    build.type "cpp"  -> MSVC cl.exe (vcvars64 auto-discovered) /std:c++17 /O2.
     * 3. build.bat / build.sh present in the module folder -> run it.
     */
    private boolean compileNetModule(NetModuleDefinition definition){
        // Concurrency guard: only one compile per module at a time. When the initial
        // deploy compile is still running, the file watcher may also detect changes and
        // request a compile; skip those instead of racing two compilers.
        if(!netModulesCompiling.add(definition.id)){
            Log.info("[NetGateway] 模块 @ 正在编译中，跳过本次重复编译请求。", definition.id);
            return false;
        }
        try{
            return compileNetModuleLocked(definition);
        }finally{
            netModulesCompiling.remove(definition.id);
        }
    }

    private boolean compileNetModuleLocked(NetModuleDefinition definition){
        File dir = definition.dir.file();
        long startMs = System.currentTimeMillis();
        String type = definition.buildType;
        Log.info("[NetGateway] 热编译核心网络模块: @ (type=@)", definition.id, YZFText.blank(type) ? "script" : type);

        boolean ok;
        if(!YZFText.blank(definition.buildScript)){
            File script = new File(dir, definition.buildScript);
            if(!script.exists()){
                Log.err("[NetGateway] 模块 @ 的 build.script 不存在: @", definition.id, script.getAbsolutePath());
                return false;
            }
            ok = runBuildCommand(definition, dir, scriptCommandLine(script));
        }else if("go".equalsIgnoreCase(type)){
            ok = buildGoModule(definition, dir);
        }else if("cpp".equalsIgnoreCase(type) || "c".equalsIgnoreCase(type) || "cxx".equalsIgnoreCase(type)){
            ok = buildCppModule(definition, dir);
        }else{
            File bat = new File(dir, "build.bat");
            File sh = new File(dir, "build.sh");
            if(bat.exists()) ok = runBuildCommand(definition, dir, scriptCommandLine(bat));
            else if(sh.exists()) ok = runBuildCommand(definition, dir, scriptCommandLine(sh));
            else{
                Log.warn("[NetGateway] 模块 @ 没有可用的 build 配置或 build 脚本，跳过热编译。", definition.id);
                return false;
            }
        }

        File output = resolveCommandFile(definition);
        if(ok && !output.exists()){
            Log.err("[NetGateway] 模块 @ 编译命令成功但未生成目标文件: @", definition.id, output.getAbsolutePath());
            return false;
        }
        if(ok){
            Log.info("[NetGateway] 模块 @ 热编译成功，耗时 @ ms。", definition.id, System.currentTimeMillis() - startMs);
        }
        return ok;
    }

    private boolean buildGoModule(NetModuleDefinition definition, File dir){
        File output = resolveCommandFile(definition);
        Seq<String> cmd = new Seq<>();
        cmd.add("go");
        cmd.add("build");
        cmd.add("-o");
        cmd.add(output.getAbsolutePath());
        if(new File(dir, "go.mod").exists()){
            cmd.add(".");
        }else{
            File[] sources = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".go"));
            if(sources == null || sources.length == 0){
                Log.err("[NetGateway] 模块 @ 目录下没有 go.mod 或 .go 源文件。", definition.id);
                return false;
            }
            for(File source : sources) cmd.add(source.getName());
        }
        return runBuildCommand(definition, dir, cmd.toArray(String.class));
    }

    private boolean buildCppModule(NetModuleDefinition definition, File dir){
        File vcvars = findVcVars64();
        if(vcvars == null){
            Log.err("[NetGateway] 未找到 MSVC vcvars64.bat，无法热编译 C++ 模块 @。请安装 Visual Studio Build Tools，或在 netmodule.hjson 中配置 build.script 使用自己的编译器。", definition.id);
            return false;
        }
        if(YZFText.blank(definition.buildSource)){
            Log.err("[NetGateway] C++ 模块 @ 缺少 build.source（如 \"src/main.cpp\"）。", definition.id);
            return false;
        }
        File output = resolveCommandFile(definition);
        String line = "call \"" + vcvars.getAbsolutePath() + "\" >nul 2>nul"
            + " && cl.exe /nologo /std:c++17 /O2 /EHsc /W3 /utf-8"
            + " \"" + definition.buildSource.replace('/', File.separatorChar) + "\""
            + " /Fe:\"" + output.getName() + "\"";
        return runBuildCommand(definition, dir, new String[]{"cmd", "/c", line});
    }

    private static String[] scriptCommandLine(File script){
        String name = script.getName().toLowerCase(Locale.ROOT);
        if(name.endsWith(".bat") || name.endsWith(".cmd")){
            return new String[]{"cmd", "/c", script.getAbsolutePath()};
        }
        return new String[]{"bash", script.getAbsolutePath()};
    }

    /** Runs a build command in the module folder, capturing combined output. 300s timeout. */
    private boolean runBuildCommand(NetModuleDefinition definition, File dir, String[] command){
        StringBuilder output = new StringBuilder();
        try{
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(dir);
            builder.redirectErrorStream(true);
            builder.environment().put("YZF_MODULE_ID", definition.id);
            Process process = builder.start();
            Thread reader = new Thread(() -> {
                try(BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))){
                    String line;
                    while((line = input.readLine()) != null){
                        synchronized(output){
                            if(output.length() < 8000) output.append(line).append('\n');
                        }
                    }
                }catch(IOException ignored){
                }
            }, "YZFNetGateway-Build-" + definition.id);
            reader.setDaemon(true);
            reader.start();
            boolean done = process.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
            if(!done){
                process.destroyForcibly();
                Log.err("[NetGateway] 模块 @ 编译超时(300s)，已强制终止。", definition.id);
                return false;
            }
            int code = process.exitValue();
            if(code != 0){
                String tail;
                synchronized(output){
                    String full = output.toString();
                    tail = full.length() > 2000 ? full.substring(full.length() - 2000) : full;
                }
                Log.err("[NetGateway] 模块 @ 编译失败 (exit=@):\n@", definition.id, code, tail);
                return false;
            }
            return true;
        }catch(Throwable error){
            YZFErrorLog.high("netgateway", "Failed to hot-compile net module " + definition.id, error);
            return false;
        }
    }

    /** Locates vcvars64.bat under common Visual Studio installations. */
    private static File findVcVars64(){
        String cached = System.getProperty("yzf.vcvars64", "");
        if(!YZFText.blank(cached)){
            File file = new File(cached);
            if(file.exists()) return file;
        }
        String[] bases = {
            System.getenv("ProgramFiles(x86)"),
            System.getenv("ProgramFiles"),
            "C:\\Program Files (x86)",
            "C:\\Program Files"
        };
        for(String base : bases){
            if(YZFText.blank(base)) continue;
            File vsDir = new File(base, "Microsoft Visual Studio");
            File[] years = vsDir.listFiles(File::isDirectory);
            if(years == null) continue;
            for(File year : years){
                File[] editions = year.listFiles(File::isDirectory);
                if(editions == null) continue;
                for(File edition : editions){
                    File vcvars = new File(edition, "VC" + File.separator + "Auxiliary" + File.separator + "Build" + File.separator + "vcvars64.bat");
                    if(vcvars.exists()) return vcvars;
                }
            }
        }
        return null;
    }

    // ============================== netmod fingerprints ==============================

    /** Fingerprint a module binary so a rebuild is detected ("lastModified:length"). */
    private static String binaryFingerprint(File file){
        if(file == null || !file.exists() || !file.isFile()) return "missing";
        return file.lastModified() + ":" + file.length();
    }

    /** Fingerprint of build-relevant files (sources + build scripts). */
    private static String buildFingerprint(NetModuleDefinition definition){
        java.util.List<String> parts = new java.util.ArrayList<>();
        collectFingerprint(definition.dir.file(), definition.dir.file(), true, parts);
        java.util.Collections.sort(parts);
        return String.join("|", parts);
    }

    /** Fingerprint of metadata/config files (netmodule.hjson + config.hjson/json). */
    private static String metaFingerprint(NetModuleDefinition definition){
        java.util.List<String> parts = new java.util.ArrayList<>();
        collectFingerprint(definition.dir.file(), definition.dir.file(), false, parts);
        java.util.Collections.sort(parts);
        return String.join("|", parts);
    }

    private static void collectFingerprint(File root, File dir, boolean buildOnly, java.util.List<String> parts){
        File[] files = dir.listFiles();
        if(files == null) return;
        for(File file : files){
            String name = file.getName().toLowerCase(Locale.ROOT);
            if(file.isDirectory()){
                if(name.equals("cache") || name.startsWith(".")) continue;
                collectFingerprint(root, file, buildOnly, parts);
                continue;
            }
            if(isJunkFile(name)) continue;
            // Binaries are tracked via binaryFingerprint, never here.
            if(name.endsWith(".exe") || name.endsWith(".bin") || name.endsWith(".elf") || name.endsWith(".dll")) continue;
            boolean metaRelevant = name.equals("netmodule.hjson") || name.equals("netmodule.json")
                || name.equals("config.hjson") || name.equals("config.json");
            boolean buildRelevant = isBuildRelevantFile(name) && !metaRelevant;
            boolean include = buildOnly ? buildRelevant : metaRelevant;
            if(!include) continue;
            String rel;
            try{
                rel = root.toPath().relativize(file.toPath()).toString().replace('\\', '/');
            }catch(Throwable error){
                rel = file.getName();
            }
            parts.add(rel + ":" + file.lastModified() + ":" + file.length());
        }
    }

    private static boolean isJunkFile(String name){
        return name.endsWith(".tmp") || name.endsWith(".log") || name.endsWith(".bak") || name.endsWith(".swp")
            || name.endsWith(".obj") || name.endsWith(".o") || name.endsWith(".pdb") || name.endsWith(".ilk")
            || name.endsWith(".exp") || name.endsWith(".lib") || name.endsWith(".map") || name.endsWith(".d")
            || name.endsWith(".class") || name.endsWith(".lock");
    }

    /**
     * Core network module management over HTTP (used by build scripts and ops tooling):
     *   GET/POST /yzfnet/netmods            - list modules with live process state
     *   POST     /yzfnet/netmods/stop       - stop a module (body: {"id":"..."}), releases the
     *                                          Windows exe lock so the build script can replace it
     *   POST     /yzfnet/netmods/restart    - restart a module (body: {"id":"..."|"all"})
     *   POST     /yzfnet/netmods/rescan     - rescan folder and start newly added modules
     * Returns null for paths that are not netmod endpoints.
     */
    private String handleNetModEndpoint(String path, HttpExchange exchange) throws IOException{
        if(!path.startsWith("/yzfnet/netmods")) return null;
        String id = "";
        try{
            String body = readBody(exchange);
            if(!YZFText.blank(body)){
                Jval root = Jval.read(body);
                id = root.getString("id", "").trim();
            }
        }catch(Throwable ignored){
        }
        switch(path){
            case "/yzfnet/netmods" -> {
                return "{\"ok\":true,\"info\":\"" + escape(listNetModules().replace('\n', '|')) + "\"}";
            }
            case "/yzfnet/netmods/stop" -> {
                return "{\"ok\":true,\"message\":\"" + escape(stopNetModule(id)) + "\"}";
            }
            case "/yzfnet/netmods/restart" -> {
                return "{\"ok\":true,\"message\":\"" + escape(restartNetModule(YZFText.blank(id) ? "all" : id)) + "\"}";
            }
            case "/yzfnet/netmods/rescan" -> {
                return "{\"ok\":true,\"message\":\"" + escape(rescanNetModules()) + "\"}";
            }
            default -> {
                return null;
            }
        }
    }

    // ============================== event handlers (game thread) ==============================

    private void installEventHandlers(){
        sendHandler = event -> {
            if(event == null || event.packet == null) return;
            String name = packetName(event.packet);
            countPacket("S:" + name);
            if(dropFilters.contains("S:" + name)){
                event.isCancelled = true;
                return;
            }
            if(!rateBucketAllows("S:" + name)){
                event.isCancelled = true;
                rateLimitedPackets.incrementAndGet();
                return;
            }
            if(trySplit(event, name)){
                // Packet was split; the original oversized packet is cancelled.
                return;
            }
            if(observeSend){
                enqueueEvent("SendPacketEvent", "\"packet\":\"" + escape(name) + "\""
                    + ",\"connection\":\"" + escape(event.con == null ? "*" : connLabel(event.con)) + "\""
                    + ",\"except\":\"" + escape(event.except == null ? "" : connLabel(event.except)) + "\"");
            }
        };
        receiveHandler = event -> {
            if(event == null || event.packet == null) return;
            String name = packetName(event.packet);
            countPacket("R:" + name);
            if(dropFilters.contains("R:" + name)){
                event.isCancelled = true;
                return;
            }
            if(observeReceive){
                enqueueEvent("ReceivePacketEvent", "\"packet\":\"" + escape(name) + "\""
                    + ",\"connection\":\"" + escape(event.con == null ? "?" : connLabel(event.con)) + "\"");
            }
        };
        chatHandler = event -> {
            if(event == null || event.player == null) return;
            if(observeChat){
                enqueueEvent("PlayerChatEvent", "\"player\":\"" + escape(event.player.name) + "\""
                    + ",\"uuid\":\"" + escape(event.player.uuid() == null ? "" : event.player.uuid()) + "\""
                    + ",\"message\":\"" + escape(event.message == null ? "" : event.message) + "\"");
            }
        };
        joinHandler = event -> {
            if(event == null || event.player == null) return;
            if(observeJoins){
                enqueueEvent("PlayerJoin", "\"player\":\"" + escape(event.player.name) + "\""
                    + ",\"uuid\":\"" + escape(event.player.uuid() == null ? "" : event.player.uuid()) + "\"");
            }
        };
        leaveHandler = event -> {
            if(event == null || event.player == null) return;
            if(observeJoins){
                enqueueEvent("PlayerLeave", "\"player\":\"" + escape(event.player.name) + "\""
                    + ",\"uuid\":\"" + escape(event.player.uuid() == null ? "" : event.player.uuid()) + "\"");
            }
        };
        Events.on(SendPacketEvent.class, sendHandler);
        Events.on(ReceivePacketEvent.class, receiveHandler);
        Events.on(PlayerChatEvent.class, chatHandler);
        Events.on(PlayerJoin.class, joinHandler);
        Events.on(PlayerLeave.class, leaveHandler);
    }

    private void countPacket(String key){
        packetCounters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    // ============================== rate limiting ==============================

    private boolean rateBucketAllows(String key){
        RateBucket bucket = rateBuckets.get(key);
        if(bucket == null) return true;
        return bucket.tryConsume();
    }

    private void configureRateLimit(String key, int perSecond, int burst){
        if(perSecond <= 0){
            rateBuckets.remove(key);
        }else{
            rateBuckets.put(key, new RateBucket(perSecond, Math.max(perSecond, burst)));
        }
    }

    private static final class RateBucket{
        private final double perSecond;
        private final double capacity;
        private double tokens;
        private long lastRefillNanos = System.nanoTime();

        RateBucket(int perSecond, int capacity){
            this.perSecond = perSecond;
            this.capacity = capacity;
            this.tokens = capacity;
        }

        synchronized boolean tryConsume(){
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000d;
            lastRefillNanos = now;
            tokens = Math.min(capacity, tokens + elapsedSeconds * perSecond);
            if(tokens >= 1d){
                tokens -= 1d;
                return true;
            }
            return false;
        }
    }

    // ============================== big-packet splitting ==============================

    /** Returns true if the packet was split and the original should be cancelled. */
    private boolean trySplit(SendPacketEvent event, String packetName){
        if("off".equals(splitMode)) return false;
        String message = extractMessage(event.packet);
        if(message == null || message.length() < splitThreshold) return false;

        event.isCancelled = true;
        splitPackets.incrementAndGet();

        if("external".equals(splitMode)){
            // Delegate chunking to an external core module / TCP client.
            String kind = splitKind(event.packet);
            enqueueEvent("SplitRequest", "\"packet\":\"" + escape(packetName) + "\""
                + ",\"kind\":\"" + escape(kind) + "\""
                + ",\"length\":" + message.length()
                + ",\"chunkSize\":" + splitChunkSize
                + ",\"message\":\"" + escape(message) + "\"");
            return true;
        }

        // Internal mode: enqueue chunks for the scheduled pump.
        String kind = splitKind(event.packet);
        int start = 0;
        int index = 0;
        int total = (message.length() + splitChunkSize - 1) / splitChunkSize;
        while(start < message.length()){
            int end = Math.min(message.length(), start + splitChunkSize);
            String chunk = message.substring(start, end);
            pendingChunks.offer(new SplitChunk(kind, chunk, index, total));
            start = end;
            index++;
        }
        return true;
    }

    private String splitKind(Object packet){
        if(packet instanceof SendMessageCallPacket) return "sendMessage";
        if(packet instanceof InfoMessageCallPacket) return "infoMessage";
        if(packet instanceof AnnounceCallPacket) return "announce";
        if(packet instanceof SendChatMessageCallPacket) return "sendMessage";
        return "sendMessage";
    }

    private String extractMessage(Object packet){
        if(packet instanceof SendMessageCallPacket p) return p.message;
        if(packet instanceof InfoMessageCallPacket p) return p.message;
        if(packet instanceof AnnounceCallPacket p) return p.message;
        if(packet instanceof SendChatMessageCallPacket p) return p.message;
        return null;
    }

    /** Pump pending split chunks onto the game thread at a controlled rate. */
    private void startChunkPump(){
        if(chunkTask != null) return;
        float intervalSeconds = splitIntervalMs / 1000f;
        chunkTask = Timer.schedule(() -> {
            if(!running.get()) return;
            int dispatched = 0;
            SplitChunk chunk;
            while(dispatched < splitChunksPerTick && (chunk = pendingChunks.poll()) != null){
                sendSplitChunk(chunk);
                dispatched++;
            }
        }, intervalSeconds, intervalSeconds);
    }

    private void sendSplitChunk(SplitChunk chunk){
        switch(chunk.kind){
            case "infoMessage" -> Call.infoMessage(chunk.text);
            case "announce" -> Call.announce(chunk.text);
            default -> Call.sendMessage(chunk.text);
        }
    }

    private static final class SplitChunk{
        final String kind;
        final String text;
        final int index;
        final int total;

        SplitChunk(String kind, String text, int index, int total){
            this.kind = kind;
            this.text = text;
            this.index = index;
            this.total = total;
        }
    }

    // ============================== stats loop ==============================

    private void startStatsLoop(){
        if(statsTask != null) return;
        statsTask = Timer.schedule(() -> {
            if(!running.get()) return;
            YZFNetworkMetrics.sampleNow();
            StringBuilder fields = new StringBuilder();
            fields.append("\"uploadBps\":").append(YZFNetworkMetrics.currentUploadBps())
                .append(",\"downloadBps\":").append(YZFNetworkMetrics.currentDownloadBps())
                .append(",\"tps\":").append(Vars.actualServerTps)
                .append(",\"players\":").append(playerCount())
                .append(",\"pendingChunks\":").append(pendingChunks.size())
                .append(",\"rateLimited\":").append(rateLimitedPackets.get());
            String top = topPacketsJson();
            if(!top.isEmpty()){
                fields.append(",\"topPackets\":").append(top);
            }
            enqueueEvent("NetStatsEvent", fields.toString());
        }, 1f, 1f);
    }

    private String topPacketsJson(){
        if(packetCounters.isEmpty()) return "";
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        // Sample and reset counters.
        for(java.util.Map.Entry<String, AtomicLong> entry : packetCounters.entrySet()){
            long value = entry.getValue().getAndSet(0);
            if(value <= 0) continue;
            if(!first) builder.append(',');
            first = false;
            builder.append('"').append(escape(entry.getKey())).append("\":").append(value);
        }
        builder.append('}');
        return first ? "" : builder.toString();
    }

    private int playerCount(){
        int count = 0;
        for(Player ignored : Groups.player) count++;
        return count;
    }

    // ============================== dispatch ==============================

    private void enqueueEvent(String eventName, String fieldsJson){
        String line = "{\"type\":\"event\",\"event\":\"" + escape(eventName) + "\"," + fieldsJson + "}";
        if(dispatchQueueSize.get() >= DISPATCH_QUEUE_CAPACITY){
            droppedEvents.incrementAndGet();
            return;
        }
        dispatchQueue.offer(line);
        dispatchQueueSize.incrementAndGet();
    }

    private void dispatchLoop(){
        while(running.get() || !dispatchQueue.isEmpty()){
            String line = dispatchQueue.poll();
            if(line == null){
                try{
                    Thread.sleep(5);
                }catch(InterruptedException interrupt){
                    return;
                }
                continue;
            }
            dispatchQueueSize.decrementAndGet();
            String eventName = extractEventName(line);
            synchronized(clients){
                for(GatewayClient client : clients.values()){
                    if(client.subscribedAll || client.subscribed.contains(eventName)){
                        if(client.sendLine(line)){
                            deliveredEvents.incrementAndGet();
                        }
                    }
                }
            }
        }
    }

    private static String extractEventName(String line){
        int start = line.indexOf("\"event\":\"");
        if(start < 0) return "";
        start += 9;
        int end = line.indexOf('"', start);
        return end < 0 ? "" : line.substring(start, end);
    }

    private static String packetName(Object packet){
        return packet == null ? "null" : packet.getClass().getSimpleName();
    }

    private static String connLabel(mindustry.net.NetConnection connection){
        return connection.address == null ? "?" : connection.address;
    }

    private static String escape(String value){
        if(value == null) return "";
        StringBuilder builder = new StringBuilder(value.length());
        for(int i = 0; i < value.length(); i++){
            char c = value.charAt(i);
            switch(c){
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if(c < 0x20){
                        builder.append(String.format("\\u%04x", (int)c));
                    }else{
                        builder.append(c);
                    }
                }
            }
        }
        return builder.toString();
    }

    // ============================== HTTP transport ==============================

    private void startHttp() throws IOException{
        InetAddress bind = InetAddress.getByName(httpAddress);
        YZFExternalAccessConfig policy = access();
        if(policy != null && !policy.allowsSocketBind(bind)){
            Log.err("[NetGateway] 拒绝公网绑定 HTTP 端口 @（external-access 需要 allowInsecurePublicSocket: true）。", httpPort);
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "YZFNetGateway-HTTP");
            thread.setDaemon(true);
            return thread;
        });
        httpServer = HttpServer.create(new InetSocketAddress(bind, httpPort), 64);
        httpServer.setExecutor(pool);
        httpServer.createContext("/", this::handleHttp);
        httpServer.start();
    }

    private void handleHttp(HttpExchange exchange){
        try{
            String path = exchange.getRequestURI().getPath();
            if("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())){
                cors(exchange);
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if(!authenticated(exchange.getRemoteAddress().getAddress(), exchange.getRequestHeaders().getFirst("Authorization"))){
                cors(exchange);
                respond(exchange, 401, "{\"ok\":false,\"error\":\"authentication required\"}");
                return;
            }
            switch(path){
                case "/yzfnet/status", "/yzfnet" -> respond(exchange, 200, "{\"ok\":true," + statusJson() + "}");
                case "/yzfnet/players" -> respond(exchange, 200, "{\"ok\":true,\"players\":" + playersJson() + "}");
                case "/yzfnet/filters" -> respond(exchange, 200, "{\"ok\":true,\"filters\":" + filtersJson() + "}");
                case "/yzfnet/broadcast" -> respond(exchange, 200, handleActionJson("broadcast", readBody(exchange)));
                case "/yzfnet/command" -> respond(exchange, 200, handleActionJson("command", readBody(exchange)));
                case "/yzfnet/kick" -> respond(exchange, 200, handleActionJson("kick", readBody(exchange)));
                case "/yzfnet/filter" -> respond(exchange, 200, handleActionJson("filter", readBody(exchange)));
                case "/yzfnet/say" -> respond(exchange, 200, handleActionJson("broadcast", readBody(exchange)));
                default -> {
                    // Core network module management endpoints. Build scripts use
                    // /yzfnet/netmods/stop to release the locked .exe on Windows before
                    // replacing the binary; the file watcher then restarts the module.
                    String result = handleNetModEndpoint(path, exchange);
                    if(result == null){
                        respond(exchange, 404, "{\"ok\":false,\"error\":\"unknown endpoint\"}");
                    }else{
                        respond(exchange, 200, result);
                    }
                }
            }
        }catch(Throwable error){
            try{
                respond(exchange, 500, "{\"ok\":false,\"error\":\"" + escape(YZFText.blank(error.getMessage()) ? error.getClass().getSimpleName() : error.getMessage()) + "\"}");
            }catch(Throwable ignored){
            }
        }finally{
            exchange.close();
        }
    }

    private String readBody(HttpExchange exchange) throws IOException{
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void cors(HttpExchange exchange){
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
    }

    private void respond(HttpExchange exchange, int code, String body) throws IOException{
        cors(exchange);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private String statusJson(){
        return "\"enabled\":true"
            + ",\"serverOpen\":" + (Vars.state.isGame() || !Vars.state.isMenu())
            + ",\"players\":" + playerCount()
            + ",\"wave\":" + Vars.state.wave
            + ",\"map\":\"" + escape(Vars.state.map == null ? "" : Vars.state.map.name()) + "\""
            + ",\"tps\":" + Vars.actualServerTps
            + ",\"uploadBps\":" + YZFNetworkMetrics.currentUploadBps()
            + ",\"downloadBps\":" + YZFNetworkMetrics.currentDownloadBps()
            + ",\"gatewayClients\":" + clientCount()
            + ",\"coreModules\":" + netModules.size
            + ",\"splitMode\":\"" + escape(splitMode) + "\""
            + ",\"splitPackets\":" + splitPackets.get()
            + ",\"rateLimited\":" + rateLimitedPackets.get()
            + ",\"deliveredEvents\":" + deliveredEvents.get()
            + ",\"droppedEvents\":" + droppedEvents.get();
    }

    private String playersJson(){
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for(Player player : Groups.player){
            if(!first) builder.append(',');
            first = false;
            builder.append("{\"name\":\"").append(escape(player.name))
                .append("\",\"uuid\":\"").append(escape(player.uuid() == null ? "" : player.uuid()))
                .append("\",\"address\":\"").append(escape(player.con == null ? "" : connLabel(player.con)))
                .append("\",\"team\":\"").append(player.team().name)
                .append("\",\"admin\":").append(player.admin)
                .append('}');
        }
        return builder.append(']').toString();
    }

    private String filtersJson(){
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        synchronized(dropFilters){
            for(String filter : dropFilters){
                if(!first) builder.append(',');
                first = false;
                builder.append('"').append(escape(filter)).append('"');
            }
        }
        return builder.append(']').toString();
    }

    // ============================== TCP transport ==============================

    private void startTcp() throws IOException{
        InetAddress bind = InetAddress.getByName(tcpAddress);
        YZFExternalAccessConfig policy = access();
        if(policy != null && !policy.allowsSocketBind(bind)){
            Log.err("[NetGateway] 拒绝公网绑定 TCP 端口 @（external-access 需要 allowInsecurePublicSocket: true）。", tcpPort);
            return;
        }
        tcpServerSocket = new ServerSocket();
        tcpServerSocket.setReuseAddress(true);
        tcpServerSocket.bind(new InetSocketAddress(bind, tcpPort));
        tcpAcceptThread = new Thread(() -> {
            while(running.get()){
                try{
                    Socket socket = tcpServerSocket.accept();
                    Thread clientThread = new Thread(() -> handleTcpClient(socket), "YZFNetGateway-TCP-Client");
                    clientThread.setDaemon(true);
                    clientThread.start();
                }catch(IOException error){
                    if(running.get()){
                        Log.warn("[NetGateway] TCP accept 失败: @", YZFText.blank(error.getMessage()) ? error : error.getMessage());
                    }
                }
            }
        }, "YZFNetGateway-TCP-Accept");
        tcpAcceptThread.setDaemon(true);
        tcpAcceptThread.start();
    }

    private void handleTcpClient(Socket socket){
        GatewayClient client = new GatewayClient("tcp:" + socket.getRemoteSocketAddress(), socket.getInetAddress());
        try(BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))){
            client.output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            client.sendLine("{\"type\":\"hello\",\"gateway\":\"MindustryYZF\",\"version\":\"" + escape(MindustryYZF.version) + "\",\"message\":\"发送 {\\\"type\\\":\\\"hello\\\",\\\"token\\\":\\\"...\\\",\\\"clientId\\\":\\\"...\\\"} 完成认证\"}");
            socket.setSoTimeout(15000);
            String helloLine = input.readLine();
            socket.setSoTimeout(0);
            if(helloLine == null || !authenticateClient(client, helloLine)){
                client.sendLine("{\"type\":\"hello\",\"ok\":false,\"error\":\"authentication required\"}");
                return;
            }
            registerClient(client);
            client.sendLine("{\"type\":\"hello\",\"ok\":true,\"clientId\":\"" + escape(client.id) + "\"}");
            Log.info("[NetGateway] 外部网络模块已连接: @ (@)", client.id, client.address);
            String line;
            while(running.get() && (line = input.readLine()) != null){
                if(line.length() > MAX_EVENT_LINE_CHARS) continue;
                if(YZFText.blank(line)) continue;
                handleAction(client, line);
            }
        }catch(IOException error){
            if(running.get() && !YZFText.blank(error.getMessage()) && !"Connection reset".equals(error.getMessage())){
                Log.warn("[NetGateway] 外部网络模块断开: @ (@)", client.id, error.getMessage());
            }
        }finally{
            unregisterClient(client);
            client.closeQuietly();
        }
    }

    private boolean authenticateClient(GatewayClient client, String helloLine){
        try{
            Jval root = Jval.read(helloLine);
            if(!"hello".equals(root.getString("type", ""))) return false;
            String clientId = root.getString("clientId", "").trim();
            if(!clientId.isEmpty()) client.id = clientId;
            client.isCoreModule = root.getBool("coreModule", false);
            if(YZFText.blank(effectiveToken())) return true;
            String presented = root.getString("token", "");
            return authenticated(client.address, presented);
        }catch(Throwable error){
            return false;
        }
    }

    // ============================== embedded processes ==============================

    private void startProcessManager(){
        Thread thread = new Thread(() -> {
            for(ProcessDefinition definition : processDefinitions){
                if(!running.get()) return;
                if(!definition.enabled){
                    Log.info("[NetGateway] 内嵌外部进程已跳过（禁用）: @", definition.name);
                    continue;
                }
                spawnProcess(definition);
            }
        }, "YZFNetGateway-Processes");
        thread.setDaemon(true);
        thread.start();
    }

    private void spawnProcess(ProcessDefinition definition){
        try{
            Seq<String> commandLine = new Seq<>();
            commandLine.add(definition.command);
            commandLine.addAll(definition.args);
            ProcessBuilder builder = new ProcessBuilder(commandLine.toArray(String.class));
            builder.directory(paths.root.file());
            builder.environment().put("YZF_PROTOCOL", "ndjson-stdio");
            builder.environment().put("YZF_GATEWAY", "netgateway");
            builder.environment().put("YZF_TOKEN", effectiveToken());
            Process process = builder.start();
            EmbeddedProcess embedded = new EmbeddedProcess(definition.name, process);
            embedded.client = new GatewayClient("process:" + definition.name, null);
            embedded.client.output = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            embedded.client.subscribedAll = true;
            embedded.client.authenticated = true;
            registerClient(embedded.client);
            synchronized(processes){
                processes.add(embedded);
            }
            embedded.client.sendLine("{\"type\":\"hello\",\"gateway\":\"MindustryYZF\",\"version\":\"" + escape(MindustryYZF.version) + "\",\"ok\":true,\"clientId\":\"" + escape(embedded.client.id) + "\"}");

            Thread reader = new Thread(() -> {
                try(BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))){
                    String line;
                    while(running.get() && (line = input.readLine()) != null){
                        if(YZFText.blank(line)) continue;
                        if(line.length() > MAX_EVENT_LINE_CHARS) continue;
                        handleAction(embedded.client, line);
                    }
                }catch(IOException ignored){
                }finally{
                    unregisterClient(embedded.client);
                }
            }, "YZFNetGateway-Process-" + definition.name);
            reader.setDaemon(true);
            reader.start();

            Thread stderr = new Thread(() -> {
                try(BufferedReader input = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))){
                    String line;
                    while((line = input.readLine()) != null){
                        Log.info("[NetGateway:@] @", definition.name, line);
                    }
                }catch(IOException ignored){
                }
            }, "YZFNetGateway-Process-" + definition.name + "-stderr");
            stderr.setDaemon(true);
            stderr.start();

            Log.info("[NetGateway] 内嵌外部进程已启动: @ (@)", definition.name, definition.command);
        }catch(Throwable error){
            YZFErrorLog.high("netgateway", "Failed to spawn embedded process " + definition.name, error);
        }
    }

    // ============================== client registry ==============================

    private void registerClient(GatewayClient client){
        synchronized(clients){
            clients.put(client.id, client);
        }
    }

    private void unregisterClient(GatewayClient client){
        synchronized(clients){
            if(clients.get(client.id) == client){
                clients.remove(client.id);
            }
        }
    }

    private int clientCount(){
        synchronized(clients){
            return clients.size;
        }
    }

    // ============================== action handling ==============================

    private String handleActionJson(String type, String body){
        try{
            Jval root = YZFText.blank(body) ? Jval.newObject() : Jval.read(body);
            Jval merged = Jval.newObject();
            merged.put("type", type);
            Jval fields = Jval.newObject();
            for(var entry : root.asObject()){
                fields.put(entry.key, entry.value.asString());
            }
            merged.put("fields", fields);
            return handleAction(new GatewayClient("http", null), merged.toString(Jval.Jformat.plain));
        }catch(Throwable error){
            return "{\"ok\":false,\"error\":\"" + escape(YZFText.blank(error.getMessage()) ? "bad request" : error.getMessage()) + "\"}";
        }
    }

    /** Handles one NDJSON action line from any transport. Returns a reply JSON line. */
    private String handleAction(GatewayClient client, String line){
        try{
            Jval root = Jval.read(line);
            String type = root.getString("type", "").trim().toLowerCase();
            String replyId = root.getString("replyId", "");
            Jval fields = root.get("fields");
            if(fields == null || !fields.isObject()) fields = root;
            String result = dispatchAction(client, type, fields);
            actionsHandled.incrementAndGet();
            String reply = "{\"type\":\"reply\",\"action\":\"" + escape(type) + "\",\"replyId\":\"" + escape(replyId) + "\"," + result + "}";
            if(client.output != null){
                client.sendLine(reply);
            }
            return reply;
        }catch(Throwable error){
            String reply = "{\"type\":\"reply\",\"ok\":false,\"error\":\"" + escape(YZFText.blank(error.getMessage()) ? error.getClass().getSimpleName() : error.getMessage()) + "\"}";
            if(client.output != null){
                client.sendLine(reply);
            }
            return reply;
        }
    }

    private String dispatchAction(GatewayClient client, String type, Jval fields){
        switch(type){
            case "hello" -> {
                String clientId = fields.getString("clientId", "").trim();
                if(!clientId.isEmpty()){
                    synchronized(clients){
                        if(clients.get(client.id) == client && !client.id.equals(clientId)){
                            clients.remove(client.id);
                            client.id = clientId;
                            clients.put(clientId, client);
                        }
                    }
                }
                return "\"ok\":true";
            }
            case "subscribe" -> {
                String event = fields.getString("event", "").trim();
                if(event.equalsIgnoreCase("all")){
                    client.subscribedAll = true;
                }else if(!event.isEmpty()){
                    client.subscribed.add(event);
                }
                return "\"ok\":true,\"subscribedAll\":" + client.subscribedAll;
            }
            case "unsubscribe" -> {
                String event = fields.getString("event", "").trim();
                if(event.equalsIgnoreCase("all")){
                    client.subscribedAll = false;
                    client.subscribed.clear();
                }else{
                    client.subscribed.remove(event);
                }
                return "\"ok\":true";
            }
            case "broadcast", "say" -> {
                String message = fields.getString("message", "").trim();
                if(message.isEmpty()) return "\"ok\":false,\"error\":\"message is empty\"";
                Core.app.post(() -> Call.sendMessage(message));
                return "\"ok\":true";
            }
            case "command" -> {
                String command = fields.getString("line", fields.getString("command", "")).trim();
                if(command.isEmpty()) return "\"ok\":false,\"error\":\"command is empty\"";
                Core.app.post(() -> serverControl.handleCommandString(command));
                return "\"ok\":true";
            }
            case "kick" -> {
                String name = fields.getString("player", "").trim();
                String reason = fields.getString("reason", "kicked by external network module");
                if(name.isEmpty()) return "\"ok\":false,\"error\":\"player is empty\"";
                Core.app.post(() -> {
                    for(Player player : Groups.player){
                        if(player.name.equalsIgnoreCase(name) || (player.uuid() != null && player.uuid().equals(name))){
                            player.kick(reason);
                            return;
                        }
                    }
                });
                return "\"ok\":true,\"queued\":true";
            }
            case "filter" -> {
                String event = fields.getString("event", "receive").trim().toLowerCase();
                String packet = fields.getString("packet", "").trim();
                String action = fields.getString("action", "drop").trim().toLowerCase();
                if(packet.isEmpty()) return "\"ok\":false,\"error\":\"packet is empty\"";
                String key = (event.startsWith("send") ? "S:" : "R:") + packet;
                if(action.equals("drop") || action.equals("cancel")){
                    synchronized(dropFilters){
                        dropFilters.add(key);
                    }
                }else{
                    synchronized(dropFilters){
                        dropFilters.remove(key);
                    }
                }
                return "\"ok\":true,\"filter\":\"" + escape(key) + "\",\"action\":\"" + escape(action) + "\"";
            }
            case "ratelimit", "rate_limit" -> {
                String event = fields.getString("event", "send").trim().toLowerCase();
                String packet = fields.getString("packet", "").trim();
                int perSecond = parseInt(fields.getString("perSecond", "0"), 0);
                int burst = parseInt(fields.getString("burst", String.valueOf(perSecond)), perSecond);
                if(packet.isEmpty()) return "\"ok\":false,\"error\":\"packet is empty\"";
                String key = (event.startsWith("send") ? "S:" : "R:") + packet;
                configureRateLimit(key, perSecond, burst);
                return "\"ok\":true,\"key\":\"" + escape(key) + "\",\"perSecond\":" + perSecond + ",\"burst\":" + burst;
            }
            case "splitpolicy", "split_policy" -> {
                String mode = fields.getString("mode", "").trim().toLowerCase();
                if(!mode.isEmpty()){
                    if(mode.equals("off") || mode.equals("internal") || mode.equals("external")){
                        splitMode = mode;
                    }
                }
                int threshold = parseInt(fields.getString("threshold", ""), 0);
                if(threshold > 0) splitThreshold = Math.max(16, threshold);
                int chunkSize = parseInt(fields.getString("chunkSize", ""), 0);
                if(chunkSize > 0) splitChunkSize = Math.max(16, chunkSize);
                int interval = parseInt(fields.getString("intervalMs", ""), 0);
                if(interval > 0) splitIntervalMs = Math.max(5, interval);
                return "\"ok\":true,\"mode\":\"" + escape(splitMode) + "\",\"threshold\":" + splitThreshold
                    + ",\"chunkSize\":" + splitChunkSize + ",\"intervalMs\":" + splitIntervalMs;
            }
            case "split.send" -> {
                // External module returns one chunk to be sent to all players.
                String kind = fields.getString("kind", "sendMessage").trim();
                String text = fields.getString("text", fields.getString("message", ""));
                if(YZFText.blank(text)) return "\"ok\":false,\"error\":\"text is empty\"";
                Core.app.post(() -> {
                    switch(kind){
                        case "infoMessage" -> Call.infoMessage(text);
                        case "announce" -> Call.announce(text);
                        default -> Call.sendMessage(text);
                    }
                });
                return "\"ok\":true";
            }
            case "status" -> {
                return "\"ok\":true," + statusJson();
            }
            case "players" -> {
                return "\"ok\":true,\"players\":" + playersJson();
            }
            case "ping" -> {
                return "\"ok\":true,\"time\":" + System.currentTimeMillis();
            }
            default -> {
                return "\"ok\":false,\"error\":\"unknown action '" + escape(type) + "'\"";
            }
        }
    }

    private static int parseInt(String value, int fallback){
        try{
            return YZFText.blank(value) ? fallback : Integer.parseInt(value.trim());
        }catch(NumberFormatException error){
            return fallback;
        }
    }

    // ============================== helper types ==============================

    private static final class GatewayClient{
        volatile String id;
        final InetAddress address;
        volatile BufferedWriter output;
        volatile boolean subscribedAll;
        volatile boolean authenticated;
        volatile boolean isCoreModule;
        final ObjectSet<String> subscribed = new ObjectSet<>();
        private final Object writeLock = new Object();

        GatewayClient(String id, InetAddress address){
            this.id = id;
            this.address = address;
        }

        boolean sendLine(String line){
            BufferedWriter writer = output;
            if(writer == null) return false;
            try{
                synchronized(writeLock){
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                }
                return true;
            }catch(IOException error){
                return false;
            }
        }

        void closeQuietly(){
            BufferedWriter writer = output;
            output = null;
            if(writer != null){
                try{
                    writer.close();
                }catch(IOException ignored){
                }
            }
        }
    }

    private static final class ProcessDefinition{
        final String name;
        final String command;
        final Seq<String> args = new Seq<>();
        boolean enabled = true;

        ProcessDefinition(String name, String command){
            this.name = name;
            this.command = command;
        }
    }

    /** A core network module discovered in the netmods folder. */
    private static final class NetModuleDefinition{
        final Fi dir;
        String id;
        String name;
        String version;
        int priority = 100;
        boolean enabled = true;
        String command;
        final Seq<String> args = new Seq<>();
        // Hot-build configuration: buildType go|cpp|c|cxx, buildSource for cpp (e.g. src/main.cpp),
        // buildScript for custom build (e.g. build.bat). Empty means no explicit build config.
        String buildType = "";
        String buildSource = "";
        String buildScript = "";

        NetModuleDefinition(Fi dir){
            this.dir = dir;
        }
    }

    private static final class EmbeddedProcess{
        final String name;
        final Process process;
        GatewayClient client;

        EmbeddedProcess(String name, Process process){
            this.name = name;
            this.process = process;
        }

        void destroy(){
            try{
                if(client != null){
                    client.sendLine("{\"type\":\"shutdown\"}");
                }
            }catch(Throwable ignored){
            }
            process.destroy();
            try{
                if(!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)){
                    process.destroyForcibly();
                }
            }catch(InterruptedException ignored){
            }
        }
    }

    public int httpPort(){ return httpPort; }
    public int tcpPort(){ return tcpPort; }
    public boolean running(){ return running.get(); }
}
