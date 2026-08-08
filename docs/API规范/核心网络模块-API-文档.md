# 核心网络模块（core-network-modules）超详细开发文档

## 稳定性标记说明

| 标记 | 含义 |
| --- | --- |
| `🟢 稳定` | 签名和行为已定型，向后兼容，可用于生产。 |
| `🟡 实验性` | 可用但细节可能调整，建议做好容错。 |
| `🔴 已弃用` | 未来版本移除，文中给出替代方案。 |

## 目录

- [五分钟快速上手](#五分钟快速上手)
  - [第 1 步：创建模块目录与元数据](#第-1-步创建模块目录与元数据)
  - [第 2 步：启动与热添加](#第-2-步启动与热添加)
  - [第 3 步：验证](#第-3-步验证)
  - [快速上手之后](#快速上手之后)
- [1. 系统概述](#1-系统概述)
- [2. 目录结构](#2-目录结构)
- [3. 安装与部署](#3-安装与部署)
- [4. 配置完全指南](#4-配置完全指南)
  - [4.1 netmodule.hjson 字段说明](#41-netmodulehjson-字段说明)
  - [4.2 netgateway.hjson 网关配置](#42-netgatewayhjson-网关配置)
  - [4.3 模块自身配置文件（config.hjson）](#43-模块自身配置文件confighjson)
  - [4.4 配置修改后如何生效](#44-配置修改后如何生效)
- [5. 核心概念：网关管线与整形机制](#5-核心概念网关管线与整形机制)
- [6. NDJSON 协议完全参考（模块 → 网关动作）](#6-ndjson-协议完全参考模块--网关动作)
- [7. 事件参考（网关 → 模块推送）](#7-事件参考网关--模块推送)
- [8. HTTP REST API 参考](#8-http-rest-api-参考)
- [9. TCP NDJSON 通道参考](#9-tcp-ndjson-通道参考)
- [10. 热编译与热重载](#10-热编译与热重载)
- [11. 服务端命令参考（yzf net）](#11-服务端命令参考yzf-net)
- [12. 内置模块详解：netwatch（带宽哨兵，Go）](#12-内置模块详解netwatch带宽哨兵go)
- [13. 内置模块详解：packet-splitter（大包拆分器，C++）](#13-内置模块详解packet-splitter大包拆分器c)
- [14. 权限、安全与审计](#14-权限安全与审计)
- [15. 错误处理与容错](#15-错误处理与容错)
- [16. 陷阱与注意事项](#16-陷阱与注意事项)
- [17. 完整模块模板](#17-完整模块模板)
- [18. 可观测性：status 字段与统计计数器](#18-可观测性status-字段与统计计数器)
- [19. FAQ](#19-faq)
- [20. 版本与待确认清单](#20-版本与待确认清单)

---

## 五分钟快速上手

核心网络模块（core network module）是放在 `config/yzf/netmods/` 下、用**任意语言**（Go / C++ / Rust / Python…）编写的独立可执行程序。网关（`YZFNetGateway`）按 `priority` 升序启动它们，通过 **stdin/stdout NDJSON** 通信，让它们参与游戏服务器的收发包管线：观察带宽与包流量、对指定包类型限速、接管超长消息的拆分。

### 第 1 步：创建模块目录与元数据

在运行目录（服务端 jar 所在目录）下创建：

```text
config/yzf/netmods/
└── my-module/
    ├── netmodule.hjson   # 模块元数据（必需）
    └── my-module.exe     # 可执行文件（command 指向它）
```

`netmodule.hjson` 最小内容：

```hjson
{
  id: my-module
  name: "我的网络模块"
  version: "1.0.0"
  priority: 50
  enabled: true
  command: "my-module.exe"
  args: []
}
```

如果只有源码（例如 Go），加上 `build` 段，网关会**自动编译**：

```hjson
{
  id: my-module
  name: "我的网络模块"
  version: "1.0.0"
  priority: 50
  enabled: true
  command: "my-module.exe"
  args: []
  build: { type: "go" }   # 网关自动执行 go build -o my-module.exe .
}
```

### 第 2 步：启动与热添加

两种情况：

1. **服务端启动前**就放好了模块：启动服务端即可，网关自动扫描并启动。
2. **服务端运行中**放入模块：在控制台执行热添加：

```text
yzf net rescan
```

预期输出：

```text
[I] [MindustryYZF] 已热添加模块: my-module
[I] [NetGateway] 核心网络模块已启动: my-module v1.0.0 (priority 50)
```

若配置了 `build` 段且二进制不存在，会先看到：

```text
[I] [NetGateway] 核心网络模块 my-module 缺少二进制，先热编译源码...
[I] [NetGateway] 热编译核心网络模块: my-module (type=go)
[I] [NetGateway] 模块 my-module 热编译成功，耗时 xxx ms。
```

### 第 3 步：验证

```text
yzf net mods
```

预期输出（节选）：

```text
[I] [MindustryYZF] 核心网络模块：
核心网络模块目录: <运行目录>\config\yzf\netmods
  my-module v1.0.0 priority=50 [运行中] command=my-module.exe
```

再看网关整体状态：

```text
yzf net status
```

其中 `核心网络模块: N 个定义, M 个运行中` 一行应与你放入的模块数一致。

### 快速上手之后

- 想知道模块能收到什么事件、能发什么动作：读 [第 6 章](#6-ndjson-协议完全参考模块--网关动作)、[第 7 章](#7-事件参考网关--模块推送)。
- 想用 HTTP/TCP 而不是内嵌进程接入：读 [第 8 章](#8-http-rest-api-参考)、[第 9 章](#9-tcp-ndjson-通道参考)。
- 想改完源码自动重编译重启：读 [第 10 章](#10-热编译与热重载)。
- 想看两个官方内置模块怎么写的：读 [第 12 章](#12-内置模块详解netwatch带宽哨兵go)、[第 13 章](#13-内置模块详解packet-splitter大包拆分器c)。

---

## 1. 系统概述

### 1.1 它解决什么问题

Mindustry 服务端在高玩家数、大面积方块变化时，会出现：

- 单个超大文本包（公告、长消息）瞬间打满上行带宽，造成全体玩家卡顿/掉线/不同步；
- `BlockSnapshotCallPacket`（方块快照）、`SyncCallPacket`（实体同步）突发刷屏，带宽压力陡增。

核心网络模块机制让**服务器之外的独立程序**实时观察流量并动手整形：

| 能力 | 实现方 | 说明 |
| --- | --- | --- |
| 带宽/TPS/分包计数观察 | 网关 `NetStatsEvent`（每秒推送） | 数据来自 `YZFNetworkMetrics` 与包计数器 |
| 按包类型限速（令牌桶） | 动作 `rateLimit` | 在发送路径本地判定，无跨进程往返 |
| 超长消息拆分 | `splitPolicy` + `SplitRequest`/`split.send` | 内部定时分片或委托外部模块分片 |
| 按包类型丢弃 | 动作 `filter` | 发送/接收两个方向 |
| 广播/执行命令/踢人 | 动作 `broadcast` / `command` / `kick` | 投递到游戏主线程执行 |
| 状态查询 | 动作 `status` / `players` / `ping` | 同步返回 JSON |

### 1.2 与框架的关系

- 网关实现：`server/src/mindustry/yzf/YZFNetGateway.java`（约 2100 行）。
- 热重载监听：`server/src/mindustry/yzf/YZFNetModHotReloadWatcher.java`。
- 带宽采样：`core/src/mindustry/net/YZFNetworkMetrics.java`。
- 命令入口：`YZFServerCommands.handleNetGateway`（`yzf net ...`）。
- 核心网络模块**不是普通 JS/KT 插件**：不走 `yzf/plugins/`，不经过脚本运行时，是网关直接 spawn 的操作系统进程。没有模块时服务端保持原版/改版网络行为，网关不强制依赖任何特定模块。

### 1.3 三种接入形态

| 形态 | 适用 | 通信 | 认证 |
| --- | --- | --- | --- |
| 内嵌核心模块（netmods/） | 与服务器同机、随服务端生命周期 | stdin/stdout NDJSON | 免认证（网关注册时 `authenticated=true`） |
| TCP 客户端 | 跨机器常驻工具 | TCP NDJSON（默认 `localhost:7101`） | hello 行带 token |
| HTTP 客户端 | 无状态一次性操作（广播/踢人/查状态） | HTTP（默认 `localhost:7100`） | `Authorization: Bearer <token>` |

### 1.4 功能清单总表

| 功能 | 入口 | 说明 |
| --- | --- | --- |
| 订阅事件 | 动作 `subscribe` | 事件名或 `all` |
| 限速 | 动作 `rateLimit` | 令牌桶，`perSecond<=0` 移除规则 |
| 拆包策略 | 动作 `splitPolicy` | `off`/`internal`/`external` |
| 回传分片 | 动作 `split.send` | external 模式专用 |
| 丢弃过滤 | 动作 `filter` | `drop`/`cancel` 添加，其余移除 |
| 全服广播 | 动作 `broadcast`/`say` | `Call.sendMessage` |
| 执行服务端命令 | 动作 `command` | 等价控制台输入 |
| 踢人 | 动作 `kick` | 按名字或 UUID |
| 查状态/玩家 | 动作 `status`/`players`/`ping` | 同步应答 |
| 模块热管理 | `yzf net mods/rescan/restart/stopmod` | 见 [第 11 章](#11-服务端命令参考yzf-net) |

---

## 2. 目录结构

### 2.1 仓库源码目录 `core-network-modules/`

```text
core-network-modules/
├── netwatch/                    # 带宽哨兵（Go）
│   ├── netmodule.hjson          # 元数据：build: { type: "go" }
│   ├── main.go                  # 全部逻辑，137 行，纯标准库
│   ├── go.mod                   # module netwatch（go 1.26.2）
│   └── build.bat                # 手动构建 + 经网关停模块解锁 exe + 替换
└── packet-splitter/             # 大包拆分器（C++17）
    ├── netmodule.hjson          # 元数据：build: { type: "cpp", source: "src/main.cpp" }
    ├── config.hjson             # 运行时参数（拆包阈值/整形水位/突发限速）
    ├── src/main.cpp             # 全部逻辑，523 行，纯标准库
    └── build.bat                # （目录内另有构建脚本，内容同 netwatch 模式）
```

> 这两个目录是**模板与出厂示例**。真正被网关加载的是运行目录下的 `config/yzf/netmods/<模块>/`（首次启动时网关会自动创建 `netmods/` 并写入 `README.txt` 说明）。

### 2.2 运行时目录 `config/yzf/netmods/`

```text
config/yzf/netmods/
├── README.txt                   # 网关首次扫描时自动写入的目录说明
├── netwatch/
│   ├── netmodule.hjson
│   ├── main.go
│   ├── go.mod
│   └── netwatch.exe             # 热编译产物（首次启动自动生成）
└── packet-splitter/
    ├── netmodule.hjson
    ├── config.hjson
    ├── src/main.cpp
    ├── packet-splitter.exe      # 热编译产物
    └── main.obj                 # MSVC 中间产物（指纹计算会忽略）
```

每个子文件夹 = 一个核心网络模块。文件夹名不要求与 `id` 一致（`id` 缺省时回落到文件夹名）。

### 2.3 相关配置文件

| 文件 | 作用 |
| --- | --- |
| `config/yzf/config/netgateway.hjson` | 网关总配置（首次启动自动生成，见 [4.2](#42-netgatewayhjson-网关配置)） |
| `config/yzf/config/external-access.hjson` | 外部访问策略；公网绑定端口、令牌回退都受它约束（详见 `外部访问安全规范.md`） |

---

## 3. 安装与部署

### 3.1 前置条件

| 场景 | 需要 |
| --- | --- |
| 使用现成二进制 | 无额外依赖，放入即可 |
| Go 热编译（`build.type: "go"`） | 系统 PATH 里有 `go`（`go version` 可运行） |
| C++ 热编译（`build.type: "cpp"`） | 已安装 Visual Studio Build Tools（网关自动搜索 `vcvars64.bat`），或配置 `build.script` 用自己的编译器 |
| 自定义构建（`build.script`） | 脚本自身依赖（如 cmake、mingw） |

### 3.2 部署步骤（源码即部署）

1. 把模块文件夹复制到 `config/yzf/netmods/` 下（只需源码 + `netmodule.hjson`）。
2. 确认 `config/yzf/config/netgateway.hjson` 中 `enabled: true`、`netmods.hotReload: true`。
3. 启动服务端（或运行中执行 `yzf net rescan`）。
4. 网关发现缺少二进制 → 自动热编译 → 启动模块。

首次部署的预期日志：

```text
[I] [NetGateway] 核心网络模块 netwatch 缺少二进制，先热编译源码...
[I] [NetGateway] 热编译核心网络模块: netwatch (type=go)
[I] [NetGateway] 模块 netwatch 热编译成功，耗时 812 ms。
[I] [NetGateway] 核心网络模块已启动: netwatch v1.0.0 (priority 20)
```

### 3.3 启用/禁用/移除

- **临时禁用**：`netmodule.hjson` 里 `enabled: false` 保存。热重载监听检测到元数据变化后自动停止该模块（无需重启服务端）。
- **热停止**：`yzf net stopmod <id>`。
- **彻底移除**：删除模块文件夹，监听器自动停止进程。
- **重启服务端**：所有模块随网关关闭（网关先向每个模块发送 `{"type":"shutdown"}`，等待 2 秒后强制销毁）。

### 3.4 版本兼容说明

- 协议：`YZF_PROTOCOL=ndjson-stdio`（环境变量），单行 JSON（NDJSON），UTF-8。
- 网关下发 hello 中携带 `version`（MindustryYZF 版本号）与 `gateway: "MindustryYZF"`，模块可据此做版本分支。
- 模块读取的单行长度上限 1 MiB（`MAX_EVENT_LINE_CHARS = 1024*1024`），超长的行被丢弃。

---

## 4. 配置完全指南

### 4.1 netmodule.hjson 字段说明

**用途：** 描述一个核心网络模块。网关扫描 `netmods/` 下每个子文件夹时读取（`netmodule.hjson` 优先，缺省时尝试 `netmodule.json`）。

| 字段 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | string | 否 | 文件夹名 | 模块唯一标识；命令、日志、指纹均用它 |
| `name` | string | 否 | 同 `id` | 展示名称，可中文 |
| `version` | string | 否 | `"1.0.0"` | 展示版本号 |
| `priority` | int | 否 | `100` | 启动顺序，**升序**（数字越小越先启动） |
| `enabled` | bool | 否 | `true` | `false` 时扫描到也不启动；运行中改为 false 会被热停止 |
| `command` | string | **是** | 无 | 可执行文件路径；相对路径相对**模块文件夹**解析，也可绝对路径。缺失则整个模块被跳过并告警 |
| `args` | string[] | 否 | `[]` | 追加给可执行文件的命令行参数 |
| `build.type` | string | 否 | 无 | 热编译类型：`go` / `cpp` / `c` / `cxx`；留空则回落到模块目录内的 `build.bat`/`build.sh` |
| `build.source` | string | cpp 必填 | 无 | C/C++ 主源文件相对路径，如 `"src/main.cpp"` |
| `build.script` | string | 否 | 无 | 自定义构建脚本（如 `"build.bat"`）；**配置后优先于内置编译器** |

**默认内容示例（Go 热编译）：**

```hjson
# 网关按 priority 升序启动 netmods/ 下的模块; 数字越小越先启动。
# command 指向编译产物可执行文件（相对本模块文件夹解析）。
{
  id: netwatch
  name: "带宽哨兵"
  version: "1.0.0"
  priority: 20
  enabled: true
  command: "netwatch.exe"
  args: []
  build: { type: "go" }
}
```

**重要说明：**

- `command` 缺失时日志为 `[NetGateway] 核心网络模块缺少 command，已跳过: <id>`，模块不会启动。
- `netmodule.hjson` 解析失败（非法 JSON/HJSON）时日志为 `Invalid netmodule.hjson in <文件夹名>`，该模块被跳过，不影响其他模块。
- 元数据文件（`netmodule.hjson`/`config.hjson`）的变更由文件指纹（修改时间+长度）检测，保存即触发热重启，见 [第 10 章](#10-热编译与热重载)。

### 4.2 netgateway.hjson 网关配置

**用途：** 网关总开关与三种传输形态的配置。位于 `config/yzf/config/netgateway.hjson`，首次启动不存在时由网关自动写入下述默认内容。

**默认内容（网关自动生成的原文）：**

```hjson
# YZF 外部网络模块网关配置。
# 让任意语言（Go/C++/Rust/Python...）编写的外部模块参与游戏收发包。
# enabled: 网关总开关。
# http: HTTP REST API（无状态互动：广播/命令/踢人/过滤器/状态）。
# tcp: NDJSON 实时双向通道（订阅收发包/聊天/进出事件并回传动作）。
# processes: 内嵌外部进程，网关直接启动这些程序并通过 stdin/stdout NDJSON 通信。
#   示例: { name: "go-filter", command: "./netmods/go-filter.exe", args: [], enabled: true }
# netmods: 核心网络模块相关开关。
#   dir: 核心网络模块目录（默认 netmods）；autoRestart: 崩溃后是否自动重启。
#   hotReload: 是否自动监听目录变化并热加载（重新编译/新增/删除模块后自动生效，无需重启服务端）。
# splitPolicy: 大包拆分策略。
#   mode: off=关闭 internal=网关内部定时分片 external=委托外部模块拆分。
#   threshold: 超过该字符长度的消息包会被拆分。
#   chunkSize: 每个分片最大字符数。
#   intervalMs: internal 模式下相邻分片的发送间隔（毫秒），用于平滑突发。
#   chunksPerTick: internal 模式每个节拍最多发出的分片数。
# token: 网关独立令牌；留空则使用 external-access.hjson 的令牌。
# observe: 事件观察开关（推送给订阅方；过滤器拦截不受此影响）。
enabled: true
http: { enabled: true, address: "localhost", port: 7100 }
tcp: { enabled: true, address: "localhost", port: 7101 }
processes: []
netmods: { dir: "netmods", autoRestart: true, hotReload: true }
splitPolicy: { mode: "internal", threshold: 200, chunkSize: 100, intervalMs: 60, chunksPerTick: 4 }
token: ""
observe: { sendPackets: false, receivePackets: true, chat: true, joins: true }
```

**字段说明：**

- `enabled` (bool, 默认 true)：网关总开关。`false` 时整个网关（HTTP/TCP/内嵌进程/核心模块）都不启动，日志 `外部网络模块网关已禁用`。配置文件解析失败时也会被强制置为 `false`（安全兜底）。
- `http.enabled` (bool, 默认 true)：HTTP REST API 开关。
- `http.address` (string, 默认 `"localhost"`)：绑定地址。绑定公网地址前必须通过 `external-access.hjson` 的 `allowInsecurePublicSocket` 检查，否则日志 `拒绝公网绑定 HTTP 端口` 并跳过监听。
- `http.port` (int, 默认 7100)：HTTP 端口。
- `tcp.enabled` (bool, 默认 true)：TCP NDJSON 通道开关。
- `tcp.address` (string, 默认 `"localhost"`)：绑定地址，公网约束同上。
- `tcp.port` (int, 默认 7101)：TCP 端口。
- `token` (string, 默认 `""`)：网关独立令牌。**留空时回退**使用 `external-access.hjson` 的 Bearer 令牌；两者都空则不鉴权（任何客户端可直连，仅限本机部署可接受）。
- `processes` (array, 默认 `[]`)：额外的内嵌外部进程定义（非 netmods 机制，字段为 `name`/`command`/`args`/`enabled`）。与核心模块的区别：不读 `netmodule.hjson`、没有 priority/热编译/自动重启，工作目录是 yzf 根目录而非模块文件夹。
- `netmods.dir` (string, 默认 `"netmods"`)：核心网络模块目录名（相对 yzf 根目录）。
- `netmods.autoRestart` (bool, 默认 true)：模块进程意外退出后，网关每 10 秒巡检一次并自动重启。
- `netmods.hotReload` (bool, 默认 true)：是否启动文件监听器做热编译/热重启，见 [第 10 章](#10-热编译与热重载)。
- `splitPolicy.mode` (string, 默认 `"internal"`)：`off`=关闭拆分；`internal`=网关内部定时分片；`external`=取消原包并向订阅方广播 `SplitRequest`，等待模块回传 `split.send`。
- `splitPolicy.threshold` (int, 默认 200)：消息**字符数**超过该值触发拆分。解析时强制 `max(16, 值)`。
- `splitPolicy.chunkSize` (int, 默认 100)：每片最大字符数。强制 `max(16, 值)`。
- `splitPolicy.intervalMs` (int, 默认 60)：internal 模式相邻分片间隔（毫秒）。强制 `max(5, 值)`。
- `splitPolicy.chunksPerTick` (int, 默认 4)：internal 模式每个节拍最多发出的分片数。强制 `max(1, 值)`。
- `observe.sendPackets` (bool, 默认 **false**)：是否产生 `SendPacketEvent` 事件。默认关闭——发送方向事件量大，按需打开。
- `observe.receivePackets` (bool, 默认 true)：是否产生 `ReceivePacketEvent`。
- `observe.chat` (bool, 默认 true)：是否产生 `PlayerChatEvent`。
- `observe.joins` (bool, 默认 true)：是否产生 `PlayerJoin`/`PlayerLeave`。

**重要说明：**

- observe 开关只控制**事件是否产生**；`filter`/`rateLimit` 的拦截行为不受 observe 影响（拦截发生在发送/接收路径上，始终生效）。
- `splitPolicy` 是**初始值**：运行中任何客户端发送 `splitPolicy` 动作都会覆盖当前模式与参数（不写回配置文件）。重启服务端后恢复为配置文件值。
- 配置解析出错的兜底行为是**整个网关禁用**（`Invalid netgateway.hjson; gateway disabled`），而不是带错误配置运行。改完配置务必用 `yzf net status` 验证。

### 4.3 模块自身配置文件（config.hjson）

模块自己的配置文件由**模块进程自己读取**，网关不解析、不下发。约定放在模块文件夹内（网关以模块文件夹为工作目录启动进程），模块重启后生效。

官方 packet-splitter 的 `config.hjson` 默认内容：

```hjson
# packet-splitter 运行时配置
# 修改后重启模块生效 (yzf net reload 或服务端重启)。

# --- 常规拆包参数 ---
# 消息超过该字符数视为大包, 触发拆分
splitThreshold: 200
# 每个分片最大字符数 (UTF-8 边界安全切分)
splitChunkSize: 100
# 相邻分片发送间隔 (毫秒), 用于平滑突发
splitIntervalMs: 60

# --- 整形模式 (带宽压力自适应) ---
# 上行带宽超过该值 (bytes/s) 进入整形模式
bandwidthHighBps: 150000
# 上行带宽回落到该值以下退出整形模式
bandwidthLowBps: 80000
# 整形模式下的收紧参数
shapedThreshold: 120
shapedChunkSize: 80
shapedIntervalMs: 100

# --- 突发抑制 (整形模式下对大流量包类型限速, 单位: 包/秒) ---
burstControl: 1
# BlockSnapshotCallPacket: 方块快照, 典型的大突发源
burstLimitSnapshot: 25
# SyncCallPacket: 实体同步
burstLimitSync: 30

# --- 其他 ---
# 每 N 秒向 stderr 打印一次模块状态
statsLogEvery: 30
```

逐字段说明见 [第 13 章 packet-splitter 配置小节](#132-confighjson-逐字段说明)。

**重要说明：**

- `config.hjson`/`config.json` 属于热重载的**元数据指纹**范围：保存后文件监听器会自动重启该模块使配置生效（无需手动 `yzf net restart`）。
- 自己写模块时配置文件格式完全自定（packet-splitter 用最简单的键值 + `#` 注释，自行实现了解析）；网关唯一关心的是这个文件的变化会触发模块重启。

### 4.4 配置修改后如何生效

| 改了什么 | 生效方式 |
| --- | --- |
| `netgateway.hjson` | `yzf net reload`（网关整体重启）或服务端重启 |
| `netmodule.hjson` | 保存即自动热重启该模块（hotReload 开启时）；否则 `yzf net restart <id>` |
| 模块 `config.hjson` | 保存即自动重启该模块；否则 `yzf net restart <id>` |
| 模块源码（.go/.cpp/…） | 保存即自动热编译+重启；否则 `yzf net restart <id>`（restart 也会强制重新编译） |
| 替换二进制 | 保存/覆盖即自动热重启 |

---

## 5. 核心概念：网关管线与整形机制

### 5.1 发送路径处理顺序

每一个**发送方向**的游戏包都会经过 `SendPacketEvent` 钩子，网关按以下**固定顺序**处理（源码 `installEventHandlers`）：

1. **计数**：`packetCounters["S:<包类名>"]++`（供 `NetStatsEvent.topPackets` 采样）。
2. **丢弃过滤**：若 `dropFilters` 含 `S:<包类名>` → 取消发送，结束。
3. **令牌桶限速**：若该包类型有 `rateLimit` 规则且桶内无令牌 → 取消发送，`rateLimited` 计数 +1，结束。
4. **大包拆分**：若 `splitPolicy` 非 off 且包是可拆分文本包且消息长度 ≥ threshold → 取消原包，走拆分流程（见 5.3），结束。
5. **事件推送**：若 `observe.sendPackets=true` → 入队 `SendPacketEvent` 事件推给订阅方。

接收路径（`ReceivePacketEvent`）只有 1（计数 `R:<包类名>`）、2（丢弃过滤）与事件推送三步——**没有限速与拆分**。

### 5.2 令牌桶（RateBucket）

- 每个被限速的键（`S:xxx` 或 `R:xxx`）一个桶：每秒补充 `perSecond` 个令牌，容量 `capacity = max(perSecond, burst)`，启动时桶满。
- 发包消耗 1 令牌；无令牌即取消该包。**判定在发送路径本地完成**，不跨进程往返，开销极小。
- `perSecond <= 0` 的动作会**移除**规则（恢复不限速）。
- 桶是"丢弃型"限速：超限的包直接被取消，不会排队延迟发送。对快照包而言表现为降低同步频率，客户端由游戏自身的位置纠正机制兜底。

### 5.3 大包拆分两种模式

可拆分包类型（`extractMessage`/`splitKind` 支持的包）：

| 包类型 | kind |
| --- | --- |
| `SendMessageCallPacket` | `sendMessage` |
| `SendChatMessageCallPacket` | `sendMessage` |
| `InfoMessageCallPacket` | `infoMessage` |
| `AnnounceCallPacket` | `announce` |

**internal 模式（网关自己拆）：**

1. 原包取消，按 `chunkSize` 字符切成 N 片入队 `pendingChunks`。
2. 定时泵每 `intervalMs` 毫秒最多发出 `chunksPerTick` 片。
3. 每片按 kind 调 `Call.sendMessage` / `Call.infoMessage` / `Call.announce`。

**external 模式（委托模块拆）：**

1. 原包取消，网关向订阅方广播 `SplitRequest` 事件（含完整原文 `message`）。
2. 模块自行切分（可以做 UTF-8 边界安全、更智能的节奏），逐片回传 `split.send` 动作。
3. 网关收到 `split.send` 后投递到游戏主线程按 kind 发送。

> 注意：internal 模式按 Java `char`（UTF-16 单元）切分；packet-splitter 的 external 实现按 **UTF-8 字节边界**回退切分，不会把多字节字符切成乱码。中文环境建议使用 external 模式（即保留 packet-splitter）。

### 5.4 NetStatsEvent 采样机制

- 网关每 1 秒调用 `YZFNetworkMetrics.sampleNow()`：把过去一段时间的上下行字节累计量折算成 B/s。
- 同一窗口内还结算**上传方向单包尺寸统计**：`recordUpload(bytes)` 每次发包累加包数与字节数并更新最大/最小值，`sampleNow()` 时把窗口结果转入 `lastUploadPacket*` 并清零（`YZFNetworkMetrics.lastUploadPacketMax()/Min()/Count()/Avg()`，平均值为字节数÷包数的整数商，包数为 0 时返回 0）。
- 随后把 `uploadBps / downloadBps / tps / players / pendingChunks / rateLimited / splitPackets / packetMax / packetMin / packetAvg / packetCount / topPackets` 组装成 `NetStatsEvent` 入队推送。
- `topPackets` 是"上一次采样以来的分包计数"，采样即清零（键形如 `S:BlockSnapshotCallPacket`、`R:ConnectPacket`）。

### 5.5 事件分发与背压

- 游戏线程只负责把事件行入队（`dispatchQueue`，容量 16384 行）；独立派发线程逐个取出，按订阅关系写给每个客户端。
- 队列满时新事件被丢弃，`droppedEvents` 计数 +1——慢客户端不会拖垮游戏线程。
- 核心网络模块（netmods）注册时 `subscribedAll=true`：**默认收到所有已产生的事件**，无需 `subscribe`（`subscribe` 仍可用于语义自文档化）。

### 5.6 小结与交叉引用

本章机制对应的配置在 [4.2](#42-netgatewayhjson-网关配置)，动作签名在 [第 6 章](#6-ndjson-协议完全参考模块--网关动作)，事件字段在 [第 7 章](#7-事件参考网关--模块推送)，运行状态查看在 [第 18 章](#18-可观测性status-字段与统计计数器)。

---

## 6. NDJSON 协议完全参考（模块 → 网关动作）

`🟢 稳定`

通用格式：每行一个 JSON 对象。

```json
{"type": "<动作名>", "replyId": "<可选，原样回传>", "fields": { ... }}
```

- `fields` 可省略——省略时网关把整行对象当作 fields（顶层键即字段）。
- 每个动作都会收到一行应答：

```json
{"type":"reply","action":"<动作名>","replyId":"<原样回传>", ...结果字段}
```

- 应答里 `ok:false` 表示失败，`error` 为原因。未知动作返回 `"error":"unknown action '<type>'"`。
- 动作名大小写不敏感（网关统一转小写匹配）；`rateLimit` 与 `rate_limit`、`splitPolicy` 与 `split_policy` 等价。

下面逐条列出网关注册的全部动作。

---

#### `hello` → `{"ok":true}`

宣告/改名。TCP 客户端用它完成认证（见 [第 9 章](#9-tcp-ndjson-通道参考)）；内嵌核心模块启动时网关已主动下发 hello 确认，模块可不再发送。

**参数（fields）：**

- `clientId` (string): 期望的客户端标识。非空且与当前标识不同时，网关在注册表中改名。

**示例：**

```json
{"type":"hello","clientId":"netwatch","coreModule":true}
```

**注意：** `coreModule` 字段对 TCP 客户端有效（记录在客户端对象上）；对 netmods 内嵌进程，网关启动时已强制 `authenticated=true, subscribedAll=true`，与该字段无关。

---

#### `subscribe` → `{"ok":true,"subscribedAll":bool}`

订阅事件。

**参数：**

- `event` (string): 事件名（如 `NetStatsEvent`），或特殊值 `all`（订阅全部，等价 `subscribedAll=true`）。空字符串不做任何事。

**示例：**

```json
{"type":"subscribe","fields":{"event":"NetStatsEvent"}}
{"type":"subscribe","fields":{"event":"all"}}
```

**注意：** 内嵌核心模块默认 `subscribedAll=true`，已经能收到全部事件；`subscribe` 的作用是显式声明意图（netwatch 与 packet-splitter 都保留了订阅语句）。事件是否真正产生还受 `observe.*` 开关约束。

---

#### `unsubscribe` → `{"ok":true}`

取消订阅。

**参数：**

- `event` (string): 事件名；`all` 清空全部订阅（`subscribedAll=false` 且清空事件集合）。

---

#### `broadcast` / `say` → `{"ok":true}`

全服广播一条聊天消息。

**参数：**

- `message` (string): 消息内容。空白返回 `{"ok":false,"error":"message is empty"}`。

**行为：** 投递到游戏主线程执行 `Call.sendMessage(message)`。

**示例：**

```json
{"type":"broadcast","fields":{"message":"服务器将在 5 分钟后重启"}}
```

**注意：** 若当前 `splitPolicy` 非 off 且消息长度超过阈值，这条广播本身会再次进入拆分管线（广播走 `Call.sendMessage` → 触发 `SendPacketEvent`）。超长公告正是拆分机制的目标场景。

---

#### `command` → `{"ok":true}`

以控制台身份执行一条服务端命令。

**参数：**

- `line` (string): 完整命令行（如 `"say hello"`）。`command` 键是 `line` 的别名。空白返回 `error: "command is empty"`。

**行为：** 投递到游戏主线程执行 `serverControl.handleCommandString(line)`，与在控制台键入完全等价。

**示例：**

```json
{"type":"command","fields":{"line":"host"}}
```

**注意 / 陷阱：** 这是**最高权限**动作（可 `stop` 关服、可改游戏规则）。令牌泄露等同交出服务器控制台。公网部署必须配置 token 并遵守 `外部访问安全规范.md`。

---

#### `kick` → `{"ok":true,"queued":true}`

按名字或 UUID 踢出玩家。

**参数：**

- `player` (string): 玩家名（不区分大小写）或 UUID（精确匹配）。空白返回 `error: "player is empty"`。
- `reason` (string): 踢出理由，缺省 `"kicked by external network module"`。

**行为：** 投递到主线程遍历在线玩家匹配后 `player.kick(reason)`。`queued:true` 表示已入队，**不代表已踢到**（玩家可能已离线）。

---

#### `filter` → `{"ok":true,"filter":"S:xxx|R:xxx","action":"<action>"}`

添加/移除"按包类型丢弃"过滤器。

**参数：**

- `event` (string): 方向。以 `send` 开头为发送方向（`S:`），否则接收方向（`R:`）。缺省 `receive`。
- `packet` (string): 包类简单名（如 `BlockSnapshotCallPacket`）。空白返回 `error: "packet is empty"`。
- `action` (string): `drop` 或 `cancel` 添加丢弃规则；**其他任何值**（如 `allow`）移除规则。缺省 `drop`。

**示例：**

```json
{"type":"filter","fields":{"event":"send","packet":"SomeNoisyPacket","action":"drop"}}
{"type":"filter","fields":{"event":"send","packet":"SomeNoisyPacket","action":"allow"}}
```

**注意：** 过滤器在重启网关后清空（不持久化）。过滤器拦截不受 `observe` 开关影响。

---

#### `rateLimit` / `rate_limit` → `{"ok":true,"key":"S:xxx","perSecond":n,"burst":n}`

为某方向某包类型设置令牌桶限速（机制见 [5.2](#52-令牌桶ratebucket)）。

**参数：**

- `event` (string): `send`（缺省）或 `receive`。
- `packet` (string): 包类简单名。空白返回 `error: "packet is empty"`。
- `perSecond` (string/int): 每秒令牌数。**`<= 0` 表示移除该规则**。字段按字符串解析，非法值回落 0（即移除）。
- `burst` (string/int): 桶容量，缺省等于 `perSecond`；实际容量取 `max(perSecond, burst)`。

**示例：**

```json
{"type":"rateLimit","fields":{"event":"send","packet":"BlockSnapshotCallPacket","perSecond":25,"burst":30}}
{"type":"rateLimit","fields":{"event":"send","packet":"BlockSnapshotCallPacket","perSecond":0}}
```

**注意 / 陷阱：**

- `perSecond` 与 `burst` 在 fields 里是**字符串**（网关 `parseInt(fields.getString(...))`），写数字也能工作（JSON 数字 getString 得到字面量）。
- 移除规则请显式发 `perSecond: 0`；网关重启后所有规则清空。
- netwatch 解除限速用的就是 `perSecond: 0`；packet-splitter 进入整形时下发规则后**不会**主动移除（见 [16 章陷阱](#16-陷阱与注意事项)）。

---

#### `splitPolicy` / `split_policy` → `{"ok":true,"mode":"...","threshold":n,"chunkSize":n,"intervalMs":n}`

设置大包拆分策略（覆盖运行时值，不写配置文件）。

**参数：**

- `mode` (string): `off` / `internal` / `external`。空或其他值保持现状。
- `threshold` (int): 触发拆分的消息字符数；`>0` 时生效，强制 `max(16,值)`。
- `chunkSize` (int): 每片最大字符数；`>0` 时生效，强制 `max(16,值)`。
- `intervalMs` (int): internal 模式分片间隔；`>0` 时生效，强制 `max(5,值)`。

**示例（packet-splitter 接管拆分）：**

```json
{"type":"splitPolicy","fields":{"mode":"external","threshold":"200","chunkSize":"100","intervalMs":"60"}}
```

**注意：** external 模式下 `intervalMs` 只影响网关侧语义（分片节奏由模块自己控制）。应答回显最终生效值，可用于确认钳制结果。

---

#### `split.send` → `{"ok":true}`

external 拆分模式下，模块回传一个待发分片。

**参数：**

- `kind` (string): `sendMessage`（缺省）/ `infoMessage` / `announce`。
- `text` (string): 分片文本；`message` 键是 `text` 的别名。空白返回 `error: "text is empty"`。

**行为：** 投递到主线程按 kind 调 `Call.sendMessage/infoMessage/announce`。

**示例：**

```json
{"type":"split.send","fields":{"kind":"sendMessage","text":"第一段……"}}
```

**注意：** 分片发出顺序 = 网关收到的顺序；节奏由模块控制（packet-splitter 用内部队列 + 5ms 轮询线程按 `intervalMs*i` 定时发出）。

---

#### `status` → `{"ok":true, ...状态字段}`

返回网关与服务端实时状态，字段清单见 [第 18 章](#18-可观测性status-字段与统计计数器)。

---

#### `players` → `{"ok":true,"players":[...]}`

返回在线玩家数组，每项：

```json
{"name":"...","uuid":"...","address":"...","team":"sharded","admin":false}
```

---

#### `ping` → `{"ok":true,"time":1699999999999}`

连通性探测，`time` 为服务端当前毫秒时间戳。

---

## 7. 事件参考（网关 → 模块推送）

`🟢 稳定`

网关推送的事件统一为一行：

```json
{"type":"event","event":"<事件名>", <字段...直接平铺>}
```

注意字段是**平铺在顶层**的（与 `fields` 包裹的动作方向相反）。

| 事件名 | 触发时机 | 字段 | 产生开关 |
| --- | --- | --- | --- |
| `NetStatsEvent` | 每秒一次（网关运行中） | `uploadBps` `downloadBps` `tps` `players` `pendingChunks` `rateLimited` `topPackets` | 始终产生 |
| `SplitRequest` | external 拆分模式下消息超长时 | `packet` `kind` `length` `chunkSize` `message` | `splitPolicy.mode=external` |
| `SendPacketEvent` | 每个通过管线的发送包 | `packet` `connection` `except` | `observe.sendPackets`（默认 false） |
| `ReceivePacketEvent` | 每个收到的客户端包 | `packet` `connection` | `observe.receivePackets`（默认 true） |
| `PlayerChatEvent` | 玩家发言 | `player` `uuid` `message` | `observe.chat`（默认 true） |
| `PlayerJoin` | 玩家进入 | `player` `uuid` | `observe.joins`（默认 true） |
| `PlayerLeave` | 玩家离开 | `player` `uuid` | `observe.joins`（默认 true） |

---

#### `NetStatsEvent`

**字段：**

```json
{
  "uploadBps": 182340.0,
  "downloadBps": 9120.0,
  "tps": 60.0,
  "players": 23,
  "pendingChunks": 0,
  "rateLimited": 17,
  "splitPackets": 12,
  "packetMax": 48213,
  "packetMin": 12,
  "packetAvg": 305,
  "packetCount": 596,
  "topPackets": { "S:BlockSnapshotCallPacket": 4211, "S:SyncCallPacket": 1902 }
}
```

- `uploadBps`/`downloadBps`：上一个采样周期的折算带宽（bytes/s），来自 `YZFNetworkMetrics`。
- `tps`：`Vars.actualServerTps` 实测 TPS。
- `splitPackets`：网关累计已拆包数。
- `packetMax`/`packetMin`/`packetAvg`/`packetCount`：**上一个采样窗口**内上传方向的单包尺寸统计（最大/最小/平均字节数、包数）；平均值为整数商，窗口内无包时为 0。
- `topPackets`：上一周期各方向分包计数，**采样即清零**；无流量时该字段缺省。

**示例处理（Go，netwatch 真实写法）：**

```go
var st struct {
    UploadBps   float64 `json:"uploadBps"`
    DownloadBps float64 `json:"downloadBps"`
    TPS         float64 `json:"tps"`
    Players     float64 `json:"players"`
}
// env 是解析后的信封 {Type, Event, Fields}
if env.Event == "NetStatsEvent" {
    json.Unmarshal(env.Fields, &st)
    // st.UploadBps 即当前上行带宽
}
```

> 说明：信封结构（`type`/`event`/`fields`）是 netwatch 自己定义的解析辅助——网关推送时字段平铺在顶层，netwatch 把 `fields` 定义为 `json.RawMessage` 同样能接住平铺字段中未命名的部分。C++ 的 packet-splitter 则直接在整行文本里按键提取 `uploadBps`。两种解析风格都能工作。

---

#### `SplitRequest`

external 拆分模式下，网关取消了超长原包后广播：

```json
{
  "type": "event",
  "event": "SplitRequest",
  "packet": "SendMessageCallPacket",
  "kind": "sendMessage",
  "length": 812,
  "chunkSize": 100,
  "message": "<完整原文，已 JSON 转义>"
}
```

- `length`：原消息字符数；`chunkSize`：网关当前配置的分片建议值（模块可无视，用自己的参数）。
- 收到后模块应切分并逐条回传 `split.send`。**若没有任何客户端处理 SplitRequest，这条长消息就丢失了**——external 模式下务必保证拆分模块在运行（packet-splitter 崩溃时网关 10 秒内自动重启它）。

---

#### `ReceivePacketEvent` / `SendPacketEvent`

```json
{"type":"event","event":"ReceivePacketEvent","packet":"ConnectPacket","connection":"1.2.3.4:56789"}
{"type":"event","event":"SendPacketEvent","packet":"WorldStream","connection":"*","except":""}
```

- `packet` 是包类简单名；`connection` 是对端地址（发送广播时为 `*`，接收未知时为 `?`）；`except` 是发送时排除的连接（可为空串）。

---

#### `PlayerChatEvent` / `PlayerJoin` / `PlayerLeave`

```json
{"type":"event","event":"PlayerChatEvent","player":"Steve","uuid":"...","message":"hello"}
{"type":"event","event":"PlayerJoin","player":"Steve","uuid":"..."}
{"type":"event","event":"PlayerLeave","player":"Steve","uuid":"..."}
```

---

#### 网关下发的控制消息

除事件外，网关还会向模块下发两类消息：

1. **hello 确认**（内嵌模块启动时收到一次）：

```json
{"type":"hello","gateway":"MindustryYZF","version":"<框架版本>","ok":true,"clientId":"netmod:<id>","moduleId":"<id>","coreModule":true}
```

2. **shutdown**（网关关闭/模块被热停止时收到）：

```json
{"type":"shutdown"}
```

模块应在收到 `shutdown` 后尽快退出；网关等待 2 秒后强制销毁进程。stdin 被关闭（EOF）也应视为退出信号。

---

## 8. HTTP REST API 参考

`🟢 稳定`

监听 `http.address:http.port`（默认 `localhost:7100`）。所有响应带 CORS 头（`Access-Control-Allow-Origin: *`），`OPTIONS` 直接返回 204。

**认证：** 每个请求检查 `Authorization` 头。令牌为 `netgateway.hjson` 的 `token`，留空则回退 `external-access.hjson` 的 Bearer 令牌；两者皆空则不鉴权。比较采用常量时间算法（`MessageDigest.isEqual`）。

**请求体约定：** POST 端点的 body 是一个扁平 JSON 对象，其**全部顶层键**都会被当作动作 fields（值统一转字符串）。

| 端点 | 方法 | 说明 |
| --- | --- | --- |
| `/yzfnet/status`（或 `/yzfnet`） | GET | 网关+服务端状态 |
| `/yzfnet/players` | GET | 在线玩家列表 |
| `/yzfnet/filters` | GET | 当前丢弃过滤器列表 |
| `/yzfnet/broadcast` | POST | 等价 `broadcast` 动作，body: `{"message":"..."}` |
| `/yzfnet/say` | POST | 同 broadcast |
| `/yzfnet/command` | POST | 等价 `command` 动作，body: `{"line":"..."}` |
| `/yzfnet/kick` | POST | 等价 `kick` 动作，body: `{"player":"...","reason":"..."}` |
| `/yzfnet/filter` | POST | 等价 `filter` 动作 |
| `/yzfnet/netmods` | GET/POST | 核心模块列表（含运行状态） |
| `/yzfnet/netmods/stop` | POST | 热停止模块，body: `{"id":"..."}` |
| `/yzfnet/netmods/restart` | POST | 热重启模块，body: `{"id":"..."|"all"}`（id 缺省=all） |
| `/yzfnet/netmods/rescan` | POST | 重扫目录并热添加新模块 |

未匹配路径返回 404 `{"ok":false,"error":"unknown endpoint"}`；服务器异常返回 500。

**示例（bash）：**

```bash
# 状态
curl -s http://localhost:7100/yzfnet/status -H "Authorization: Bearer <token>"

# 广播
curl -s -X POST http://localhost:7100/yzfnet/broadcast \
  -H "Content-Type: application/json" \
  -d '{"message":"维护公告：10 分钟后重启"}'

# 停止模块（Windows 下用于解锁 exe，见 10.6）
curl -s -X POST http://localhost:7100/yzfnet/netmods/stop \
  -H "Content-Type: application/json" -d '{"id":"netwatch"}'
```

`/yzfnet/netmods` 应答示例（换行被替换为 `|`）：

```json
{"ok":true,"info":"核心网络模块目录: C:\\server\\config\\yzf\\netmods|  netwatch v1.0.0 priority=20 [运行中] command=netwatch.exe|..."}
```

**注意：** HTTP 线程池固定 4 线程、accept 队列 64——它面向运维脚本而非高频调用；高频实时需求请用 TCP 或内嵌模块。

---

## 9. TCP NDJSON 通道参考

`🟢 稳定`

监听 `tcp.address:tcp.port`（默认 `localhost:7101`）。握手流程：

1. 连接建立后，网关立即下发提示行：

```json
{"type":"hello","gateway":"MindustryYZF","version":"...","message":"发送 {\"type\":\"hello\",\"token\":\"...\",\"clientId\":\"...\"} 完成认证"}
```

2. 客户端必须在 **15 秒内**回复 hello 行（认证阶段 socket 读超时 15000ms）：

```json
{"type":"hello","token":"<令牌>","clientId":"my-tool","coreModule":false}
```

3. 认证成功：`{"type":"hello","ok":true,"clientId":"my-tool"}`；失败：`{"type":"hello","ok":false,"error":"authentication required"}` 并断开。
4. 之后双向 NDJSON：客户端发动作（[第 6 章](#6-ndjson-协议完全参考模块--网关动作)），网关推事件（[第 7 章](#7-事件参考网关--模块推送)）。

**注意：**

- TCP 客户端默认**没有任何订阅**，需要 `subscribe`（与内嵌核心模块不同）。
- 令牌为空时跳过 token 校验（`clientId` 仍可用于标识）。
- 超过 1 MiB 的行被静默丢弃。

---

## 10. 热编译与热重载

`🟡 实验性`（依赖本机工具链与文件系统事件，行为已稳定但环境差异多）

### 10.1 触发源：文件监听器

`YZFNetModHotReloadWatcher` 用 Java NIO WatchService 递归监听 `netmods/` 全树（新建子目录自动注册），事件**去抖 1 秒**后合并成一次应用（`onNetModFilesChanged`），在监听器工作线程执行——编译/重启**不占用游戏线程**。

判定为"相关变更"的文件：

| 类别 | 文件 | 后果 |
| --- | --- | --- |
| 元数据 | `netmodule.hjson` / `netmodule.json` | 重启（可构建则先重编译） |
| 配置 | `config.hjson` / `config.json` | 重启（可构建则先重编译） |
| 二进制 | `*.exe` / `*.bin` / `*.elf` / 无扩展名可执行文件 | 普通重启 |
| 源码/构建脚本 | `.c .cc .cpp .cxx .h .hpp .hxx .go .rs .zig .mod .sum .bat .sh .mk .cmake`、`makefile`、`go.mod`、`go.sum` | 热编译+重启 |

忽略的文件：`.tmp .log .bak .swp .obj .o .pdb .ilk .exp .lib .map .d .class .lock`，以及其他 `.hjson/.json/.md/.txt`（视为运行数据/文档）。

### 10.2 应用差异（diff 语义）

每次应用时网关重新 `scanNetModules()` 并与运行态比对：

| 情况 | 动作 |
| --- | --- |
| 新增模块文件夹 | 有 build 配置 → 编译后启动；否则直接启动 |
| 文件夹被删除 / `enabled:false` | 停止进程 |
| 源码指纹变化 | 停止 → **热编译** → 启动新二进制 |
| 元数据/配置指纹变化 | 重启（可构建模块先重编译） |
| 二进制指纹变化（外部替换） | 普通重启 |

指纹规则：

- 二进制指纹 = `最后修改时间:文件长度`。
- 源码指纹 = 全部构建相关文件（递归，跳过 `cache/` 与隐藏目录、跳过二进制与垃圾文件）的 `相对路径:修改时间:长度` 排序拼接。
- 元数据指纹 = 同上规则但只收 `netmodule.hjson/json` 与 `config.hjson/json`。

### 10.3 热编译策略（第一个匹配生效）

1. **`build.script` 已配置** → 在模块目录执行该脚本（`.bat/.cmd` 用 `cmd /c`，其余用 `bash`）。
2. **`build.type: "go"`** → `go build -o <command绝对路径> .`（有 `go.mod` 时）；否则编译目录下全部 `.go` 文件。
3. **`build.type: "cpp"|"c"|"cxx"`** → 自动定位 MSVC `vcvars64.bat`（先查 JVM 属性 `yzf.vcvars64` 缓存，再扫 `Microsoft Visual Studio/<年>/<版>/VC/Auxiliary/Build/vcvars64.bat`），执行：

   ```text
   call "<vcvars64.bat>" >nul 2>nul && cl.exe /nologo /std:c++17 /O2 /EHsc /W3 /utf-8 "<build.source>" /Fe:"<command文件名>"
   ```

4. **回落**：模块目录内存在 `build.bat` 或 `build.sh` → 执行它。
5. 都没有 → 日志 `没有可用的 build 配置或 build 脚本，跳过热编译`。

编译约束：

- 工作目录 = 模块文件夹；合并输出最多捕获 8000 字符；**超时 300 秒**强制终止。
- 编译成功但 `command` 指向的文件仍不存在 → 视为失败（`编译命令成功但未生成目标文件`）。
- **编译失败保留旧版本**：若旧二进制还在，用它重启模块并告警；否则模块保持停止并报错。
- 并发保护：同一模块同时只允许一次编译（`netModulesCompiling` 集合）；初始部署编译进行中时，文件监听器的重复编译请求会被跳过。

### 10.4 崩溃自愈

`netmods.autoRestart: true`（默认）时，网关每 **10 秒**巡检：已死亡的模块进程被清理并重新走"编译（如需）→ 启动"流程。日志 `核心网络模块已退出，尝试重启: <id>`。

### 10.5 首次部署

有 build 配置但没有二进制时，启动流程自动先编译：

```text
[I] [NetGateway] 核心网络模块 packet-splitter 缺少二进制，先热编译源码...
```

即"源码即部署"：只拷贝源码文件夹也能跑起来。

### 10.6 Windows exe 文件锁

运行中的 `.exe` 被 Windows 锁定，无法直接覆盖。官方 `build.bat`（netwatch 目录内）给出的标准流程：

1. `go build` 到**临时文件**（`netwatch.build.tmp.exe`）。
2. `curl -X POST http://localhost:7100/yzfnet/netmods/stop -d '{"id":"netwatch"}'` 通知网关停止模块 → 释放文件锁。
3. 等待 2 秒后 `move /Y` 临时文件覆盖 `netwatch.exe`。
4. 文件监听器检测到二进制变化 → 自动热重启模块。

> 配置了 `build` 段的模块**不需要**这套手动流程——网关热编译在停止进程之后、替换之前进行，锁问题由网关内部规避。手动 build.bat 适用于不配置 build 段、用自己的构建环境的场景。

### 10.7 小结与交叉引用

- 相关命令见 [第 11 章](#11-服务端命令参考yzf-net)；HTTP 管理端点见 [第 8 章](#8-http-rest-api-参考)。
- 编译工具链缺失的报错与排查见 [15 章](#15-错误处理与容错) 与 [19 章 FAQ](#19-faq)。

---

## 11. 服务端命令参考（yzf net）

`🟢 稳定`

控制台命令入口：`yzf net [子命令]`（帮助条目原文：`管理外部网络模块网关与核心网络模块（热添加/热替换/热移除）`）。子命令支持中文别名；未识别的子命令打印用法。

**网关生命周期：**

```text
yzf net status    # 网关与服务端状态全量输出（别名：状态）
yzf net start     # 启动网关（别名：启动）
yzf net stop      # 停止网关并销毁全部模块进程（别名：停止）
yzf net reload    # 重新读取 netgateway.hjson 并重启网关（别名：重载）
```

**模块管理：**

```text
yzf net mods                # 模块列表与运行状态（别名：模块、列表）
yzf net rescan              # 重扫目录，热添加新模块（别名：重扫、热添加）
yzf net restart <id|all>    # 热重启指定模块或全部；有 build 配置时强制重新编译（别名：热重启）
yzf net stopmod <id>        # 热停止指定模块（别名：热移除）
```

**示例：**

```text
yzf net restart packet-splitter
```

```text
[I] [NetGateway] 热编译核心网络模块: packet-splitter (type=cpp)
[I] [NetGateway] 模块 packet-splitter 热编译成功，耗时 2113 ms。
[I] [NetGateway] 核心网络模块已启动: packet-splitter v1.0.0 (priority 10)
[I] [MindustryYZF] 已热重启模块: packet-splitter
```

**status 输出示例：**

```text
启用: 是
运行中: 是
HTTP: localhost:7100
TCP: localhost:7101
核心网络模块: 2 个定义, 2 个运行中
核心模块热加载: 启用 (监听中)
内嵌进程: 0 个定义, 0 个运行中
在线外部客户端: 2
拆包模式: external 阈值=200 分片=100 间隔=60ms
限速规则: 2 条
观察开关: 发送=false 接收=true 聊天=true 进出=true
丢包过滤器: 0 条
已拆包: 12 已限速丢弃: 340
事件统计: 已投递=8123 队列满丢弃=0 队列当前=0
动作处理总数: 97
```

**注意：**

- `yzf net restart` 对可构建模块**总是先重新编译**——这是让源码修改生效的可靠手段（与文件监听互为冗余）。
- 参数解析是贪心的：`net` 之后的所有 token 合并后重新切分，因此引号包裹的参数不适用于含空格的 moduleId（moduleId 不建议含空格）。

---

## 12. 内置模块详解：netwatch（带宽哨兵，Go）

`🟢 稳定`

### 12.1 功能概述

源码：`core-network-modules/netwatch/main.go`（137 行，纯 Go 标准库）。

功能（源码注释原文归纳）：

1. 订阅 `NetStatsEvent`，实时跟踪服务端上行/下行带宽。
2. 当上行带宽连续超过阈值时向网关发送限速建议动作（`rateLimit`），对 `BlockSnapshotCallPacket` 临时限速，缓解带宽压力造成的卡顿/掉线。
3. 压力回落后自动解除限速。

### 12.2 参数（源码常量）

| 常量 | 值 | 含义 |
| --- | --- | --- |
| `bandwidthHighBps` | 150000 | 上行超过该值（bytes/s）进入压制模式 |
| `bandwidthLowBps` | 80000 | 回落至该值以下解除压制 |
| `snapshotLimit` | 25 | 压制模式下快照包限速（包/秒） |
| `snapshotBurst` | 30 | 令牌桶容量 |

源码注释说明这些常量"也可改为从 config.hjson 读取"——当前版本未读取配置文件，改参数需要改源码重新编译（热编译会自动完成）。

### 12.3 启动与握手

```go
sendLine(map[string]any{"type": "hello", "clientId": "netwatch", "coreModule": true})
sendAction("subscribe", map[string]any{"event": "NetStatsEvent"})
```

- 先发 hello 宣告自身，再订阅 `NetStatsEvent`（内嵌模块本就收全部事件，订阅是显式声明）。
- stdin 逐行读取，缓冲区 64KB 起、上限 8MB（`scanner.Buffer(make([]byte, 0, 64*1024), 8*1024*1024)`）。

### 12.4 压制状态机

```text
[正常] --UploadBps>=150000 持续 3 秒--> [压制中] --UploadBps<=80000--> [正常]
```

- **进入压制**：`uploadBps >= 150000` 且**连续 3 秒**不回落才生效（`highSince` 计时），避免瞬时抖动误判；触发后发送：

```json
{"type":"rateLimit","fields":{"event":"send","packet":"BlockSnapshotCallPacket","perSecond":25,"burst":30}}
```

- **退出压制**：`uploadBps <= 80000` 立即解除，发送 `perSecond: 0` 移除规则：

```json
{"type":"rateLimit","fields":{"event":"send","packet":"BlockSnapshotCallPacket","perSecond":0}}
```

- 压制中（`shaping=true`）不再重置 `highSince`，也不重复下发规则。

### 12.5 日志与退出

- 所有日志走 stderr（前缀 `[netwatch]`），网关将每行转发为 `[NetMod:netwatch] ...` 写入服务端日志。
- 每 30 秒打印一次状态：`状态: 上行=... B/s 下行=... B/s TPS=... 玩家=... 压制=true/false`。
- 收到 `shutdown` 立即退出；stdin 关闭（EOF）退出。退出后若 `autoRestart` 开启，网关 10 秒内会把它拉起来。

### 12.6 build.bat 手动构建流程

netwatch 目录附带 `build.bat`：`go build` 到临时文件 → POST `/yzfnet/netmods/stop` 解锁 exe → 替换正式文件 → 监听器自动热重启。详细解释见 [10.6](#106-windows-exe-文件锁)。前置：PATH 里有 `go` 与 `curl`。

---

## 13. 内置模块详解：packet-splitter（大包拆分器，C++）

`🟢 稳定`

### 13.1 功能概述

源码：`core-network-modules/packet-splitter/src/main.cpp`（523 行，仅 C++17 标准库）。

功能（源码注释原文归纳）：

1. 监测服务端实时上行带宽（`NetStatsEvent`，每秒一次）。
2. 当上行带宽超过阈值时进入"整形模式"，收紧拆包参数并对突发包限速，避免瞬间大包冲击带宽造成卡顿/掉线/不同步。
3. 收到 `SplitRequest` 事件（网关取消了超长消息包）后，把消息切成小包，按固定节奏逐个发回网关（`split.send`），平滑突发流量。
4. 所有参数可通过本目录 `config.hjson` 调整；模块崩溃会被网关自动重启。

### 13.2 config.hjson 逐字段说明

| 字段 | 类型 | 默认值 | 含义 |
| --- | --- | --- | --- |
| `splitThreshold` | int | 200 | 普通模式拆包阈值（字符数）——通过 `splitPolicy` 下发给网关 |
| `splitChunkSize` | int | 100 | 普通模式每片最大字符数 |
| `splitIntervalMs` | int | 60 | 普通模式相邻分片间隔（毫秒） |
| `bandwidthHighBps` | long | 150000 | 上行超过该值（bytes/s）进入整形模式 |
| `bandwidthLowBps` | long | 80000 | 回落至该值以下退出整形模式 |
| `shapedThreshold` | int | 120 | 整形模式拆包阈值 |
| `shapedChunkSize` | int | 80 | 整形模式每片字符数 |
| `shapedIntervalMs` | int | 100 | 整形模式分片间隔 |
| `burstControl` | int(0/1) | 1 | 整形模式下是否下发突发限速规则 |
| `burstLimitSnapshot` | int | 25 | `BlockSnapshotCallPacket` 限速（包/秒） |
| `burstLimitSync` | int | 30 | `SyncCallPacket` 限速（包/秒） |
| `statsLogEvery` | int | 30 | 每 N 秒打印一次状态 |

**注意：**

- 模块按顺序尝试读取工作目录下 `config.hjson` → `config.json`，都缺失则用内置默认值（与上表一致）。
- 解析器是极简实现：逐行去掉 `#` 及其后内容、给不带引号的键补引号、按键名定位数字。**值里不要包含 `#`**；不支持嵌套对象。
- `burstControl` 按整数读（`0` 关 / 非 0 开），不要写 `true/false`。

### 13.3 启动与接管拆分

握手（收到网关 `hello` 且 `"ok":true` 后）：

```json
{"type":"subscribe","fields":{"event":"SplitRequest"}}
{"type":"subscribe","fields":{"event":"NetStatsEvent"}}
{"type":"splitPolicy","fields":{"mode":"external","threshold":"200","chunkSize":"100","intervalMs":"60"}}
```

即以 **external 模式接管拆包决策**（参数取普通模式值）。此后所有超长文本包都会被网关取消并以 `SplitRequest` 交给本模块。

### 13.4 整形状态机

与 netwatch 不同，packet-splitter 的整形切换**没有 3 秒去抖**——`uploadBps` 越过水位立即切换：

- 进入整形：`splitPolicy` 下发收紧参数（120/80/100ms），日志 `带宽压力升高, 进入整形模式 (收紧拆包参数 + 突发限速)`。
- 首次进入整形且 `burstControl=1` 时，**一次性**下发两条限速规则（之后不再重发）：

```json
{"type":"rateLimit","fields":{"event":"send","packet":"BlockSnapshotCallPacket","perSecond":"25","burst":"25"}}
{"type":"rateLimit","fields":{"event":"send","packet":"SyncCallPacket","perSecond":"30","burst":"30"}}
```

- 退出整形：`splitPolicy` 恢复普通参数（200/100/60ms）。
- **注意：退出整形不会移除突发限速规则**（`g_burst_rules_sent` 只置位不清零，且没有发 `perSecond:0` 的逻辑）——限速规则伴随模块进程整个生命周期，直到模块重启或网关重启。这是否为期望行为见 [20 章待确认清单](#20-版本与待确认清单)。

### 13.5 拆分流水线

`SplitRequest` 到达后：

1. 提取 `message`（JSON 字符串反转义，支持 `\n \r \t \" \\ \/ \uXXXX`——基本平面字符按 UTF-8 编码输出）与 `kind`（缺省 `sendMessage`）。
2. 按当前模式取 `chunkSize` 与 `intervalMs`。
3. **UTF-8 边界安全切分**：按字节切到 chunkSize 后回退到非 `10xxxxxx` 续字节位置，避免把多字节字符切成乱码。
4. 每个分片生成任务 `send_at = now + intervalMs * i` 入队。
5. 独立的节奏线程（pacer）每 5ms 检查队首，到期即发送：

```json
{"type":"split.send","fields":{"kind":"sendMessage","text":"<分片，JSON 转义>"}}
```

另有：

- **60 秒滚动窗口单包统计**：每次收到 `NetStatsEvent` 时解析 `packetMax/packetMin/packetAvg/packetCount`（窗口字节数 = packetAvg × packetCount 反推），连同时间戳入队；每次入队后丢弃 60 秒前的样本。汇总函数 `packet_window_stats` 输出窗口内的最大包、最小包（忽略 0 值）、总包数与平均字节数，供状态日志使用。
- 同时从事件里同步两个网关侧累计量：`pendingChunks`（待发分片）直接记录；`splitPackets`（网关累计拆包数）若大于本地 `g_split_total` 则对齐到网关值——保证"已拆包"计数与网关一致（external 模式下网关也在计数）。
- 心跳线程：每 `statsLogEvery` 秒打印状态，格式为 `状态: 上行=... B/s 整形=是/否 已拆包=N 待发分片=M | 近60秒单包: 最大=... B 最小=... B 平均=... B 包数=...`。
- Windows 下 stdin/stdout 设置 64KB 全缓冲（`setvbuf`）。
- stdout 写入有互斥锁保护（事件处理与 pacer 线程共享）。

### 13.6 编译方式

`netmodule.hjson` 配置 `build: { type: "cpp", source: "src/main.cpp" }`，网关用 MSVC 编译：

```text
cl.exe /nologo /std:c++17 /O2 /EHsc /W3 /utf-8 "src\main.cpp" /Fe:"packet-splitter.exe"
```

无 MSVC 时报错 `未找到 MSVC vcvars64.bat...请安装 Visual Studio Build Tools，或在 netmodule.hjson 中配置 build.script 使用自己的编译器`。中间产物 `main.obj` 等被指纹与监听器忽略，不会触发误重载。

---

## 14. 权限、安全与审计

对外端口与令牌策略的完整要求见同目录 [`外部访问安全规范.md`](外部访问安全规范.md)。本节只列核心网络模块机制特有的安全事实。

### 14.1 令牌与认证

| 通道 | 认证方式 | 说明 |
| --- | --- | --- |
| 内嵌核心模块（stdin/stdout） | 免认证 | 网关 spawn 时直接标记已认证；进程只能由网关创建 |
| TCP | hello 行 token | 15 秒握手超时；常量时间比较 |
| HTTP | `Authorization: Bearer` | 常量时间比较；空令牌策略下完全放行 |

令牌来源优先级：`netgateway.hjson` 的 `token` → `external-access.hjson` 的 Bearer 令牌。模块进程可通过环境变量 `YZF_TOKEN` 拿到当前有效令牌（网关 spawn 时注入）。

### 14.2 公网绑定约束

HTTP/TCP 绑定非本机地址前，网关检查 `external-access.hjson` 策略（`allowsSocketBind`）；不允许时拒绝绑定并记录 `拒绝公网绑定 HTTP/TCP 端口 ...（external-access 需要 allowInsecurePublicSocket: true）`。默认配置绑定 `localhost`，无公网暴露。

### 14.3 高危动作清单

| 动作/端点 | 风险 | 建议 |
| --- | --- | --- |
| `command` / `/yzfnet/command` | 等价服务器控制台，可关服可改规则 | 令牌必填；外部工具尽量不用此动作 |
| `kick` | 可踢任意玩家 | 审计来源 clientId |
| `filter`/`rateLimit` | 可致特定包全部丢弃 | 变更前记录旧值，便于回滚（规则不持久化，重启网关即清空） |
| `broadcast` | 全服可见 | 内容需审核 |

### 14.4 模块进程注入的环境变量

| 变量 | 值 |
| --- | --- |
| `YZF_PROTOCOL` | `ndjson-stdio` |
| `YZF_GATEWAY` | `netgateway` |
| `YZF_MODULE_ID` | 模块 id |
| `YZF_TOKEN` | 当前有效令牌（**敏感**：模块进程可读） |

> 由此推论：任何被放进 `netmods/` 的可执行文件都能拿到令牌并完整读写服务器。只部署可信模块。

---

## 15. 错误处理与容错

### 15.1 框架侧容错点

| 场景 | 行为 |
| --- | --- |
| `netgateway.hjson` 解析失败 | 整个网关禁用，日志 `Invalid netgateway.hjson; gateway disabled` |
| `netmodule.hjson` 解析失败 | 跳过该模块，不影响其他模块 |
| 缺少 `command` | 跳过并告警 |
| 模块进程崩溃 | `autoRestart` 开启时每 10 秒巡检拉起 |
| 热编译失败 | 有旧二进制 → 旧版本继续运行（告警）；无 → 模块停止（报错） |
| 热编译超时 | 300 秒强制终止，视为失败 |
| 事件队列满（16384） | 丢弃新事件，`droppedEvents` +1，不阻塞游戏线程 |
| 模块发来超长行（>1MiB） | 静默丢弃 |
| 端口绑定被 external-access 拒绝 | 该传输形态不启动，网关其余部分正常 |
| HTTP/TCP 启动 IO 异常 | 记录 `YZFErrorLog.high`，网关其余部分正常 |
| 网关关闭 | 向每个模块发 `shutdown`，等 2 秒后强制销毁；移除全部事件钩子 |

### 15.2 模块侧推荐容错模式

- **逐行解析必须容忍坏行**：netwatch 对 `json.Unmarshal` 失败的行直接 `continue`；packet-splitter 对提取失败的字段直接返回。永远不要因为一行坏数据退出进程（退出=功能中断，虽然有自动重启）。
- **stdout 只输出 NDJSON**：日志一律走 stderr。stdout 混入非 JSON 行会导致网关 `Jval.read` 抛错（该行按解析失败处理并回 `ok:false` 应答，但污染协议流）。
- **处理 EOF 与 shutdown 两种退出路径**：stdin 关闭和显式 shutdown 都会发生（前者见于进程被强杀前的管道关闭）。
- **发送失败容忍**：netwatch 的 `sendLine` 对 marshal 失败静默返回——网关写端也有同样的"写失败即放弃该行"语义，不要在写失败时死循环重试。

### 15.3 常见报错与排查

| 报错/现象 | 原因 | 解决办法 |
| --- | --- | --- |
| `未找到 MSVC vcvars64.bat，无法热编译 C++ 模块` | 没装 VS Build Tools | 安装 Build Tools（含 C++ 桌面开发负载），或配 `build.script` |
| `C++ 模块 <id> 缺少 build.source` | cpp 类型没给主源文件 | 补 `build: { source: "src/main.cpp" }` |
| `模块 <id> 目录下没有 go.mod 或 .go 源文件` | go 类型但目录空 | 补源码或改 command 指向现成二进制 |
| `模块 <id> 编译超时(300s)，已强制终止` | 依赖下载慢/机器慢 | 预热 go module 缓存；或用 build.script 分离下载与编译 |
| `编译命令成功但未生成目标文件` | 构建脚本输出路径与 `command` 不一致 | 让脚本产物落在 `command` 指向的路径 |
| 模块 `[运行中]` 但收不到事件 | observe 开关关闭了对应事件 | 检查 `netgateway.hjson` 的 `observe.*` |
| external 模式下长消息消失 | 没有模块订阅/处理 SplitRequest | `yzf net mods` 确认 packet-splitter 在运行 |
| Windows 下 build.bat 替换 exe 失败 | 模块仍在运行，文件锁未释放 | 先 POST `/yzfnet/netmods/stop`，或改用 build 段热编译 |

---

## 16. 陷阱与注意事项

1. **internal 拆分按 UTF-16 字符切**：网关内部模式用 Java `String.substring`，对代理对（emoji 等）可能切成两半；中文 BMP 字符安全。packet-splitter 的 external 实现按 UTF-8 字节边界回退，更安全。
2. **packet-splitter 的突发限速不随整形退出而移除**：规则持续到模块/网关重启。带宽长期在低水位的服务器，首次触发整形后会一直带着 25/30 的快照/同步限速。需要撤销时手动发 `rateLimit perSecond:0`（TCP 客户端）或重启模块。
3. **netwatch 有 3 秒去抖，packet-splitter 没有**：两者对 `bandwidthHighBps` 的响应节奏不同，混看日志时不要误判。
4. **核心模块默认 subscribedAll**：netmods 进程会收到所有 observe 开启的事件（包括 ReceivePacketEvent 这类高频事件）。高频事件 + 慢解析会撑大派发队列，模块主循环必须足够快。
5. **动作字段是字符串解析**：`rateLimit` 的 `perSecond/burst` 走 `parseInt(fields.getString(...))`；传 `null` 或非法串会回落默认值（0），等价"移除规则"，注意语义。
6. **事件字段平铺、动作字段包在 fields**：两个方向的 JSON 结构不对称。写通用解析时别混。
7. **`filter` 的 action 语义**：只有 `drop`/`cancel` 是添加，**其他一切值都是移除**（包括拼错的 `drpo`）。
8. **splitPolicy 运行时覆盖不落盘**：模块接管后网关配置里仍是旧值；重启服务端后若模块没及时重新下发 splitPolicy，会短暂回到 internal/off。packet-splitter 在 hello 握手后立即下发，空窗极短。
9. **moduleId 不要含空格**：`yzf net restart <id>` 按空白切分参数。
10. **stdout 只能有 NDJSON**：任何调试输出（fmt.Println、std::cout）混入 stdout 都会污染协议，一律用 stderr。
11. **`yzf net reload` 会重启整个网关**：所有 TCP 客户端断开、所有模块进程重启、限速规则与过滤器清空。只想重启某个模块用 `yzf net restart <id>`。
12. **环境变量里有令牌**：模块进程可读 `YZF_TOKEN`；模块源码不要打印环境变量。

---

## 17. 完整模块模板

### 17.1 最小 Go 模块（观察 + 广播）

放置路径：`config/yzf/netmods/hello-net/main.go`（同目录放 `netmodule.hjson`，`build: { type: "go" }`、`command: "hello-net.exe"`）。

```go
// hello-net: 最小核心网络模块示例
// 订阅玩家进出事件，有人进入时全服欢迎。
package main

import (
    "bufio"
    "encoding/json"
    "fmt"
    "os"
)

type envelope struct {
    Type   string          `json:"type"`
    Event  string          `json:"event"`
    Player string          `json:"player"`
    Fields json.RawMessage `json:"fields,omitempty"`
}

func send(v map[string]any) {
    b, _ := json.Marshal(v)
    fmt.Println(string(b))
}

func main() {
    send(map[string]any{"type": "hello", "clientId": "hello-net", "coreModule": true})
    send(map[string]any{"type": "subscribe", "fields": map[string]any{"event": "PlayerJoin"}})

    scanner := bufio.NewScanner(os.Stdin)
    scanner.Buffer(make([]byte, 0, 64*1024), 8*1024*1024)
    for scanner.Scan() {
        var env envelope
        if err := json.Unmarshal(scanner.Bytes(), &env); err != nil {
            continue
        }
        switch env.Type {
        case "shutdown":
            return
        case "event":
            if env.Event == "PlayerJoin" && env.Player != "" {
                send(map[string]any{"type": "broadcast",
                    "fields": map[string]any{"message": "欢迎 " + env.Player + " 进入服务器！"}})
            }
        }
    }
}
```

### 17.2 netmodule.hjson 模板（三种构建形态）

```hjson
# 形态 A：现成二进制（无 build 段）
{
  id: my-tool
  name: "我的工具"
  version: "1.0.0"
  priority: 50
  enabled: true
  command: "my-tool.exe"
  args: []
}

# 形态 B：Go 热编译
{
  id: my-tool
  name: "我的工具"
  version: "1.0.0"
  priority: 50
  enabled: true
  command: "my-tool.exe"
  args: []
  build: { type: "go" }
}

# 形态 C：C++ 热编译（MSVC）
{
  id: my-tool
  name: "我的工具"
  version: "1.0.0"
  priority: 50
  enabled: true
  command: "my-tool.exe"
  args: []
  build: { type: "cpp", source: "src/main.cpp" }
}

# 形态 D：自定义构建脚本（任意工具链）
{
  id: my-tool
  name: "我的工具"
  version: "1.0.0"
  priority: 50
  enabled: true
  command: "dist/my-tool.exe"
  args: []
  build: { script: "build.bat" }
}
```

### 17.3 最小 C++ 模块骨架

```cpp
// min-netmod: 最小核心网络模块 (C++17, 纯标准库)
#include <cstdio>
#include <iostream>
#include <string>

int main(){
    // 握手: 宣告自身并订阅带宽事件
    std::puts(R"({"type":"hello","clientId":"min-netmod","coreModule":true})");
    std::puts(R"({"type":"subscribe","fields":{"event":"NetStatsEvent"}})");
    std::fflush(stdout);

    std::string line;
    while(std::getline(std::cin, line)){
        if(line.find(R"("type":"shutdown")") != std::string::npos) break;
        if(line.find("\"event\":\"NetStatsEvent\"") != std::string::npos){
            // 这里解析 uploadBps 等字段 (参考 packet-splitter 的 json_get_number)
        }
    }
    return 0;
}
```

---

## 18. 可观测性：status 字段与统计计数器

`yzf net status`（或 HTTP `/yzfnet/status`、动作 `status`）输出字段说明：

**控制台版（`status()` 方法）：**

| 行 | 含义 |
| --- | --- |
| 启用 / 运行中 | 配置开关与实际状态 |
| HTTP / TCP | 绑定地址与端口（禁用时显示"禁用"） |
| 核心网络模块 | `N 个定义, M 个运行中` |
| 核心模块热加载 | 启用/禁用；监听器存活时带 `(监听中)` |
| 内嵌进程 | `processes` 配置的定义数与运行数 |
| 在线外部客户端 | 注册表中的客户端总数（含内嵌模块） |
| 拆包模式 | 当前 mode、阈值、分片、间隔 |
| 限速规则 | 当前生效的令牌桶数量 |
| 观察开关 | 四个 observe 布尔值 |
| 丢包过滤器 | dropFilters 条数 |
| 已拆包 / 已限速丢弃 | `splitPackets` / `rateLimitedPackets` 累计 |
| 事件统计 | 已投递 / 队列满丢弃 / 队列当前长度 |
| 动作处理总数 | `actionsHandled` |

**JSON 版（`statusJson()`）字段：**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `enabled` | bool | 恒 true（能应答说明在运行） |
| `serverOpen` | bool | `Vars.state.isGame() \|\| !Vars.state.isMenu()` |
| `players` | int | 在线玩家数 |
| `wave` | int | 当前波次 |
| `map` | string | 当前地图名 |
| `tps` | float | 实测 TPS |
| `uploadBps` / `downloadBps` | long | 最近采样周期带宽 |
| `gatewayClients` | int | 客户端数 |
| `coreModules` | int | 核心模块定义数 |
| `splitMode` | string | 当前拆分模式 |
| `splitPackets` / `rateLimited` | long | 累计计数 |
| `deliveredEvents` / `droppedEvents` | long | 事件投递/丢弃累计 |

模块 stderr 输出会带前缀进入服务端日志：`[NetMod:<id>] <内容>`（内嵌核心模块）或 `[NetGateway:<name>] <内容>`（processes 内嵌进程）。

---

## 19. FAQ

**Q1：netmods 模块和普通 JS 插件（`yzf/plugins/`）有什么区别？**
A：完全不同。netmods 是网关 spawn 的独立操作系统进程，任意语言、stdin/stdout NDJSON 通信，只参与网络管线；插件跑在框架脚本运行时里，用 `yzf.*` API 参与游戏逻辑。两者目录、加载器、生命周期互不相干。

**Q2：netmods 目录为空会怎样？**
A：服务端保持原版/改版网络行为。日志：`未发现核心网络模块（config/yzf/netmods/ 为空），保持原版/改版网络行为。` 网关本身（HTTP/TCP/限速/拆分能力）仍然可用。

**Q3：改了 netgateway.hjson 里的 splitPolicy，为什么运行中没变化？**
A：splitPolicy 运行时值可被模块的 `splitPolicy` 动作覆盖；配置文件值只在网关（重）启动时读取。用 `yzf net reload` 让配置生效，并用 `yzf net status` 的"拆包模式"行确认。

**Q4：模块一直 `[已停止]`？**
A：依次检查：① `enabled` 是否 false；② `command` 路径是否存在（相对模块文件夹解析）；③ 有 build 配置时看编译日志是否失败；④ 手动运行该 exe 看是否立即退出（比如 stdin 读取写法错误）。

**Q5：C++ 模块能在 Linux 服务器上用吗？**
A：内置 cpp 热编译走 MSVC（vcvars64.bat + cl.exe），是 Windows 路径。Linux 上请配 `build.script` 指向自己的 g++/clang 构建脚本，产物为无扩展名可执行文件（监听器把无扩展名文件也视为二进制变更）。⚠️ 暂未从源码确认：仓库内没有 Linux 构建脚本示例。

**Q6：限速会不会让玩家不同步？**
A：限速只丢弃**发送方向**的快照/同步包，降低更新频率；Mindustry 客户端有位置纠正与快照补偿机制兜底。netwatch/packet-splitter 选取 25~30 pps 的限速值正是为了在带宽与同步质量间取平衡。若观察到明显回弹（rubberband），调高对应限速值或缩短整形窗口。

**Q7：如何彻底关闭拆分功能？**
A：`netgateway.hjson` 里 `splitPolicy: { mode: "off" }`，并且确保没有模块在运行中再发 `splitPolicy` 动作（停掉 packet-splitter：`yzf net stopmod packet-splitter` 且在其 netmodule.hjson 里 `enabled: false`，否则重启后它又会接管）。

**Q8：多个模块同时发 rateLimit 会怎样？**
A：后到的覆盖先到的（同一键只保留一个桶）。模块间需要自行约定优先级（priority 只决定启动顺序，不决定规则归属）。

**Q9：事件丢了怎么排查？**
A：看 `yzf net status` 的 `队列满丢弃`——非 0 说明派发队列（16384）被打满，通常是某个客户端写得太慢。高频场景减少订阅、或提高模块处理速度。

---

## 20. 版本与待确认清单

- 文档基线：Mindustry 159.7 + YZF 框架当前源码（`YZFNetGateway.java` / `YZFNetModHotReloadWatcher.java` / netwatch v1.0.0 / packet-splitter v1.0.0）。
- netwatch 与 packet-splitter 的 `netmodule.hjson` 版本均为 `1.0.0`。

| 项目 | 缺失内容 | 建议确认方式 |
| --- | --- | --- |
| Linux/macOS C++ 热编译 | 内置 cpp 编译链为 MSVC 专用，无 Unix 示例脚本 | 在 Linux 服务端实测 `build.script` 流程 |
| packet-splitter 突发限速不解除 | 退出整形后 BlockSnapshot/Sync 限速持续存在，是否为有意设计 | 与框架维护者确认（月月岛科技） |
| `topPackets` 采样窗口 | 与 NetStatsEvent 同为 1 秒节拍，但计数器清零时机依附 stats 任务 | 源码已确认 `getAndSet(0)` 于采样时执行，无额外风险 |
| netwatch 阈值硬编码 | 常量不可配置（注释称"可改为从 config.hjson 读取"，尚未实现） | 后续版本可能增加配置文件支持 |

---

*本文档由源码逐行核对生成；与实现冲突时以 `server/src/mindustry/yzf/YZFNetGateway.java` 为准。*
