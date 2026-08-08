# MindustryYZF 超详细开发文档

## 稳定性标记说明

本文档中的 API 按稳定性分为三级：

| 标记       | 含义                      | 建议              |
| -------- | ----------------------- | --------------- |
| `🟢 稳定`  | API 签名和行为已定型，后续版本保持向后兼容 | 可放心用于生产环境       |
| `🟡 实验性` | 功能可用但 API 细节可能调整        | 可用于非关键场景，建议做好容错 |
| `🔴 已弃用` | 将在未来版本移除                | 请尽快迁移到替代方案      |

---

## 目录

1. [五分钟快速上手](#五分钟快速上手)
2. [系统概述](#1-系统概述)
3. [目录结构](#2-目录结构)
4. [支持的运行时](#3-支持的运行时)
5. [模块编写指南](#4-模块编写指南)
6. [插件编写指南](#5-插件编写指南)
7. [module.hjson 完整字段说明](#6-modulehjson-完整字段说明)
8. [JavaScript API 完整参考](#7-javascript-api-完整参考)
   - 7.1 [yzfModule 模块信息](#71-yzfmodule-模块信息)
   - 7.2 [生命周期 yzf.onEnable / yzf.onDisable](#72-生命周期)
   - 7.3 [控制台命令 yzf.command](#73-控制台命令)
   - 7.4 [玩家命令 yzf.playerCommand / yzf.adminCommand](#74-玩家命令)
   - 7.5 [事件监听 yzf.on](#75-事件监听)
   - 7.6 [定时器 yzf.after / yzf.every](#76-定时器)
   - 7.7 [日志 yzf.log/info/warn/err](#77-日志)
   - 7.8 [yzf.player 玩家管理](#78-yzfplayer--玩家管理)
   - 7.9 [yzf.game 游戏状态](#79-yzfgame--游戏状态)
   - 7.10 [yzf.net 网络消息](#710-yzfnet--网络消息)
   - 7.11 [yzf.content 内容查询与注册](#711-yzfcontent--内容查询与注册)
   - 7.12 [yzf.world 世界操作](#712-yzfworld--世界操作)
   - 7.13 [yzf.config 模块配置](#713-yzfconfig--模块配置)
   - 7.14 [yzf.remote 远程HTTP服务](#714-yzfremote--远程http服务)
   - 7.15 [yzf.service 外部服务](#715-yzfservice--外部服务)
   - 7.16 [yzf.redis Redis操作](#716-yzfredis--redis操作)（含 [Redis 连接配置](#7161-redis-连接配置)）
   - 7.17 [yzf.sql SQL数据库操作](#717-yzfsql--sql数据库操作)（含 [MySQL/MariaDB 配置](#7171-mysqlmariadb-连接配置)、[SQLite 配置](#7172-sqlite-连接配置)）
   - 7.18 [yzf.minio 对象存储](#718-yzfminio--对象存储)（含 [MinIO 配置](#7181-minio-连接配置)）
   - 7.19 [yzf.ws WebSocket](#719-yzfws--websocket)
   - 7.20 [yzf.comid comid系统](#720-yzfcomid--comid系统)
   - 7.21 [yzf.data 玩家数据持久化](#721-yzfdata--玩家数据持久化)
   - 7.22 [yzf.module 跨模块通信](#722-yzfmodule--跨模块通信)
   - 7.23 [yzf.db JSON数据库](#723-yzfdb--json-数据库)
   - 7.24 [yzf.commands 可调用命令注册表](#724-yzfcommands--可调用命令注册表)
   - 7.25 [yzf.mod Mod命令桥接](#725-yzfmod--mod-命令桥接)
   - 7.26 [yzf.runtime 运行时控制](#726-yzfruntime--运行时控制)
   - 7.27 [yzf.openapi API自省](#727-yzfopenapi--api-自省)
   - 7.28 [yzf.response 响应体构建](#728-yzfresponse--响应体构建)
   - 7.29 [yzf.memory 内存区与进程隔离](#729-yzfmemory--内存区与进程隔离)
   - 7.30 [yzf.status 服务端状态快照](#730-yzfstatus--服务端状态快照)
   - 7.31 [yzf.stableApi 稳定别名 API](#731-yzfstableapi--稳定别名-api)
   - [补充：遗漏的零散方法](#补充遗漏的零散方法)
   - [全局对象参考](#全局对象参考)

9. [外部服务连接配置指南](#外部服务连接配置指南)
10. [错误处理与容错指南](#错误处理与容错指南)
11. [yzf.service.call 通用调用分发表](#yzfservicecall-通用调用分发表)
12. [权限、安全与审计日志](#权限安全与审计日志)
13. [Rhino引擎注意事项与陷阱](#12-rhino-引擎注意事项与陷阱)
14. [服务端命令参考](#13-服务端命令参考)
15. [完整模块模板](#14-完整模块模板)
16. [JVM启动参数](#15-jvm-启动参数)
17. [FAQ](#16-faq)
18. [内置模块、插件与脚手架](#17-内置模块插件与脚手架)
19. [服务端架构：网络配置与进程隔离](#18-服务端架构网络配置与进程隔离)
20. [模块/插件生命周期、卸载与进程线程](#19-模块插件生命周期卸载与进程线程)
21. [服务端可观测性与容错机制](#20-服务端可观测性与容错机制)
22. [脚本安全边界与运行时白名单](#21-脚本安全边界与运行时白名单)

---

## 五分钟快速上手

> 只想先跑起来？按以下 3 步操作，从零到模块加载不超过 5 分钟。

### 第 1 步：创建目录和文件

在服务端数据目录下创建如下结构：

```
<server-data>/yzf/modules/myname/hello/
├── module.hjson
└── scripts/
    └── main.js
```

`module.hjson` 内容：

```hjson
{
    id: "hello"
    name: "Hello Module"
    author: "myname"
    version: "1.0.0"
    main: "scripts/main.js"
}
```

`scripts/main.js` 内容：

```javascript
yzf.onEnable(function() {
    yzf.info("Hello World! 模块加载成功!");

    // 向控制台注册一条命令
    yzf.command("greet", "<name>", "向玩家打招呼", function(args) {
        if (args.length === 0) {
            yzf.info("用法: greet <name>");
            return;
        }
        yzf.info("你好, " + args[0] + "!");
    });

    // 玩家加入时发送欢迎消息
    yzf.on("PlayerJoin", function(event) {
        yzf.player.send(event.player.id, "[cyan]欢迎来到服务器! 输入 /help 查看命令");
    });
});

yzf.onDisable(function() {
    yzf.info("Bye!");
});
```

### 第 2 步：启动服务端

```bash
java -jar server-release.jar
```

服务端启动后会自动扫描 `modules/` 目录并加载模块。控制台应出现：

```
[I] [MindustryYZF] [hello] Hello World! 模块加载成功!
```

### 第 3 步：验证

在服务端控制台输入：

```
greet World
```

应输出：`你好, World!`

进入游戏，加入服务器，应收到欢迎消息。

### 快速上手之后

现在你已经有一个可运行的模块了。接下来可以：

- 想了解更多命令和事件？→ [7.3 控制台命令](#73-控制台命令)、[7.5 事件监听](#75-事件监听)
- 想存取玩家数据？→ [7.21 yzf.data 玩家数据持久化](#721-yzfdata--玩家数据持久化)
- 想接入 Redis / MySQL / MinIO？→ [外部服务连接配置指南](#外部服务连接配置指南)
- 想看完整功能模板？→ [14. 完整模块模板](#14-完整模块模板)

---

## 1. 系统概述

MindustryYZF 是一个基于 Mindustry 服务端的模块化插件系统，允许通过 JavaScript 脚本或外部进程扩展服务端功能。

**核心能力：**

- JavaScript (Rhino) 脚本运行时，直接调用 Java API
- 外部进程桥接（Node.js, Java, Kotlin KT/KTS 等）
- 热加载：修改脚本文件后自动重载
- 模块间通信：导出/调用其他模块的函数
- WebSocket 客户端支持
- 玩家数据持久化（基于 comid）
- 内容元数据注册与属性反射修改
- 外部服务集成（Redis, SQL, HTTP, MinIO）

---

## 2. 目录结构

```
<server-data>/yzf/
│
├── modules/                        # 模块目录（正式模块）
│   └── <author>/                   # 作者名
│       └── <module-name>/          # 模块名
│           ├── module.hjson        # 必须：模块元数据
│           ├── scripts/
│           │   └── main.js         # 主脚本（module.hjson 中 main 字段指定）
│           ├── data/               # 模块数据（运行时自动创建）
│           │   └── config/         # 运行时配置目录
│           │       └── config.hjson # yzf.config.* 存储位置（HJSON）
│           ├── cache/              # 缓存目录（运行时自动创建）
│           └── assets/             # 静态资源（可选）
│
├── plugins/                        # 插件目录（外部插件，平级结构）
│   └── <plugin-name>/              # 直接放在 plugins/ 下，不需要 author 子目录
│       ├── module.hjson
│       ├── scripts/
│       │   └── main.js
│       ├── data/
│       └── assets/
│
├── scripts/                        # 全局共享脚本（所有模块可见）
│
├── config/                         # 框架配置目录
│   ├── services/                   # 外部服务配置（Redis, SQL, HTTP 等）
│   │   ├── redis-main.hjson
│   │   ├── my-db.hjson
│   │   └── http-api.hjson
│   ├── permissions.hjson           # 权限配置（角色、玩家权限）
│   ├── security.hjson              # 安全配置（预留，当前未生效）
│   └── terminal.hjson              # 终端配置（预留，当前未生效）
│
├── data/                           # 全局数据目录
│   ├── comid-registry.json         # comid 注册表
│   └── player-data/                # 玩家数据（以 comid 命名的 JSON 文件）
│       ├── 12345.json
│       └── 67890.json
│
└── logs/                           # 日志目录
    └── yzf-audit.log               # 审计日志
```

---

## 3. 支持的运行时

| 运行时                 | 标识     | 说明                                                  |
| ------------------- | ------ | --------------------------------------------------- |
| JavaScript (Rhino)  | `js`   | 默认，内置 Rhino 引擎，可直接调用 Java API                       |
| Node.js             | `node` | 外部进程，通过 stdin/stdout 通信                             |
| Java                | `java` | 外部 JVM 进程；规范中的 Java 接口调用统一按 KTS 映射说明编写              |
| Kotlin (KT)         | `kt`   | 外部 Kotlin 源码/进程，入口通常为 `.kt`                         |
| Kotlin Script (KTS) | `kts`  | 外部 Kotlin 脚本，入口通常为 `.kts`；运行时与 `kt` 同属 Kotlin 运行时家族 |

> `kt` 与 `kts` 均为服务端插件可用运行时。`runtime: "kt"` 或 `runtime: "kts"` 都会按 Kotlin 运行时加载，其中 `kts` 的脚本入口由 `main` 指向的 `.kts` 文件决定。

### 3.1 Java 接口调用到 KTS 的映射约定

在 KT/KTS 插件规范中，涉及 Mindustry Java API、JDK API 或 Java 接口调用的内容，统一映射为 Kotlin/KTS 写法：

| Java/JS 侧写法                            | KTS 写法                                          |
| -------------------------------------- | ----------------------------------------------- |
| `java.lang.System.currentTimeMillis()` | `System.currentTimeMillis()`                    |
| `java.io.File(path)`                   | `java.io.File(path)` 或 `File(path)`（已 import 时） |
| `mindustry.gen.Call.sendMessage(text)` | `Call.sendMessage(text)`                        |
| `new java.util.HashMap()`              | `java.util.HashMap<Any, Any>()` 或 `hashMapOf()` |

KT/KTS 插件可以直接 `import mindustry.gen.Call`、`import mindustry.Vars` 等 Java 类；YZF 侧对外暴露的协议事件、命令注册、配置和服务调用，也统一按 KTS 代码风格编写和说明。

### 3.2 各运行时的启动引用方式

`module.hjson` 里的 `main` 和 `runtime` 一起决定模块怎么启动、入口文件怎么引用：

| 运行时                 | `runtime` | `main` 引用方式                   | 启动方式                          |
| ------------------- | --------- | ----------------------------- | ----------------------------- |
| JavaScript (Rhino)  | `js`      | `scripts/main.js`             | 服务端直接加载 JS 脚本                 |
| Node.js             | `node`    | `scripts/main.js` 或任意 Node 入口 | 作为外部进程启动，通过 stdin/stdout 通信   |
| Java                | `java`    | `main` 指向 Java 入口类或启动封装       | 作为外部 JVM 进程启动                 |
| Kotlin (KT)         | `kt`      | `scripts/main.kt`             | 作为外部 Kotlin 进程启动，入口通常是 `.kt`  |
| Kotlin Script (KTS) | `kts`     | `scripts/main.kts`            | 作为外部 Kotlin 脚本启动，入口通常是 `.kts` |

> 如果你说的 “Katy” 是指 Kotlin/KTS，这里对应的就是 `kt` / `kts`。如果你实际想写的是某个单独的启动器名字，我也可以按你的项目术语改成那个名字。

---

## 4. 模块编写指南

### 4.1 创建一个模块

1. 在 `modules/` 下创建目录：`modules/你的名字/模块名/`
2. 创建 `module.hjson` 文件
3. 创建 `scripts/main.js`（或其他主脚本路径）
4. 服务端自动扫描并加载

### 4.2 模块目录示例

```
modules/
└── myname/
    └── my-module/
        ├── module.hjson
        └── scripts/
            └── main.js
```

### 4.3 module.hjson 示例

```hjson
{
    id: "my-module"
    name: "My Module"
    author: "myname"
    description: "一个示例模块"
    version: "1.0.0"
    main: "scripts/main.js"
    runtime: "js"
    enabled: true
    category: "Tools"
    permission: "mymod.use"
    tags: ["example"]
}
```

### 4.4 最小可运行脚本

```javascript
// scripts/main.js
yzf.onEnable(function() {
    yzf.info("模块已加载!");
});

yzf.onDisable(function() {
    yzf.info("模块已卸载!");
});
```

---

## 5. 插件编写指南

### 5.1 与模块的区别

- 插件放在 `plugins/` 目录，不需要 author 子目录
- 结构为 `plugins/<plugin-name>/module.hjson`
- author 字段默认为 `"plugins"`
- 其他一切与模块完全相同（同样的 module.hjson 格式、同样的 API）

### 5.2 创建插件

1. 在 `plugins/` 下创建目录：`plugins/我的插件/`
2. 创建 `module.hjson`
3. 创建主脚本
4. 服务端运行时放入即可自动热加载

### 5.3 管理命令

```
yzf plugins                    # 列出所有插件
yzf plugin enable <plugin-id>  # 启用插件
yzf plugin disable <plugin-id> # 禁用插件
yzf reload <plugin-id>         # 重载插件
```

---

## 6. module.hjson 完整字段说明

| 字段             | 类型       | 必填 | 默认值                 | 说明                                      |
| -------------- | -------- | -- | ------------------- | --------------------------------------- |
| `id`           | string   | 是  | 目录名                 | 模块唯一标识                                  |
| `name`         | string   | 否  | id                  | 显示名称                                    |
| `author`       | string   | 否  | 父目录名                | 作者（plugins/ 下默认 "plugins"）              |
| `description`  | string   | 否  | `""`                | 模块描述                                    |
| `version`      | string   | 否  | `"0.1.0"`           | 版本号                                     |
| `main`         | string   | 否  | `"scripts/main.js"` | 主脚本路径（相对于模块根目录）                         |
| `runtime`      | string   | 否  | `"js"`              | 运行时类型：`js`, `node`, `java`, `kt`, `kts` |
| `enabled`      | boolean  | 否  | `true`              | 是否启用                                    |
| `hidden`       | boolean  | 否  | `false`             | 是否在列表中隐藏                                |
| `requiresArgs` | boolean  | 否  | `false`             | 是否需要参数才能运行                              |
| `category`     | string   | 否  | `"Runtime"`         | 分类标签                                    |
| `permission`   | string   | 否  | `""`                | 模块所需权限前缀                                |
| `tags`         | string[] | 否  | `[]`                | 自定义标签                                   |
| `depends`      | string[] | 否  | `[]`                | 硬依赖模块ID列表                               |
| `softDepends`  | string[] | 否  | `[]`                | 软依赖模块ID列表                               |
| `loadType`     | string   | 否  | `"module"`         | 加载类型：`module`（`modules/` 目录）或 `plugin`（`plugins/` 目录）；框架据此区分扫描路径与 `yzfModule` 的 `_source` |
| `memoryMin`    | string   | 否  | `""`                | 内存区最小堆（如 `"256m"`）；仅 `process` / `classloader` 隔离生效 |
| `memoryMax`    | string   | 否  | `""`                | 内存区最大堆（如 `"1g"`）；`yzf runtime setMemory` 会写回此字段 |
| `jvmArgs`      | string[] | 否  | `[]`                | 传递给 JVM / 子进程的额外启动参数（如 `["-Xss512k"]`） |
| `programArgs`  | string[] | 否  | `[]`                | 传递给 `node` / `java` 运行时进程的程序参数 |

> 进程隔离相关字段（`loadType` 除外）只在模块走 `process` 或 `classloader` 隔离时生效；纯 `js` 嵌入式运行时忽略 `memoryMin` / `memoryMax` / `jvmArgs` / `programArgs`。其中 `memoryMin` / `memoryMax` 与 [19.3 卸载触发场景](#193-卸载的-6-类触发场景) 的 `yzf runtime setMemory` 直接对应（该命令写回 `module.hjson` 这两个字段后，进程型模块需重载才生效）；`loadType` 与两类目录的扫描差异见 [19.1 模块 vs 插件](#191-模块与插件的真相两套目录同一套-api)。详见 [第 18 章 进程隔离](#18-服务端架构网络配置与进程隔离) 与 [第 19 章 卸载与进程线程](#19-模块插件生命周期卸载与进程线程)。

#### 依赖解析行为

加载器使用**深度优先拓扑排序**（`YZFDependencyResolver`）确定模块加载顺序，保证依赖先于被依赖者加载。

| 场景        | 行为                                           |
| --------- | -------------------------------------------- |
| 硬依赖存在     | 按拓扑序加载，依赖先加载                                 |
| 软依赖存在     | 同上——有则按序加载                                   |
| **硬依赖缺失** | 模块**不加载**（静默跳过），日志中记录错误                      |
| **软依赖缺失** | 模块**正常加载**，日志中记录警告                           |
| **循环依赖**  | 日志中记录错误路径（如 `A -> B -> C -> A`），模块仍会加载但顺序不确定 |

**依赖 ID 格式：** 使用模块的完整 ID，即 `author/moduleId`，如 `"monthzifang/yueyu-hud"`。

```hjson
{
    id: "my-plugin"
    // 硬依赖：没有 yueyu-hud 就不加载
    depends: ["monthzifang/yueyu-hud"]

    // 软依赖：有 redis-main 就用，没有也正常运行
    softDepends: ["example/redis-helper"]
}
```

**重载级联：** 重载一个模块时，所有直接或间接依赖它的模块也会被重载。重载是**事务性**的——如果级联中任何一个模块加载失败，整批回滚到重载前的状态。

```bash
yzf reload author/lib-v2
# 实际重载顺序：lib-v2 → 依赖 lib-v2 的 A → 依赖 A 的 B（回滚如果 B 失败）
```

---

## 7. JavaScript / KT / KTS API 完整参考

所有 API 通过全局对象 `yzf`（功能接口）和 `yzfModule`（模块自身信息）访问。下文示例以 JavaScript 为主，但相同的功能接口同样适用于 KT/KTS 规范中的对应写法。

---

### 7.1 yzfModule 模块信息 `🟢 稳定`

在模块脚本中，`yzfModule` 自动可用，包含当前模块的元数据。

```javascript
yzfModule.id          // string: 模块ID（如 "my-module"）
yzfModule.fullId      // string: 完整ID（如 "myname/my-module"）
yzfModule.name        // string: 显示名称
yzfModule.author      // string: 作者名
yzfModule.version     // string: 版本号
yzfModule.root        // string: 模块根目录的绝对路径
yzfModule.scriptsDir  // string: 脚本目录的绝对路径
yzfModule.dataDir     // string: 数据目录的绝对路径
yzfModule.cacheDir    // string: 缓存目录的绝对路径
```

**使用示例：**

```javascript
yzf.onEnable(function() {
    yzf.info("模块 " + yzfModule.name + " v" + yzfModule.version + " 已加载");
    yzf.info("数据目录: " + yzfModule.dataDir);
    yzf.info("完整ID: " + yzfModule.fullId);
});
```

---

### 7.2 生命周期 `🟢 稳定`

#### `yzf.onEnable(callback)`

注册模块启用回调。在模块首次加载、热重载后调用。

```javascript
yzf.onEnable(function() {
    yzf.info("模块初始化...");
    // 初始化资源、注册命令、启动定时器等
});
```

#### `yzf.onDisable(callback)`

注册模块禁用回调。在模块卸载（热重载、服务器关闭）时调用。

> 模块“是怎么被卸载、由谁触发、在哪些进程 / 线程上运行、卸载时清理了哪些资源”的完整机制，见 [第 19 章 模块/插件生命周期、卸载与进程线程](#19-模块插件生命周期卸载与进程线程)。

```javascript
yzf.onDisable(function() {
    yzf.info("模块清理...");
    // 释放资源、关闭连接、保存数据等
});
```

---

### 7.3 控制台命令 `🟢 稳定`

#### `yzf.command(name, usage, description, callback)`

注册服务端控制台命令。在服务端终端中输入命令时触发。

**参数：**

- `name` (string): 命令名称，必须唯一
- `usage` (string): 用法说明，如 `"[port] [host]"`
- `description` (string): 命令描述
- `callback` (function): 回调函数，参数为 `args`（字符串数组）

**3参数形式（无usage）：**

```javascript
yzf.command(name, description, callback)
```

**完整示例：**

```javascript
yzf.command("broadcast", "<message>", "向所有玩家广播消息", function(args) {
    if (args.length === 0) {
        yzf.info("用法: broadcast <message>");
        return;
    }
    var msg = Array.from(args).join(" ");
    yzf.net.broadcast("[yellow][公告] " + msg);
    yzf.info("已广播: " + msg);
});

yzf.command("tps", "显示服务器TPS", function(args) {
    yzf.info("当前TPS: " + yzf.game.tps());
    yzf.info("当前波次: " + yzf.game.wave());
    yzf.info("在线人数: " + yzf.player.count());
});

yzf.command("give", "<item> [amount] [teamId]", "向核心添加物品", function(args) {
    if (args.length < 1) {
        yzf.info("用法: give <item> [amount] [teamId]");
        return;
    }
    var item = String(args[0]);
    var amount = args.length > 1 ? parseInt(String(args[1])) : 1000;
    var teamId = args.length > 2 ? parseInt(String(args[2])) : 1;
    var r = JSON.parse(yzf.world.fill(item, amount, teamId));
    yzf.info(r.message);
});
```

**args 参数说明：**

- `args` 是一个 Rhino 数组对象，不是原生 JS 数组
- 使用 `args.length` 获取长度
- 使用 `args[i]` 或 `args.get(i)` 访问元素
- 需要转为 JS 数组时用 `Array.from(args)` 或 `Array.prototype.slice.call(args)`

---

### 7.4 玩家命令 `🟢 稳定`

#### `yzf.playerCommand(name, usage, description, callback)`

注册玩家聊天命令（所有人可用）。

**回调参数：** `callback(player, args)`

- `player`: Player 对象，包含 `.id`, `.name`, `.uuid`, `.admin`, `.team` 等
- `args`: 字符串数组

```javascript
yzf.playerCommand("ping", "", "测试延迟", function(player, args) {
    yzf.player.send(player.id, "[green]Pong! 延迟测试正常");
});

yzf.playerCommand("stats", "[player]", "查看玩家统计", function(player, args) {
    var target = player;
    if (args.length > 0) {
        var found = yzf.player.find(String(args[0]));
        if (found) target = found;
    }
    var info = JSON.parse(yzf.player.info(target.id));
    yzf.player.send(player.id, "[cyan]--- 玩家信息 ---");
    yzf.player.send(player.id, "名称: " + info.name);
    yzf.player.send(player.id, "UUID: " + info.uuid);
    yzf.player.send(player.id, "队伍: " + info.team);
    yzf.player.send(player.id, "comid: " + (info.comid || "未分配"));
});

yzf.playerCommand("online", "", "查看在线玩家", function(player, args) {
    var players = JSON.parse(yzf.player.list());
    var msg = "[cyan]在线玩家 (" + players.length + "人):[white] ";
    var names = [];
    for (var i = 0; i < players.length; i++) {
        names.push(players[i].name);
    }
    yzf.player.send(player.id, msg + names.join(", "));
});
```

#### `yzf.adminCommand(name, usage, description, permission, callback)`

注册管理员命令（需要指定权限）。

**回调参数：** `callback(player, args)`

```javascript
yzf.adminCommand("kick-all", "<reason>", "踢出所有玩家", "yzf.admin.kickall", function(player, args) {
    var reason = args.length > 0 ? Array.from(args).join(" ") : "服务器维护";
    var players = JSON.parse(yzf.player.list());
    var count = 0;
    for (var i = 0; i < players.length; i++) {
        if (players[i].id !== player.id) {
            yzf.player.kick(players[i].id, reason);
            count++;
        }
    }
    yzf.player.send(player.id, "[green]已踢出 " + count + " 名玩家");
});

yzf.adminCommand("wave", "<number>", "设置波次", "yzf.admin.wave", function(player, args) {
    if (args.length === 0) {
        yzf.player.send(player.id, "[yellow]当前波次: " + yzf.game.wave());
        return;
    }
    var wave = parseInt(String(args[0]));
    yzf.game.setWave(wave);
    yzf.player.send(player.id, "[green]波次已设置为 " + wave);
});
```

---

### 7.5 事件监听 `🟢 稳定`

#### `yzf.on(eventName, callback)`

监听 Mindustry 游戏事件。

**回调参数：** `callback(event)` — event 对象因事件类型而异

**完整事件列表：**

| 事件名                  | 触发时机     | event 字段                                                    |
| -------------------- | -------- | ----------------------------------------------------------- |
| `PlayerJoin`         | 玩家加入     | `event.player`                                              |
| `PlayerLeave`        | 玩家离开     | `event.player`                                              |
| `PlayerChat`         | 玩家聊天     | `event.player`, `event.message`                             |
| `WaveEvent`          | 波次开始     | 无特殊字段                                                       |
| `GameOverEvent`      | 游戏结束     | `event.winner`                                              |
| `ResetEvent`         | 地图重置     | 无特殊字段                                                       |
| `WorldLoadEvent`     | 世界加载完成   | 无特殊字段                                                       |
| `BuildSelectEvent`   | 选择建筑     | `event.build`, `event.builder`                              |
| `BlockBuildEndEvent` | 建筑建造完成   | `event.build`, `event.breaking`                             |
| `BlockDestroyEvent`  | 建筑被摧毁    | `event.build`                                               |
| `UnitDestroyEvent`   | 单位被摧毁    | `event.unit`                                                |
| `UnitCreateEvent`    | 单位创建     | `event.unit`                                                |
| `DepositEvent`       | 物品存入建筑   | `event.build`, `event.player`, `event.item`, `event.amount` |
| `WithdrawEvent`      | 物品从建筑取出  | `event.build`, `event.player`, `event.item`, `event.amount` |
| `ConfigEvent`        | 建筑配置变更   | `event.build`, `event.player`, `event.value`                |
| `TapEvent`           | 玩家点击tile | `event.player`, `event.tile`                                |
| `GameJoinEvent`      | 客户端加入游戏  | `event.player`                                              |
| `ServerLoadEvent`    | 服务器完成加载  | 无特殊字段                                                       |

**示例：**

```javascript
yzf.on("PlayerJoin", function(event) {
    var p = event.player;
    yzf.info(p.plainName() + " 加入了服务器");
    yzf.net.broadcast("[green]" + p.plainName() + " 加入了服务器！");

    // 发送欢迎消息
    yzf.player.send(p.id, "[cyan]=== 欢迎来到服务器 ===");
    yzf.player.send(p.id, "[white]输入 [yellow]/help [white]查看可用命令");
});

yzf.on("PlayerLeave", function(event) {
    yzf.info(event.player.plainName() + " 离开了服务器");
});

yzf.on("PlayerChat", function(event) {
    var msg = event.message;
    // 简单的聊天格式化
    if (event.player.admin) {
        event.message = "[red][管理] " + event.player.plainName() + ": [white]" + msg;
    }
});

yzf.on("WaveEvent", function(event) {
    var wave = yzf.game.wave();
    yzf.info("第 " + wave + " 波开始！");

    // 每10波发送奖励
    if (wave % 10 === 0) {
        yzf.net.broadcast("[yellow]第 " + wave + " 波奖励：全队核心 +500 铜！");
        yzf.world.fill("copper", 500, 1);
    }
});

yzf.on("GameOverEvent", function(event) {
    yzf.net.broadcast("[scarlet]游戏结束！即将重置地图...");
});

yzf.on("UnitDestroyEvent", function(event) {
    var unit = event.unit;
    // 记录单位被摧毁
    yzf.log("单位被摧毁: " + unit.type.name + " at (" + Math.round(unit.x) + "," + Math.round(unit.y) + ")");
});
```

**获取 Player 对象字段：**

```javascript
var p = event.player;
p.id            // int: 玩家ID
p.plainName()   // string: 纯文本名称
p.name          // string: 带格式的名称
p.uuid()        // string: UUID
p.ip()          // string: IP地址
p.admin         // boolean: 是否管理员
p.team().id     // int: 队伍ID
p.x             // float: X坐标
p.y             // float: Y坐标
p.dead()        // boolean: 是否死亡
```

---

### 7.6 定时器 `🟢 稳定`

#### `yzf.after(delaySeconds, callback)`

延迟执行**一次**。一次性定时器，触发后自动销毁。

```javascript
yzf.after(5, function() {
    yzf.info("5秒后执行（仅一次）");
});
```

#### `yzf.every(delaySeconds, intervalSeconds, callback)`

定时**循环**执行。首次延迟后，按间隔无限重复。

- `delaySeconds` (number): 首次延迟（秒），0 表示立即开始
- `intervalSeconds` (number): 循环间隔（秒）
- `callback` (function): 回调函数

```javascript
// 每10秒执行一次（首次无延迟）
yzf.every(0, 10, function() {
    yzf.info("TPS: " + yzf.game.tps() + " | 玩家: " + yzf.player.count());
});

// 延迟3秒后，每60秒执行一次
yzf.every(3, 60, function() {
    yzf.net.broadcast("[yellow]服务器运行中 | 波次: " + yzf.game.wave());
});
```

> **区别：** `after` = 延迟后执行一次（`Timer.schedule(fn, delay)`）；`every` = 延迟后循环执行（`Timer.schedule(fn, delay, interval)`）。两者底层调用不同的 Arc Timer 重载，不能互相替代。

### 7.7 日志 `🟢 稳定`

```javascript
yzf.log("普通日志");     // [I] [MindustryYZF] [moduleId] 普通日志
yzf.info("信息日志");     // [I] [MindustryYZF] [moduleId] 信息日志
yzf.warn("警告日志");     // [W] [MindustryYZF] [moduleId] 警告日志
yzf.err("错误日志");      // [E] [MindustryYZF] [moduleId] 错误日志
```

---

### 7.8 yzf.player — 玩家管理 `🟢 稳定`

#### `yzf.player.count()` → number

返回在线玩家数量。

```javascript
var count = yzf.player.count();
yzf.info("在线人数: " + count);
```

#### `yzf.player.list()` → string (JSON)

返回在线玩家列表的 JSON 字符串，需要 `JSON.parse()`。

```javascript
var players = JSON.parse(yzf.player.list());
for (var i = 0; i < players.length; i++) {
    var p = players[i];
    yzf.info("[" + p.id + "] " + p.name + " team=" + p.team + " admin=" + p.admin);
}
```

**返回格式：**

```json
[
    {
        "id": 0,
        "name": "PlayerName",
        "uuid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
        "ip": "192.168.1.1",
        "admin": false,
        "team": 1,
        "x": 1234.5,
        "y": 5678.9,
        "dead": false,
        "comid": 12345
    }
]
```

#### `yzf.player.info(playerId)` → string (JSON) | null

获取指定玩家的详细信息。

```javascript
var info = JSON.parse(yzf.player.info(0));
if (info) {
    yzf.info("玩家: " + info.name + " UUID: " + info.uuid);
}
```

#### `yzf.player.find(nameOrId)` → string (JSON) | null

查找玩家。支持按 player ID、comid、名称模糊搜索。

```javascript
// 按ID查找
var p = JSON.parse(yzf.player.find("0"));

// 按comid查找
var p = JSON.parse(yzf.player.find("12345"));

// 按名称模糊查找
var p = JSON.parse(yzf.player.find("some"));
if (p) {
    yzf.info("找到: " + p.name + " (ID:" + p.id + ")");
}
```

#### `yzf.player.kick(playerId, reason, durationMs?)`

踢出玩家。

```javascript
yzf.player.kick(0, "违规行为");
yzf.player.kick(0, "临时封禁", 3600000); // 1小时（毫秒）
```

#### `yzf.player.ban(playerId)` → boolean

按 player ID 封禁（同时封禁 UUID）。

```javascript
yzf.player.ban(0);
```

#### `yzf.player.banIP(ip)` → boolean

按 IP 封禁。

```javascript
yzf.player.banIP("192.168.1.100");
```

#### `yzf.player.banID(uuid)` → boolean

按 UUID 封禁。

```javascript
yzf.player.banID("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx");
```

#### `yzf.player.unbanIP(ip)` → boolean

解除 IP 封禁。

#### `yzf.player.unbanID(uuid)` → boolean

解除 UUID 封禁。

#### `yzf.player.admin(playerId, isAdmin)` → boolean

设置/取消管理员。

```javascript
yzf.player.admin(0, true);   // 设为管理员
yzf.player.admin(0, false);  // 取消管理员
```

#### `yzf.player.send(playerId, message)`

向指定玩家发送消息（仅该玩家可见）。另有 `yzf.net.send` 等同，推荐统一使用本方法。

```javascript
yzf.player.send(0, "[green]这是一条私信");
yzf.player.send(0, "[yellow]警告：请遵守规则");
yzf.player.send(0, "[cyan]当前波次: " + yzf.game.wave());
```

---

### 7.9 yzf.game — 游戏状态 `🟢 稳定`

#### `yzf.game.wave()` → int

获取当前波次。

#### `yzf.game.setWave(n)`

设置波次。

#### `yzf.game.waveTime()` → float

获取波次倒计时（单位：帧，60帧=1秒）。

#### `yzf.game.setWaveTime(ticks)`

设置波次倒计时。

#### `yzf.game.skipWave()`

跳过当前波，立即开始下一波。

#### `yzf.game.tick()` → double

获取当前游戏 tick。

#### `yzf.game.tps()` → int

获取服务器 TPS（每秒 tick 数，理想值 60）。

#### `yzf.game.map()` → string (JSON)

获取当前地图信息。

```javascript
var map = JSON.parse(yzf.game.map());
yzf.info("地图: " + map.name + " (" + map.width + "x" + map.height + ")");
```

**返回：** `{ "name": "...", "width": 100, "height": 100 }`

#### `yzf.game.isPlaying()` → boolean

是否在游戏中。

#### `yzf.game.isPaused()` → boolean

是否暂停。

#### `yzf.game.isCampaign()` → boolean

是否战役模式。

#### `yzf.game.isPvp()` → boolean

是否 PVP 模式。

#### `yzf.game.isAttack()` → boolean

是否攻击模式。

#### `yzf.game.enemies()` → int

当前敌人数量。

#### `yzf.game.rules()` → string (JSON)

获取游戏规则。

```javascript
var rules = JSON.parse(yzf.game.rules());
yzf.info("PVP: " + rules.pvp);
yzf.info("无限资源: " + rules.infiniteResources);
yzf.info("单位上限: " + rules.unitCap);
```

#### `yzf.game.setRule(key, value)` → boolean

修改游戏规则。key 和 value 都是字符串。

```javascript
yzf.game.setRule("pvp", "true");
yzf.game.setRule("infiniteResources", "true");
yzf.game.setRule("waveSpacing", "600");
yzf.game.setRule("unitCap", "200");
yzf.game.setRule("buildSpeedMultiplier", "2.0");
yzf.game.setRule("unitHealthMultiplier", "1.5");
```

**可用规则 key：**

| key                        | 值类型     | 说明       |
| -------------------------- | ------- | -------- |
| `waves`                    | boolean | 是否有波次    |
| `waveTimer`                | boolean | 波次计时器    |
| `waveSpacing`              | float   | 波次间隔（帧）  |
| `pvp`                      | boolean | PVP模式    |
| `attackMode`               | boolean | 攻击模式     |
| `infiniteResources`        | boolean | 无限资源     |
| `fog`                      | boolean | 战争迷雾     |
| `lighting`                 | boolean | 光照       |
| `unitCap`                  | int     | 单位上限     |
| `unitBuildSpeedMultiplier` | float   | 单位建造速度倍率 |
| `unitDamageMultiplier`     | float   | 单位伤害倍率   |
| `unitHealthMultiplier`     | float   | 单位血量倍率   |
| `blockHealthMultiplier`    | float   | 建筑血量倍率   |
| `blockDamageMultiplier`    | float   | 建筑伤害倍率   |
| `buildSpeedMultiplier`     | float   | 建造速度倍率   |
| `buildCostMultiplier`      | float   | 建造消耗倍率   |
| `defaultTeam`              | int     | 默认队伍ID   |
| `waveTeam`                 | int     | 敌方队伍ID   |
| `winWave`                  | int     | 胜利波次     |

---

### 7.10 yzf.net — 网络消息 `🟢 稳定`

#### `yzf.net.send(playerId, message)` — 等同于 `yzf.player.send(playerId, message)`

> 两者底层实现完全一致，都是 `player.sendMessage()`。选择哪个取决于语义偏好：`yzf.player.send` 强调"发给某个玩家"，`yzf.net.send` 强调"网络层发送"。推荐统一使用 `yzf.player.send`。

#### `yzf.net.broadcast(message)` / `yzf.net.broadcast(message, senderId)`

向所有玩家广播消息。可选第二个参数指定发送者 ID，消息会显示为该玩家所说。

```javascript
// 匿名广播（服务器公告）
yzf.net.broadcast("[green]服务器公告：欢迎来到服务器！");
yzf.net.broadcast("[yellow]警告：服务器将在5分钟后重启");

// 以某玩家名义广播（senderId 可选）
yzf.net.broadcast("[yellow]大家好！", 0); // 以 ID=0 的玩家名义发送
```

---

### 7.11 yzf.content — 内容查询与注册 `🟢 稳定`

#### 查询游戏内容

所有查询函数返回 JSON 字符串或 null。

```javascript
var block = JSON.parse(yzf.content.block("copper-wall"));
// { "name": "copper-wall", "id": 5, "type": "block" }

var item = JSON.parse(yzf.content.item("copper"));
// { "name": "copper", "id": 0, "type": "item" }

var liquid = JSON.parse(yzf.content.liquid("water"));
// { "name": "water", "id": 0, "type": "liquid" }

var unit = JSON.parse(yzf.content.unit("flare"));
// { "name": "flare", "id": 0, "type": "unit" }

var status = JSON.parse(yzf.content.status("burning"));
// { "name": "burning", "id": 0, "type": "status" }

var weather = JSON.parse(yzf.content.weather("rain"));
// { "name": "rain", "id": 0, "type": "weather" }

var planet = JSON.parse(yzf.content.planet("serpulo"));
// { "name": "serpulo", "id": 0, "type": "planet" }
```

#### 列出所有内容

```javascript
var blocks = JSON.parse(yzf.content.blocks());
// [{ "name": "copper-wall", "id": 5 }, ...]

var items = JSON.parse(yzf.content.items());
var liquids = JSON.parse(yzf.content.liquids());
var units = JSON.parse(yzf.content.units());
```

#### 注册自定义内容元数据

```javascript
// 注册
yzf.content.registerMeta("mymod", "super-sword", JSON.stringify({
    damage: 999,
    range: 10,
    speed: 1.5
}));

// 查询
var meta = JSON.parse(yzf.content.getMeta("mymod", "super-sword"));
if (meta) {
    yzf.info("伤害: " + meta.damage);
}

// 列出命名空间下所有元数据
var list = JSON.parse(yzf.content.listMeta("mymod"));

// 列出所有命名空间
var ns = JSON.parse(yzf.content.listNamespaces());

// 删除
yzf.content.removeMeta("mymod", "super-sword");
```

#### 修改内容属性（反射）

```javascript
// 读取属性
var hp = yzf.content.getProperty("copper-wall", "health");
yzf.info("copper-wall 血量: " + hp);

// 修改属性
var result = JSON.parse(yzf.content.setProperty("copper-wall", "health", "500"));
if (result.success) {
    yzf.info("修改成功");
}
```

---

### 7.12 yzf.world — 世界操作 `🟢 稳定`

#### `yzf.world.spawn(type, id, x, y, size, teamId, buff)` → string (JSON)

在指定位置生成内容。

**参数：**

- `type` (string): 类型 — `"unit"`, `"block"`, `"floor"`, `"overlay"`, `"liquid"`
- `id` (string): 内容名称 — `"flare"`, `"copper-wall"`, `"stone"`, `"copper"`, `"water"`
- `x` (int): X 坐标（tile 坐标）
- `y` (int): Y 坐标（tile 坐标）
- `size` (int): 范围大小 — 1=1x1, 2=3x3, 3=5x5（向外扩展）
- `teamId` (int): 队伍 ID — 0=无主, 1=黄队, 2=红队, 3=紫队
- `buff` (string|null): 状态效果名称（仅 unit 有效），null 表示无

**返回 JSON：**

```json
{ "success": true, "spawned": 9, "message": "生成了 9 个 flare" }
{ "success": false, "message": "找不到单位: xxx" }
```

**示例：**

```javascript
// 生成1个单位
var r = JSON.parse(yzf.world.spawn("unit", "flare", 100, 200, 1, 1, null));

// 生成3x3=9个单位
var r = JSON.parse(yzf.world.spawn("unit", "mono", 100, 200, 2, 1, null));

// 生成带buff的单位
var r = JSON.parse(yzf.world.spawn("unit", "flare", 100, 200, 1, 1, "burning"));

// 放置3x3方块
var r = JSON.parse(yzf.world.spawn("block", "copper-wall", 50, 60, 2, 1, null));

// 设置5x5地板
var r = JSON.parse(yzf.world.spawn("floor", "stone", 50, 60, 3, 1, null));

// 设置矿脉overlay
var r = JSON.parse(yzf.world.spawn("overlay", "copper", 50, 60, 2, 1, null));

// 注入液体到建筑
var r = JSON.parse(yzf.world.spawn("liquid", "water", 50, 60, 1, 1, null));
```

**队伍 ID 参考：**

| ID | 名称       | 说明       |
| -- | -------- | -------- |
| 0  | derelict | 无主/废弃    |
| 1  | sharded  | 黄队（玩家默认） |
| 2  | crux     | 红队（敌方默认） |
| 3  | malis    | 紫队       |
| 4  | green    | 绿队       |
| 5  | blue     | 蓝队       |

#### `yzf.world.fill(itemId, amount, teamId)` → string (JSON)

向核心填充物品。

**参数：**

- `itemId` (string): 物品名称或 `"all"` 表示所有物品
- `amount` (int): 数量
- `teamId` (int): 队伍 ID

```javascript
// 填充1000铜
var r = JSON.parse(yzf.world.fill("copper", 1000, 1));

// 填充所有物品各1000个
var r = JSON.parse(yzf.world.fill("all", 1000, 1));
```

---

### 7.13 yzf.config — 模块配置 `🟢 稳定`

每个模块有独立的配置存储，位于模块 `data/config/config.hjson`（HJSON 格式）。

#### `yzf.config.get(key, defaultValue)` → string

```javascript
var port = yzf.config.get("port", "8080");
```

#### `yzf.config.getBool(key, defaultValue)` → boolean

```javascript
var debug = yzf.config.getBool("debug", false);
```

#### `yzf.config.getInt(key, defaultValue)` → int

```javascript
var maxPlayers = yzf.config.getInt("maxPlayers", 16);
```

#### `yzf.config.set(key, value)`

```javascript
yzf.config.set("port", "9090");
```

#### `yzf.config.setBool(key, value)`

```javascript
yzf.config.setBool("debug", true);
```

#### `yzf.config.setInt(key, value)`

```javascript
yzf.config.setInt("maxPlayers", 32);
```

#### `yzf.config.path()` → string

返回配置文件路径（绝对路径）。

#### 配置存储底层机制（YZFModuleConfigStore）

- **文件位置**：`<模块根>/data/config/config.hjson`，HJSON 格式。`<模块根>/config.hjson` 仅作为**索引/描述符**（`configType: "runtime"`），真正的运行时键值全部落在 `data/config/config.hjson`。
- **启动迁移**：首次启动时若根 `config.hjson` 缺失或版本不匹配，框架自动把旧位置（`root/config.hjson` 或 `data/config.hjson`）的内容**迁移**到 `data/config/config.hjson`，并补全根索引描述符，无需手动操作。
- **HJSON `#` 注释**：配置支持 shell 风格的 `#` 注释行；若注释行残留 key（部分编辑器把 key 追到注释后），框架会自动恢复 key 值而非丢弃整行。
- **重复键去重**：同一 key 多次出现时**保留用户第一个值**，忽略后续重复（含前端默认值追加的重复值），避免前端默认值覆盖用户已配置的值。
- **写即落盘**：`set/setBool/setInt` 每次调用都立即 `save()` 写回磁盘，服务重启后保留。
- **与 `module.hjson` 的区别**：`module.hjson` 是模块**声明**（静态元数据、依赖、入口），只读；`config.hjson` 是模块**运行时配置**（动态键值、可读写、跨重启保留）。

> ⚠️ 纠错：旧版本文档曾把该文件写作 `data/config.json`，实际文件为 `data/config/config.hjson`（HJSON），请以本处为准。

---

### 7.14 yzf.remote — 远程 HTTP 服务 `🟢 稳定`

需要先在 `services/` 目录下配置 HTTP 服务端点（详见下方 [外部服务连接配置指南](#外部服务连接配置指南)）。

#### `yzf.remote.get(serviceId, path)` → string

```javascript
var data = yzf.remote.get("my-api", "/status");
```

#### `yzf.remote.postJson(serviceId, path, body)` → string

```javascript
var result = yzf.remote.postJson("my-api", "/submit", JSON.stringify({score: 100}));
```

---

### 7.15 yzf.service — 外部服务 `🟢 稳定`

#### `yzf.service.has(serviceId)` → boolean

```javascript
if (yzf.service.has("redis-main")) {
    yzf.info("Redis 服务可用");
}
```

#### `yzf.service.list()` → string (JSON)

```javascript
var services = JSON.parse(yzf.service.list());
```

#### `yzf.service.info(serviceId)` → string (JSON) | null

```javascript
var info = JSON.parse(yzf.service.info("redis-main"));
```

#### `yzf.service.call(serviceId, action, ...args)` → string

通用服务调用。

```javascript
var result = yzf.service.call("my-service", "doSomething", "arg1", "arg2");
```

---

### 7.16 yzf.redis — Redis 操作 `🟢 稳定`

> **前置条件：** 需要先配置 Redis 服务连接（详见 [Redis 连接配置](#7161-redis-连接配置)）。

#### `yzf.redis.get(serviceId, key)` → string | null

```javascript
var val = yzf.redis.get("redis-main", "player:12345:name");
```

#### `yzf.redis.set(serviceId, key, value)`

```javascript
yzf.redis.set("redis-main", "player:12345:name", "CoolPlayer");
```

#### `yzf.redis.del(serviceId, key)`

```javascript
yzf.redis.del("redis-main", "player:12345:name");
```

#### `yzf.redis.incr(serviceId, key)` → long

```javascript
var count = yzf.redis.incr("redis-main", "server:login:count");
```

#### `yzf.redis.hget(serviceId, key, field)` → string | null

```javascript
var val = yzf.redis.hget("redis-main", "players:12345", "score");
```

#### `yzf.redis.hset(serviceId, key, field, value)`

```javascript
yzf.redis.hset("redis-main", "players:12345", "score", "100");
```

#### 缓存抽象：CacheClient 设计 `🟢 稳定`

> Redis 服务之所以能直接当缓存用，是因为它实现了框架的 `YZFCacheClient` 接口。缓存不是某个具体后端的私有能力，而是\*\*「服务子类型」抽象\*\*：任何注册为 `YZFCacheClient` 的服务都自动获得缓存语义，可插拔、可替换后端。

- 脚本层调用缓存有**两种等价入口**：
  1. **专用方法**（本节 `yzf.redis.*`）：语义清晰、类型安全；
  2. **通用 `yzf.service.call` 的 cache action**（见 [第 10 章](#yzfservicecall-通用调用分发表)）：按 `instanceof YZFCacheClient` 分派，支持 `get` / `set` / `delete` / `incr` / `hget` / `hset`。

```javascript
// 下面两种写法完全等价
yzf.redis.set("redis-main", "k", "v");
yzf.service.call("redis-main", "set", "k", "v");

yzf.redis.incr("redis-main", "counter");
yzf.service.call("redis-main", "incr", "counter");   // 返回字符串形式的计数值
```

- 分发逻辑（`YZFScriptServices`）：先按服务实际类型 `instanceof` 判断——`YZFRemoteClient`→远程 HTTP、`YZFCacheClient`→缓存、`YZFSqlClient`→SQL、`YZFObjectStorageClient`→对象存储——再匹配 action；无匹配则抛 `IllegalArgumentException("不支持的服务操作: ...")`。
- 可插拔性：将来接入其它缓存后端，只要实现 `YZFCacheClient`，脚本无需改动即可复用上述全部调用方式。
- 可观测性：缓存调用计入 `redisCalls` 指标（见 [第 20 章](#20-服务端可观测性与容错机制)）；在命令 / 事件上下文中的缓存异常经 [第 20 章回调保护](#20-服务端可观测性与容错机制) 被隔离。

---

### 7.17 yzf.sql — SQL 数据库操作 `🟢 稳定`

> **前置条件：** 需要先配置数据库服务连接（详见 [MySQL/MariaDB 连接配置](#7171-mysqlmariadb-连接配置) 和 [SQLite 连接配置](#7172-sqlite-连接配置)）。

#### `yzf.sql.queryFirstCell(serviceId, sql)` → string

查询单个值。

```javascript
var count = yzf.sql.queryFirstCell("my-db", "SELECT count(*) FROM players");
yzf.info("玩家总数: " + count);
```

#### `yzf.sql.execute(serviceId, sql)` → int

执行 INSERT/UPDATE/DELETE，返回影响行数。

```javascript
var affected = yzf.sql.execute("my-db", "INSERT INTO logs(msg) VALUES('test')");
yzf.info("插入了 " + affected + " 行");
```

#### `yzf.sql.queryJson(serviceId, sql)` → string (JSON)

查询结果为 JSON 数组。

```javascript
var rows = JSON.parse(yzf.sql.queryJson("my-db", "SELECT * FROM players LIMIT 10"));
for (var i = 0; i < rows.length; i++) {
    yzf.info(rows[i].name + " - " + rows[i].score);
}
```

---

### 7.18 yzf.minio — 对象存储 `🟢 稳定`

> **前置条件：** 需要先配置 MinIO 服务连接（详见 [MinIO 连接配置](#7181-minio-连接配置)）。

#### `yzf.minio.putText(serviceId, objectName, text)`

```javascript
yzf.minio.putText("minio-main", "logs/server.txt", "Hello World!");
```

---

## 外部服务连接配置指南

所有外部服务（Redis、MinIO、MySQL、MariaDB、SQLite、RemoteHTTP）都通过 `.hjson` 配置文件统一管理。

### 配置文件位置

```
<server-data>/yzf/config/services/<serviceId>.hjson
```

> `<server-data>` 是服务端运行时的数据目录（通常与 `server-release.jar` 同级）。文件名（去掉 `.hjson` 后缀）即为 `serviceId`，模块脚本中通过此 ID 引用服务。

### 通用字段一览

所有服务类型共享以下字段：

| 字段                 | 类型       | 必填    | 默认值            | 说明                                                                     |
| ------------------ | -------- | ----- | -------------- | ---------------------------------------------------------------------- |
| `id`               | string   | 否     | 文件名            | 服务唯一标识，通常与文件名一致                                                        |
| `type`             | string   | **是** | —              | 服务类型：`redis`, `mysql`, `mariadb`, `sqlite`, `minio`, `remotehttp`      |
| `enabled`          | boolean  | 否     | `true`         | 是否启用                                                                   |
| `clusterMode`      | string   | 否     | `"standalone"` | 集群模式：`standalone`, `replication`, `sentinel`, `cluster`, `loadbalance` |
| `endpoint`         | string   | 因类型而异 | `""`           | 主节点地址                                                                  |
| `nodes`            | string[] | 否     | `[]`           | 多节点地址列表（用于集群/哨兵/负载均衡模式）                                                |
| `username`         | string   | 否     | `""`           | 用户名                                                                    |
| `password`         | string   | 否     | `""`           | 密码                                                                     |
| `connectTimeoutMs` | int      | 否     | `10000`        | 连接超时（毫秒）                                                               |
| `readTimeoutMs`    | int      | 否     | `15000`        | 读取超时（毫秒）                                                               |
| `options`          | string[] | 否     | `[]`           | 额外选项，格式为 `"key=value"` 字符串数组                                           |

### 服务管理命令

```
yzf services                    # 列出所有已配置的服务及其状态
yzf service info <serviceId>    # 查看指定服务的详细信息（连接状态、健康检查等）
yzf service reload              # 重新加载所有服务配置（需要重启生效）
```

---

### 7.16.1 Redis 连接配置

#### 单机模式（standalone）

创建文件 `<server-data>/yzf/config/services/redis-main.hjson`：

```hjson
{
    id: "redis-main"
    type: "redis"
    enabled: true
    clusterMode: "standalone"

    // Redis 地址，格式：host:port
    // 也支持 redis://host:port 或 rediss://host:port（TLS）
    // 留空时默认连接 127.0.0.1:6379
    endpoint: "127.0.0.1:6379"

    // 认证（留空表示无密码）
    username: ""
    password: ""

    // 超时设置
    connectTimeoutMs: 10000
    readTimeoutMs: 15000

    // 额外选项
    options: [
        "db=0"            // Redis 数据库编号，默认 0
    ]
}
```

#### 哨兵模式（sentinel）

```hjson
{
    id: "redis-sentinel"
    type: "redis"
    clusterMode: "sentinel"

    // 哨兵节点地址列表（不是 Redis 主节点地址）
    nodes: [
        "127.0.0.1:26379"
        "127.0.0.1:26380"
        "127.0.0.1:26381"
    ]

    password: "your-redis-password"

    options: [
        "masterName=mymaster"   // 哨兵监控的主节点名称，默认 "mymaster"
        "db=0"
    ]
}
```

#### 集群模式（cluster）

```hjson
{
    id: "redis-cluster"
    type: "redis"
    clusterMode: "cluster"

    // 集群节点地址列表（只需填部分节点，客户端会自动发现全部）
    nodes: [
        "127.0.0.1:7000"
        "127.0.0.1:7001"
        "127.0.0.1:7002"
    ]

    password: ""
}
```

#### 负载均衡/复制模式（replication / loadbalance）

```hjson
{
    id: "redis-multi"
    type: "redis"
    clusterMode: "replication"

    // 多节点轮询，每个节点独立连接池
    nodes: [
        "127.0.0.1:6379"
        "192.168.1.10:6379"
    ]

    password: ""
    options: ["db=0"]
}
```

#### 脚本中使用

```javascript
yzf.onEnable(function() {
    if (!yzf.service.has("redis-main")) {
        yzf.err("Redis 服务未就绪！请检查配置文件。");
        return;
    }

    // 写入
    yzf.redis.set("redis-main", "server:starttime", String(java.lang.System.currentTimeMillis()));

    // 读取
    var val = yzf.redis.get("redis-main", "server:starttime");
    yzf.info("服务器启动时间: " + val);

    // 计数器
    var count = yzf.redis.incr("redis-main", "server:restart:count");
    yzf.info("服务器重启次数: " + count);

    // Hash 操作
    yzf.redis.hset("redis-main", "player:stats", "maxOnline", "50");
    var maxOnline = yzf.redis.hget("redis-main", "player:stats", "maxOnline");
});
```

#### 连接排错

| 症状                                    | 可能原因           | 解决方案                                    |
| ------------------------------------- | -------------- | --------------------------------------- |
| `Connection refused`                  | Redis 未启动或地址错误 | 检查 `endpoint` 中的 host:port，确认 Redis 已启动 |
| `NOAUTH Authentication required`      | 需要密码但未配置       | 填写 `password` 字段                        |
| `WRONGPASS invalid username-password` | 用户名或密码错误       | 检查 `username` 和 `password`              |
| `CLUSTERDOWN`                         | 集群不可用          | 检查集群节点状态，确认 `nodes` 列表正确                |
| 连接超时                                  | 网络不通或防火墙       | 检查 `connectTimeoutMs`，确认端口可访问           |

---

### 7.17.1 MySQL/MariaDB 连接配置

#### MySQL 单机模式

创建文件 `<server-data>/yzf/config/services/my-db.hjson`：

```hjson
{
    id: "my-db"
    type: "mysql"
    enabled: true
    clusterMode: "standalone"

    // 数据库地址，格式：host:port
    // 留空时默认 127.0.0.1:3306
    endpoint: "127.0.0.1:3306"

    // 数据库名（必填）
    database: "mindustry"

    // 认证
    username: "root"
    password: "your-password"

    // 超时
    connectTimeoutMs: 10000

    // JDBC 额外参数（会拼接到 URL 查询参数中，同时也是 HikariCP 数据源属性）
    options: [
        "useSSL=false"
        "serverTimezone=UTC"
        "characterEncoding=utf8mb4"
        "allowPublicKeyRetrieval=true"
    ]
}
```

> **连接池：** 单机模式默认连接池大小为 8。池名格式为 `YZF-{serviceId}-{endpoint}`。

#### MariaDB 复制模式

```hjson
{
    id: "mariadb-ha"
    type: "mariadb"
    clusterMode: "replication"
    database: "mindustry"
    username: "app"
    password: "your-password"

    // 多节点地址
    nodes: [
        "db-master:3306"
        "db-slave:3306"
    ]

    options: ["useSSL=false"]
}
```

#### 脚本中使用

```javascript
yzf.onEnable(function() {
    if (!yzf.service.has("my-db")) {
        yzf.err("数据库服务未就绪！");
        return;
    }

    // 建表（首次运行时）
    yzf.sql.execute("my-db",
        "CREATE TABLE IF NOT EXISTS player_scores (" +
        "  id INT AUTO_INCREMENT PRIMARY KEY," +
        "  uuid VARCHAR(64) NOT NULL," +
        "  name VARCHAR(128)," +
        "  score INT DEFAULT 0," +
        "  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
        ")"
    );

    // 查询
    var count = yzf.sql.queryFirstCell("my-db", "SELECT COUNT(*) FROM player_scores");
    yzf.info("玩家记录数: " + count);

    // 插入/更新
    yzf.sql.execute("my-db",
        "INSERT INTO player_scores (uuid, name, score) VALUES ('abc-123', 'TestPlayer', 100) " +
        "ON DUPLICATE KEY UPDATE score = 100"
    );

    // 查询为 JSON
    var rows = JSON.parse(yzf.sql.queryJson("my-db", "SELECT * FROM player_scores ORDER BY score DESC LIMIT 10"));
    for (var i = 0; i < rows.length; i++) {
        yzf.info((i + 1) + ". " + rows[i].name + " - " + rows[i].score);
    }
});
```

#### 连接排错

| 症状                            | 可能原因          | 解决方案                                            |
| ----------------------------- | ------------- | ----------------------------------------------- |
| `Communications link failure` | 数据库未启动或地址错误   | 检查 `endpoint`，确认 MySQL/MariaDB 已启动且端口可访问        |
| `Access denied for user`      | 用户名或密码错误      | 检查 `username` 和 `password`                      |
| `Unknown database`            | 数据库不存在        | 确认 `database` 字段指向的库已创建                         |
| `Public Key Retrieval` 报错     | MySQL 8+ 认证方式 | 在 `options` 中加 `"allowPublicKeyRetrieval=true"` |
| `SSL` 相关报错                    | SSL 配置问题      | 在 `options` 中加 `"useSSL=false"`（开发环境）           |

---

### 7.17.2 SQLite 连接配置

SQLite 无需外部数据库服务，数据存储为本地文件。

创建文件 `<server-data>/yzf/config/services/local-db.hjson`：

```hjson
{
    id: "local-db"
    type: "sqlite"
    enabled: true

    // 数据库文件路径（相对于服务端根目录）
    // 留空时默认为 config/yzf/config/services/{id}.sqlite.db
    databaseFile: "config/yzf/data/local.sqlite.db"
}
```

> **注意：** SQLite 连接池大小固定为 1（单连接），不适合高并发场景。适合轻量级本地数据存储。

#### 脚本中使用

```javascript
yzf.onEnable(function() {
    yzf.sql.execute("local-db",
        "CREATE TABLE IF NOT EXISTS logs (id INTEGER PRIMARY KEY AUTOINCREMENT, msg TEXT, ts INTEGER)"
    );

    yzf.sql.execute("local-db",
        "INSERT INTO logs (msg, ts) VALUES ('server started', " + java.lang.System.currentTimeMillis() + ")"
    );

    var count = yzf.sql.queryFirstCell("local-db", "SELECT COUNT(*) FROM logs");
    yzf.info("日志条数: " + count);
});
```

---

### 7.18.1 MinIO 连接配置

创建文件 `<server-data>/yzf/config/services/minio-main.hjson`：

```hjson
{
    id: "minio-main"
    type: "minio"
    enabled: true

    // MinIO 服务地址（必须带协议前缀）
    endpoint: "http://127.0.0.1:9000"

    // 认证凭证
    // MinIO 默认管理员：accessKey=minioadmin, secretKey=minioadmin
    accessKey: "minioadmin"
    secretKey: "minioadmin"

    // 默认存储桶名称
    // 如果该桶不存在，服务启动时会自动创建
    bucket: "mindustry"
}
```

> **endpoint 格式要求：** 必须包含协议前缀 `http://` 或 `https://`。例如 `http://127.0.0.1:9000`，不要写成 `127.0.0.1:9000`。

#### 脚本中使用

```javascript
yzf.onEnable(function() {
    if (!yzf.service.has("minio-main")) {
        yzf.err("MinIO 服务未就绪！");
        return;
    }

    // 上传文本对象
    yzf.minio.putText("minio-main", "logs/server.txt", "服务器日志内容");

    // 上传 JSON 数据
    var stats = JSON.stringify({
        players: yzf.player.count(),
        wave: yzf.game.wave(),
        tps: yzf.game.tps()
    });
    yzf.minio.putText("minio-main", "stats/realtime.json", stats);
});
```

#### 连接排错

| 症状                      | 可能原因          | 解决方案                            |
| ----------------------- | ------------- | ------------------------------- |
| `Connection refused`    | MinIO 未启动     | 确认 MinIO 服务已运行，检查 `endpoint` 地址 |
| `Invalid endpoint`      | endpoint 格式错误 | 必须带 `http://` 或 `https://` 前缀   |
| `Access Denied`         | 认证失败          | 检查 `accessKey` 和 `secretKey`    |
| `Bucket does not exist` | 桶未自动创建        | 确认 `bucket` 字段已填写，重启服务让其自动创建    |

---

### 7.19.1 RemoteHTTP 连接配置

RemoteHTTP 是无状态的 HTTP 客户端，不需要维护连接池。

#### 基础配置

创建文件 `<server-data>/yzf/config/services/my-api.hjson`：

```hjson
{
    id: "my-api"
    type: "remotehttp"
    enabled: true

    // API 基础 URL（请求时 path 会拼接到此 URL 后）
    endpoint: "https://api.example.com"

    // 超时
    connectTimeoutMs: 5000
    readTimeoutMs: 30000
}
```

#### 带代理和负载均衡

```hjson
{
    id: "proxied-api"
    type: "remotehttp"
    endpoint: "https://external-api.com"

    // 多节点轮询（请求会轮流发到不同节点）
    nodes: [
        "https://node1.api.com"
        "https://node2.api.com"
    ]

    // HTTP 代理
    options: [
        "proxyHost=127.0.0.1"
        "proxyPort=8080"
    ]
}
```

#### 脚本中使用

```javascript
yzf.onEnable(function() {
    if (!yzf.service.has("my-api")) {
        yzf.err("远程 API 未配置！");
        return;
    }

    // GET 请求
    var status = yzf.remote.get("my-api", "/status");
    yzf.info("API 状态: " + status);

    // POST JSON 请求
    var result = yzf.remote.postJson("my-api", "/submit", JSON.stringify({
        serverName: "My Server",
        players: yzf.player.count(),
        wave: yzf.game.wave()
    }));
    yzf.info("提交结果: " + result);
});
```

---

### 完整配置示例：多服务组合

以下是一个同时使用 Redis + MySQL + MinIO 的完整配置方案：

**Redis — 缓存和实时数据：**

```hjson
// 文件: redis-main.hjson
{
    id: "redis-main"
    type: "redis"
    endpoint: "127.0.0.1:6379"
    password: ""
    options: ["db=0"]
}
```

**MySQL — 持久化存储：**

```hjson
// 文件: my-db.hjson
{
    id: "my-db"
    type: "mysql"
    endpoint: "127.0.0.1:3306"
    database: "mindustry"
    username: "root"
    password: "your-password"
    options: ["useSSL=false", "serverTimezone=UTC"]
}
```

**MinIO — 文件存储：**

```hjson
// 文件: minio-main.hjson
{
    id: "minio-main"
    type: "minio"
    endpoint: "http://127.0.0.1:9000"
    accessKey: "minioadmin"
    secretKey: "minioadmin"
    bucket: "mindustry"
}
```

**模块脚本中组合使用：**

```javascript
yzf.onEnable(function() {
    // 检查所有服务
    var redisOk = yzf.service.has("redis-main");
    var dbOk = yzf.service.has("my-db");
    var minioOk = yzf.service.has("minio-main");

    yzf.info("Redis: " + (redisOk ? "✓" : "✗"));
    yzf.info("MySQL: " + (dbOk ? "✓" : "✗"));
    yzf.info("MinIO: " + (minioOk ? "✓" : "✗"));

    if (!redisOk || !dbOk) {
        yzf.warn("部分服务未就绪，某些功能可能不可用");
    }
});
```

---

### 7.19 yzf.ws — WebSocket `🟢 稳定`

#### `yzf.ws.connect(url, onOpen, onMessage, onClose, onError)` → string

建立 WebSocket 连接，返回连接 ID。

**回调函数签名：**

- `onOpen(connectionId)` — 连接建立
- `onMessage(connectionId, message)` — 收到消息
- `onClose(connectionId, statusCode, reason)` — 连接关闭
- `onError(connectionId, error)` — 连接错误

```javascript
yzf.onEnable(function() {
    var wsId = yzf.ws.connect("wss://echo.websocket.org",
        function(id) {
            yzf.info("WebSocket 已连接: " + id);
            yzf.ws.send(id, "Hello Server!");
        },
        function(id, msg) {
            yzf.info("收到: " + msg);
        },
        function(id, code, reason) {
            yzf.info("WebSocket 关闭: " + code + " " + reason);
        },
        function(id, err) {
            yzf.err("WebSocket 错误: " + err);
        }
    );

    yzf.onDisable(function() {
        if (wsId) yzf.ws.close(wsId);
    });
});
```

#### `yzf.ws.send(connectionId, message)` → boolean

发送文本消息。

#### `yzf.ws.sendBinary(connectionId, base64Data)` → boolean

发送二进制数据（base64 编码）。

#### `yzf.ws.close(connectionId)`

关闭连接。

#### `yzf.ws.isOpen(connectionId)` → boolean

检查连接是否打开。

#### `yzf.ws.list()` → string (JSON)

列出所有连接。

```javascript
var conns = JSON.parse(yzf.ws.list());
for (var i = 0; i < conns.length; i++) {
    yzf.info("连接: " + conns[i].id + " url=" + conns[i].url + " open=" + conns[i].open);
}
```

---

### 7.20 yzf.comid — comid 系统 `🟡 实验性`

> **稳定性说明：** comid 系统是 YZF 的自有设计，非 Mindustry 原生功能。当前实现已可用于生产，但短 ID 的位数扩展策略、注册表存储格式在未来版本中可能存在调整。建议在关键数据中同时持久化 UUID 作为冗余键。

comid 是玩家 UUID 的短数字标识，从 5 位数（10000-99999）开始分配，用完后自动扩展到 6 位、7 位...

#### `yzf.comid.get(uuid)` → long

获取 comid，不存在返回 -1。

```javascript
var comid = yzf.comid.get("player-uuid-here");
if (comid >= 0) {
    yzf.info("comid: " + comid);
}
```

#### `yzf.comid.getOrCreate(uuid)` → long

获取或创建 comid。

```javascript
var comid = yzf.comid.getOrCreate("player-uuid-here");
```

#### `yzf.comid.uuid(comid)` → string | null

通过 comid 获取 UUID。

```javascript
var uuid = yzf.comid.uuid(12345);
```

#### `yzf.comid.exists(comid)` → boolean

检查 comid 是否存在。

#### `yzf.comid.digits()` → int

当前 comid 位数。

#### `yzf.comid.remaining()` → long

当前位数剩余可用数量。

#### `yzf.comid.total()` → int

总注册数。

---

### 7.21 yzf.data — 玩家数据持久化 `🟢 稳定`

> **依赖说明：** 此 API 依赖 comid 系统（`🟡 实验性`），但 yzf.data 本身的读写接口已稳定。数据以 JSON 文件形式存储在 `data/player-data/{comid}.json`，即使 comid 格式变更，已有数据文件不会丢失。

以 comid 为键的玩家级 KV 存储，数据持久化到 `data/player-data/{comid}.json`。

#### `yzf.data.get(comid, key, defaultValue?)` → string | null

```javascript
var name = yzf.data.get(12345, "nickname", "未命名");
```

#### `yzf.data.set(comid, key, value)`

```javascript
yzf.data.set(12345, "nickname", "CoolPlayer");
```

#### `yzf.data.getInt(comid, key, defaultValue?)` → int

```javascript
var score = yzf.data.getInt(12345, "score", 0);
```

#### `yzf.data.setInt(comid, key, value)`

```javascript
yzf.data.setInt(12345, "score", 100);
```

#### `yzf.data.getBool(comid, key, defaultValue?)` → boolean

```javascript
var vip = yzf.data.getBool(12345, "vip", false);
```

#### `yzf.data.setBool(comid, key, value)`

```javascript
yzf.data.setBool(12345, "vip", true);
```

#### `yzf.data.getDouble(comid, key, defaultValue?)` → double

```javascript
var exp = yzf.data.getDouble(12345, "exp", 0.0);
```

#### `yzf.data.setDouble(comid, key, value)`

```javascript
yzf.data.setDouble(12345, "exp", 99.5);
```

#### `yzf.data.all(comid)` → string (JSON)

获取玩家所有数据。

```javascript
var all = JSON.parse(yzf.data.all(12345));
yzf.info(JSON.stringify(all));
```

#### `yzf.data.remove(comid, key)`

删除指定键。

#### `yzf.data.clear(comid)`

清空玩家所有数据。

**完整示例 — 玩家积分系统：**

```javascript
yzf.onEnable(function() {
    yzf.command("score", "[player]", "查看积分", function(args) {
        if (args.length > 0) {
            var p = yzf.player.find(String(args[0]));
            if (p) {
                var info = JSON.parse(p);
                var comid = yzf.comid.getOrCreate(info.uuid);
                var score = yzf.data.getInt(comid, "score", 0);
                yzf.info(info.name + " 的积分: " + score);
            }
        } else {
            yzf.info("用法: score <player>");
        }
    });

    yzf.command("addscore", "<player> <amount>", "添加积分", function(args) {
        if (args.length < 2) { yzf.info("用法: addscore <player> <amount>"); return; }
        var p = yzf.player.find(String(args[0]));
        if (!p) { yzf.info("找不到玩家"); return; }
        var info = JSON.parse(p);
        var comid = yzf.comid.getOrCreate(info.uuid);
        var amount = parseInt(String(args[1]));
        var current = yzf.data.getInt(comid, "score", 0);
        yzf.data.setInt(comid, "score", current + amount);
        yzf.info(info.name + " 积分 +" + amount + " (当前: " + (current + amount) + ")");
    });

    // 玩家加入时显示积分
    yzf.on("PlayerJoin", function(event) {
        var p = event.player;
        var comid = yzf.comid.getOrCreate(p.uuid());
        var score = yzf.data.getInt(comid, "score", 0);
        yzf.player.send(p.id, "[cyan]你的积分: " + score);
    });

    // 每波增加在线玩家积分
    yzf.on("WaveEvent", function(event) {
        var players = JSON.parse(yzf.player.list());
        for (var i = 0; i < players.length; i++) {
            var comid = yzf.comid.getOrCreate(players[i].uuid);
            var current = yzf.data.getInt(comid, "score", 0);
            yzf.data.setInt(comid, "score", current + 10);
        }
    });
});
```

---

### 7.22 yzf.module — 跨模块通信 `🟡 实验性`

> **稳定性说明：** 跨模块通信（export/call）是 YZF 的高级特性。当前 API 签名已基本稳定，但模块间依赖解析顺序、导出函数的生命周期边界（模块重载时的行为）仍在完善中。建议：不要在导出函数中持有对模块局部变量的强引用；调用前始终用 `yzf.module.exported()` 检查目标函数是否存在。

#### `yzf.module.export(fnName, callback)`

导出函数供其他模块调用。

```javascript
yzf.module.export("greet", function(name) {
    return "Hello, " + name + "!";
});

yzf.module.export("calculateDamage", function(baseDamage, multiplier) {
    return baseDamage * multiplier;
});
```

#### `yzf.module.call(moduleId, fnName, ...args)` → any

调用其他模块的导出函数。

```javascript
// 调用另一个模块的导出函数
var result = yzf.module.call("author/other-module", "greet", "World");
yzf.info("结果: " + result);
```

#### `yzf.module.exported(moduleId)` → string (JSON)

列出指定模块的导出函数名。

```javascript
var fns = JSON.parse(yzf.module.exported("author/other-module"));
yzf.info("导出函数: " + fns.join(", "));
```

#### `yzf.module.list()` → string (JSON)

列出所有已加载模块。

```javascript
var modules = JSON.parse(yzf.module.list());
for (var i = 0; i < modules.length; i++) {
    yzf.info("模块: " + modules[i]);
}
```

#### `yzf.module.info(moduleId)` → string (JSON) | null

获取模块信息。

```javascript
var info = JSON.parse(yzf.module.info("author/my-module"));
if (info) {
    yzf.info("名称: " + info.name + " 版本: " + info.version + " 运行时: " + info.runtime);
}
```

---

### 7.23 yzf.db — JSON 数据库 `🟢 稳定`

> 统一的 JSON 数据库层，支持本地文件存储和远程存储。比 `yzf.data`（仅限 comid 玩家数据）更通用——可存储任意分类的键值数据，支持导入导出。

#### `yzf.db.list()` → string (JSON)

列出所有已注册的数据库 ID。

```javascript
var dbs = JSON.parse(yzf.db.list());
yzf.info("已注册数据库: " + dbs.join(", "));
```

#### `yzf.db.has(id)` → boolean

检查数据库是否存在。

#### `yzf.db.info(id)` → string (JSON) | null

获取数据库详细信息。

#### `yzf.db.addLocal(id, name)` → boolean

添加一个本地 JSON 文件数据库。

```javascript
yzf.db.addLocal("server-data", "服务器数据");
```

#### `yzf.db.addRemote(id, name, endpoint, serviceId, readOnly)` → boolean

添加一个远程 JSON 数据库。

```javascript
yzf.db.addRemote("shared-data", "共享数据", "/api/db", "my-api", false);
```

#### `yzf.db.remove(id)` → boolean

移除一个数据库。

#### `yzf.db.categories(id)` → string (JSON)

列出数据库中所有分类。

```javascript
var cats = JSON.parse(yzf.db.categories("server-data"));
yzf.info("分类: " + cats.join(", "));
```

#### `yzf.db.keys(id, category)` → string (JSON)

列出指定分类下的所有键。

#### `yzf.db.get(id, category, key, defaultValue?)` → string

读取数据库条目。

```javascript
var val = yzf.db.get("server-data", "settings", "motd", "欢迎来到服务器");
```

#### `yzf.db.set(id, category, key, value)` → void

写入数据库条目。`value` 为 JSON 字符串。

```javascript
yzf.db.set("server-data", "settings", "motd", JSON.stringify("新公告内容"));
yzf.db.set("server-data", "scores", "top-player", JSON.stringify({name: "Alice", score: 999}));
```

#### `yzf.db.removeEntry(id, category, key)` → boolean

删除一个数据库条目。

#### `yzf.db.dump(id)` → string (JSON)

导出整个数据库为 JSON 字符串。

```javascript
var dump = yzf.db.dump("server-data");
yzf.minio.putText("minio-main", "backups/db-export.json", dump);
```

#### `yzf.db.import(id, json)` → void

从 JSON 字符串导入数据（合并覆盖）。

#### `yzf.db.defaultId()` → string

获取默认数据库 ID。

#### `yzf.db.count()` → int

已注册数据库数量。

---

### 7.24 yzf.commands — 可调用命令注册表 `🟡 实验性`

> 模块间可互相调用的命令注册系统。与 `yzf.command`（控制台命令）不同，这里注册的命令可通过 `yzf.commands.call()` 从脚本中程序化调用，适合构建模块间的 RPC 机制。

#### `yzf.commands.register(name, description, callback)`

注册一个可调用命令。

```javascript
yzf.commands.register("getScore", "获取玩家积分", function(comid) {
    return yzf.data.getInt(comid, "score", 0);
});
```

#### `yzf.commands.unregister(name)` → void

注销一个可调用命令。

#### `yzf.commands.has(name)` → boolean

检查命令是否已注册。

#### `yzf.commands.call(name, ...args)` → any

调用一个已注册的可调用命令。

```javascript
var score = yzf.commands.call("getScore", 12345);
yzf.info("积分: " + score);
```

#### `yzf.commands.run(commandName, ...args)` → boolean

执行一条服务端控制台命令（等同于在终端中输入）。

```javascript
yzf.commands.run("status");
```

#### `yzf.commands.list()` → string (JSON)

列出所有已注册的可调用命令。

#### `yzf.commands.listModule(moduleId)` → string (JSON)

列出指定模块注册的可调用命令。

---

### 7.25 yzf.mod — Mod 命令桥接 `🟢 稳定`

> 与 `yzf.command`/`yzf.playerCommand`/`yzf.adminCommand` 平行的另一套命令注册接口。功能等价，但使用 `mod` 前缀命名，语义上更明确地标识为"Mod 层命令"。两者注册的命令共享同一个命名空间，不可重名。

#### `yzf.mod.registerServerCommand(name, usage, description, callback)`

注册控制台命令（等同于 `yzf.command`）。

#### `yzf.mod.registerPlayerCommand(name, usage, description, callback)`

注册玩家命令（等同于 `yzf.playerCommand`，adminOnly=false）。

#### `yzf.mod.registerAdminCommand(name, usage, description, permission, callback)`

注册管理员命令（等同于 `yzf.adminCommand`）。

#### `yzf.mod.registerCallableCommand(name, description, callback)`

注册可调用命令（等同于 `yzf.commands.register`）。

#### `yzf.mod.unregisterCommand(name)` → boolean

注销命令。

#### `yzf.mod.listCommands()` → string (JSON)

列出本模块注册的所有命令。

#### `yzf.mod.hasCommand(name)` → boolean

检查命令是否存在。

---

### 7.26 yzf.runtime — 运行时控制 `🟢 稳定`

> 模块自省和运行时控制接口。可用于查看当前运行时模式、检查文件监听状态、触发热重载等。

#### `yzf.runtime.mode()` → string

获取当前运行时模式标识。

```javascript
yzf.info("运行时: " + yzf.runtime.mode()); // "js", "node", "java", "kt", "kts"
```

#### `yzf.runtime.watcherRunning()` → boolean

文件监听器是否正在运行。

```javascript
if (!yzf.runtime.watcherRunning()) {
    yzf.warn("文件监听器未运行，热重载不可用");
}
```

#### `yzf.runtime.reloadSelf()` → void

请求延迟重载当前模块。

```javascript
yzf.command("self-reload", "重载自身模块", function(args) {
    yzf.info("正在重载...");
    yzf.runtime.reloadSelf();
});
```

#### `yzf.runtime.reloadModule(moduleId)` → void

请求延迟重载指定模块。

```javascript
yzf.runtime.reloadModule("author/other-module");
```

#### `yzf.runtime.reloadAll()` → void

请求延迟重载所有模块。

---

### 7.27 yzf.openapi — API 自省 `🟢 稳定`

> 面向外部调用者（如 HTTP 网关、管理面板）的 API 能力发现接口。模块开发者一般不需要直接使用。

#### `yzf.openapi.manifest()` → string (JSON)

完整能力清单。

#### `yzf.openapi.list()` → string (JSON)

所有能力分组。

#### `yzf.openapi.info(capabilityId)` → string (JSON) | null

单个能力分组详情。

#### `yzf.openapi.summary()` → string (JSON)

精简能力摘要。

#### `yzf.openapi.readOnly()` → string (JSON)

只读能力分组。

#### `yzf.openapi.writeOnly()` → string (JSON)

只写能力分组。

---

### 7.28 yzf.response — 响应体构建 `🟢 稳定`

> 纯 JS 工具函数，用于构建标准化的 JSON 响应体，不涉及桥接调用。适合在 HTTP 接口或跨模块返回值中使用。

#### `yzf.response.ok(code, message, data)` → JS object

#### `yzf.response.fail(code, message, data)` → JS object

```javascript
var resp = yzf.response.ok(200, "操作成功", {score: 100});
// {ok: true, success: true, code: 200, message: "操作成功", data: {score: 100}, timestampMs: ...}

var err = yzf.response.fail(404, "玩家不存在", null);
// {ok: false, success: false, code: 404, message: "玩家不存在", data: null, timestampMs: ...}
```

**返回值类型说明：** 返回的是 Rhino 原生 JS 对象（`NativeObject`），不是 Java 对象。可以直接访问属性（`resp.ok`、`resp.data`），也可以直接传给 `JSON.stringify(resp)` 序列化。通过 `yzf.module.call` 跨模块传递时，调用方拿到的也是原生 JS 对象，无需额外转换。

> **注意区分：** Java 源码中有一个 `YZFResponse` 类（返回 `Jval` JSON 字符串），与这里的 `yzf.response` JS 函数是完全独立的两套东西。`yzf.world.batchSpawn` 等桥接方法返回的 JSON 字符串是 Java 侧 `YZFResponse` 的产物，需要用 `JSON.parse()` 解析；而 `yzf.response.ok()` 返回的是 JS 对象，不需要 parse。

---

### 7.29 yzf.memory — 内存区与进程隔离 `🟡 实验性`

> YZF 的\*\*多内存区（Memory Region）\*\*机制，用于把插件按隔离级别运行在不同的执行环境中，是本框架相对上游 Mindustry 的一项高级能力。底层实现见 `YZFMemoryRegionManager` / `YZFMemoryRegion`。
>
> - 默认区 **`YF1`** 始终存在（原始服务端区），**不可停止**。
> - 插件可创建 `YF2`、`YF3`… 等新区，支持三种隔离模式。
> - 是否允许插件创建新区由配置项 `allowPluginCreateRegion` 控制（默认 `true`）。

**隔离模式（mode）：**

| mode          | 说明                     | 备注                                         |
| ------------- | ---------------------- | ------------------------------------------ |
| `logical`     | 逻辑区，共享当前 JVM           | 最轻量，默认模式                                   |
| `classloader` | 独立 `URLClassLoader` 隔离 | 可通过 `load()` 动态加载 JAR                      |
| `process`     | 独立子进程（新 JVM）           | 入口 `YZFRegionProcessMain`，支持独立 `-Xms/-Xmx` |

**配置文件** `config/memory-regions.hjson`（首次启动自动生成）：

```hjson
{
  defaultIsolation: "classloader"   # 新区默认隔离级别
  allowPluginCreateRegion: true     # 是否允许插件通过 API 创建新区
}
```

#### 服务端级进程隔离机制

除了脚本 API，内存区的实际行为由 `runtime.hjson` 与 `memory-regions.hjson` 共同决定（相关开关见 [7.26 yzf.runtime](#726-yzfruntime--运行时控制)）：

- **默认隔离级别 `defaultIsolation`**：可选 `classloader` / `process` / `logical` / `auto`，脚本未显式指定 `mode` 时采用此默认值（默认 `classloader`）。
- **类加载器隔离开关 `classLoaderIsolationEnabled`**：开启后每个模块使用独立 `URLClassLoader`，避免类冲突 / 污染。
- **内存策略 `memoryPolicy`**：`enabled` 打开后，对 `node` / `java` / `kt` / `kts` 等进程型运行时按 `defaultMin` / `defaultMax`（或 `forceProcess` 强制进程隔离）统一施加堆上限，等价于每个进程区自带 `-Xms/-Xmx`。
- **冷加载 `coldLoad`**：`reloadStrategy: "cold"` 时按 `defaultIsolation` 重新隔离后加载；`allowPluginCreateRegion` 控制是否允许脚本通过 `yzf.memory.create(...)` 新建区域。

**内存区生命周期状态机**（`YZFMemoryRegion.State`）：

| 状态         | 含义                                      |
| ---------- | --------------------------------------- |
| `created`  | 已创建，尚未启动                                |
| `starting` | 进程 / 类加载器启动中                            |
| `active`   | 运行中（进程区含 `pid` 字段）                      |
| `draining` | 正在优雅停止：先向子进程写 `shutdown`，再销毁进程 / 关闭类加载器 |
| `stopped`  | 已停止                                     |
| `failed`   | 启动或运行失败，`lastError` 记录原因                |

**进程型区域的托管**：`process` 模式的子进程由 `YZFProcessRuntime` 管理，父进程通过 **JSON 行协议**（`YZFProtocolHost` 经子进程 stdout/stdin 收发 `YZFProtocolMessage`）进行管控——命令注册、玩家命令、事件回调均可跨进程代理。子进程异常退出会被自动清理并写入审计日志（`module-stop` / `module-start`）。详见 [第 18 章 网络配置与进程隔离](#18-服务端架构网络配置与进程隔离)。

#### `yzf.memory.jvm()` → object

返回当前 JVM 内存快照：

```javascript
var m = yzf.memory.jvm();
// { heapUsed, heapCommitted, heapMax, heapFree, nonHeapUsed,
//   inputArguments: [...], xms, xmx }
yzf.info("堆已用: " + Math.round(m.heapUsed / 1048576) + " MB");
```

#### `yzf.memory.list()` → array

返回所有内存区的快照数组（含 `YF1`）。

#### `yzf.memory.info(id)` → object | null

返回指定内存区的快照，不存在时返回 `null`。

#### `yzf.memory.create(id, mode, minHeap, maxHeap)` → object

创建一个新内存区并返回其快照。`minHeap`/`maxHeap` 支持带单位的字符串（`K`/`M`/`G`，如 `"256M"`、`"1G"`），仅 `process` 模式会实际作用于子进程 JVM。若 `id` 已存在或创建失败会抛异常；若 `allowPluginCreateRegion` 为 `false` 也会抛异常。

```javascript
// 逻辑区
yzf.memory.create("YF2", "logical", "", "");
// 独立进程区，限制堆内存
yzf.memory.create("YF3", "process", "128M", "512M");
```

#### `yzf.memory.load(regionId, jarPath, className)` → string

**仅对 `classloader` 模式的区有效**：为该区替换/加载一个 JAR，并可选地预加载 `className`。返回加载的类名（`className` 为空时返回空串）。

```javascript
yzf.memory.create("plug", "classloader", "", "");
yzf.memory.load("plug", "/data/plugins/extra.jar", "com.example.Entry");
```

#### `yzf.memory.stop(id)` → boolean

停止并移除一个内存区。对 `YF1` 调用始终返回 `false`（受保护）。

---

### 7.30 yzf.status — 服务端状态快照 `🟢 稳定`

> 一次性获取**结构化的服务端全景状态**，非常适合做监控面板、健康检查或对外状态接口。底层实现见 `YZFStatusUi`，与服务端命令 `yzf status`、稳定 API `server.status` 返回同源数据。

#### `yzf.status.snapshot()` → object

返回完整状态对象。主要字段（部分字段仅在开局/有上下文时出现）：

| 分类 | 字段                                                                                                                                                                             |
| -- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 基本 | `ok` `name` `version` `generatedAtMs` `runtimeMode` `watcherRunning` `serverOpen`                                                                                              |
| 性能 | `tps` `tpsLimit` `fps` `heapMb` `jvmMemory` `memoryRegions`                                                                                                                    |
| 在线 | `players` `units` `enemies` `playerList[]`                                                                                                                                     |
| 网络 | `networkUploadBps` `networkDownloadBps` `syncClientSnapshots` `syncDroppedSnapshots` `syncCorrections` `syncRubberbands` `syncForcedReliableSnapshots` `syncLastPositionError` |
| 框架 | `modules` `scripts` `services` `healthyServices` `commands` `runtimeFeatures` `pluginCount` `modulePackageCount` `loadedModules` `processModules` `runtimeModules` `plugins[]` |
| 路径 | `paths`（root/modules/plugins/logs/config/services/remotes/terminal 及各级 error 目录）                                                                                               |
| 地图 | `map`（`name` `wave` `waveTimeSeconds` `isPlaying` `isPaused`，仅在对局中）                                                                                                            |

```javascript
var s = yzf.status.snapshot();
yzf.info("TPS=" + s.tps + " 玩家=" + s.players + " 服务=" + s.services);
```

#### `yzf.status.ui()` → object

返回 **UHD Status UI** 组件描述（`component: "uhd-status-ui"`），已把状态数据整理成 `navbar` / `window` / `metrics` / `sections` 结构，可直接喂给前端渲染。`sections` 内嵌完整的 `snapshot()` 数据。

```javascript
var ui = yzf.status.ui();
// ui.metrics => [{label:"TPS",value:"60",hint:"server tick rate"}, ...]
```

---

### 7.31 yzf.stableApi — 稳定别名 API `🟢 稳定`

> 一层**配置驱动的稳定别名**：把一组固定白名单里的诊断/状态调用映射为稳定的字符串 id，供插件和诊断脚本长期依赖，即使内部实现调整，别名与返回语义保持不变。底层实现见 `YZFStableApi`，**不暴露任意反射目标**，只能调用白名单内的目标。

**内置目标（白名单）：**

| id / target              | 返回           | 说明                         |
| ------------------------ | ------------ | -------------------------- |
| `server.actualTps`       | number       | 实测服务端每秒更新数                 |
| `server.tpsLimit`        | number       | 配置的 TPS 上限                 |
| `server.status`          | string(JSON) | 当前结构化服务端状态（同 `yzf.status`） |
| `server.openApiManifest` | string(JSON) | YZF 能力清单                   |
| `server.openApiSummary`  | string(JSON) | YZF 能力摘要                   |
| `server.playerCount`     | number       | 在线玩家数                      |

**配置文件** `config/stable-api.hjson`：

```hjson
{
  enabled: true
  interfaces: [
    { id: "tps", target: "server.actualTps", description: "服务端 TPS" }
    { id: "online", target: "server.playerCount" }
  ]
}
```

未配置或配置非法时回退到内置默认别名（id 与 target 同名）。服务端启动时还会在同目录生成一个只读的调试脚本，演示 `stableApi.call(...)` 用法。

#### `yzf.stableApi(id)` → value | null

按 id 调用一个稳定别名。id 未启用或不在白名单内返回 `null`。

```javascript
yzf.info("TPS = " + yzf.stableApi("server.actualTps"));
var status = JSON.parse(yzf.stableApi("server.status"));
```

#### `yzf.stableApiManifest()` → string (JSON)

返回当前生效的稳定 API 清单（含 `enabled`、`configPath`、`interfaces[{id,target,description}]`）。

---

### 补充：遗漏的零散方法

#### `yzf.evalFile(path)` → boolean

在当前模块作用域中执行一个 JS 文件。路径相对于模块根目录。

```javascript
yzf.evalFile("scripts/utils.js"); // 加载并执行工具脚本
```

#### `yzf.player.adminByComid(comid, isAdmin)` → boolean

通过 comid 设置/取消管理员（离线玩家也可操作）。

```javascript
yzf.player.adminByComid(12345, true); // 将 comid=12345 的玩家设为管理员
```

#### `yzf.player.infoByComid(comid)` → string (JSON) | null

通过 comid 获取玩家信息（仅限在线玩家）。

```javascript
var info = JSON.parse(yzf.player.infoByComid(12345));
```

#### `yzf.world.batchSpawn(ops)` → string (JSON)

批量生成，接受 JSON 数组或 `{operations:[...]}` 对象。每个操作字段与 `yzf.world.spawn` 相同。

```javascript
var ops = [
    {type: "unit", id: "flare", x: 100, y: 200, size: 1, teamId: 1, buff: null},
    {type: "unit", id: "mono", x: 105, y: 200, size: 2, teamId: 1, buff: null},
    {type: "block", id: "copper-wall", x: 110, y: 200, size: 3, teamId: 1, buff: null}
];
var r = JSON.parse(yzf.world.batchSpawn(JSON.stringify(ops)));
yzf.info("批量生成: " + r.message);
```

#### `yzf.service.summary(serviceId)` → string (JSON) | null

获取服务摘要（比 `info` 更精简，不含配置路径等细节）。

---

### 全局对象参考

> 以下是 Mindustry 脚本引擎自动注入到每个模块作用域的全局对象，非 YZF 特有，但在模块开发中可以直接使用。

| 全局                              | 类型       | 说明                                              |
| ------------------------------- | -------- | ----------------------------------------------- |
| `require(path)`                 | Function | CommonJS 模块加载，路径相对于模块 scripts 目录                |
| `print(text)`                   | Function | 日志输出（等同于 `Vars.mods.scripts.log`）               |
| `run(fn)`                       | Function | 将函数包装为 `java.lang.Runnable`                     |
| `boolf(fn)`                     | Function | 包装为 `Boolf`（单参数返回 boolean）                      |
| `boolp(fn)`                     | Function | 包装为 `Boolp`（无参数返回 boolean）                      |
| `floatf(fn)`                    | Function | 包装为 `Floatf`（单参数返回 float）                       |
| `floatp(fn)`                    | Function | 包装为 `Floatp`（无参数返回 float）                       |
| `cons(fn)`                      | Function | 包装为 `Cons`（单参数消费型）                              |
| `prov(fn)`                      | Function | 包装为 `Prov`（无参数供应型）                              |
| `func(fn)`                      | Function | 包装为 `Func`（单参数转换型）                              |
| `newEffect(lifetime, renderer)` | Function | 创建视觉特效                                          |
| `Call`                          | Java 类   | `mindustry.gen.Call` 的快捷引用，可直接调用 Mindustry 网络方法 |
| `extend(base, overrides)`       | Function | 创建 JavaAdapter 子类（用于继承 Java 类）                  |

**示例 — 使用 `require` 加载共享模块：**

```javascript
// scripts/utils.js
var utils = {
    formatTime: function(ms) {
        return Math.floor(ms / 60000) + "分" + Math.floor((ms % 60000) / 1000) + "秒";
    }
};
return utils;
```

```javascript
// scripts/main.js
var utils = require("./utils");
yzf.info("运行时间: " + utils.formatTime(java.lang.System.currentTimeMillis()));
```

---

## 错误处理与容错指南

> 本节基于源码分析，描述每种 API 调用在失败时的**实际行为**。YZF 对事件、命令、生命周期回调做了防御性包装，但**定时器回调没有**——这是最关键的陷阱。

### 异常传播总表

| 调用上下文                        | 有 catch?             | 异常类型 | 服务器是否存活    | 备注                 |
| ---------------------------- | -------------------- | ---- | ---------- | ------------------ |
| 模块加载（onEnable 内）             | ✅ `catch(Throwable)` | 所有   | ✅ 存活       | 模块加载失败，自动卸载        |
| 事件回调（`yzf.on`）               | ✅ `catch(Throwable)` | 所有   | ✅ 存活       | 事件处理器保持注册，下次事件继续触发 |
| 控制台命令（`yzf.command`）         | ✅ `catch(Throwable)` | 所有   | ✅ 存活       | 命令保持注册，错误仅写入服务端日志  |
| 玩家命令（`yzf.playerCommand`）    | ✅ `catch(Throwable)` | 所有   | ✅ 存活       | 玩家不会收到错误信息，仅日志     |
| 生命周期（`yzf.onDisable`）        | ✅ `catch(Throwable)` | 所有   | ✅ 存活       | —                  |
| 定时器（`yzf.after`/`yzf.every`） | ❌ **无 catch**        | 所有   | ⚠️ **不确定** | 详见下方"定时器陷阱"        |
| `yzf.evalFile`               | ✅ `catch(Throwable)` | 所有   | ✅ 存活       | 返回 `false`，错误写入日志  |

### 各服务调用的失败行为

| 调用                             | 失败时行为                             | 返回值          |
| ------------------------------ | --------------------------------- | ------------ |
| `yzf.redis.*`（Redis 断连）        | 抛出 `JedisException`（未检查异常）        | 无返回，异常向上传播   |
| `yzf.sql.*`（SQL 语法错误）          | 抛出 `SQLException`（检查异常）           | 无返回，异常向上传播   |
| `yzf.sql.*`（serviceId 不存在）     | 抛出 `IllegalStateException`        | 无返回          |
| `yzf.remote.get`（HTTP 4xx/5xx） | **不抛异常**                          | 返回错误响应体（字符串） |
| `yzf.remote.get`（连接超时）         | 抛出 `SocketTimeoutException`       | 无返回，异常向上传播   |
| `yzf.minio.putText`（认证失败）      | 抛出 MinIO `ErrorResponseException` | 无返回，异常向上传播   |
| `yzf.player.info(id)`（玩家不存在）   | **不抛异常**                          | 返回 `null`    |
| `yzf.player.find(name)`（找不到）   | **不抛异常**                          | 返回 `null`    |
| `yzf.content.*`（内容不存在）         | **不抛异常**                          | 返回 `null`    |

### 定时器陷阱 ⚠️

`yzf.after` 和 `yzf.every` 的回调**没有 try/catch 包装**（源码中只有 `try/finally` 保证 `Context.exit()`）。如果回调内抛出异常：

- 异常会传播到 arc `Timer` 内部
- 具体行为取决于 arc 库实现——可能崩溃，可能静默吞掉，可能终止该定时器
- 与其他回调（事件、命令）的防御性行为**不一致**

**建议：** 在定时器回调内始终用 try/catch 包裹业务逻辑：

```javascript
yzf.every(0, 10, function() {
    try {
        // 你的业务逻辑
        var val = yzf.redis.get("redis-main", "some-key");
        yzf.info("值: " + val);
    } catch(e) {
        yzf.err("定时任务异常: " + e);
        // 异常被吞掉，定时器继续运行
    }
});
```

### 服务器冻结风险 🛑

**`while(true)` 或任何无限循环会永久冻结服务器。**

YZF 的 Rhino 运行时**没有**执行时间限制机制——没有指令计数器、没有看门狗线程、没有超时。Mindustry 的游戏循环是单线程的，脚本在主线程上执行。一个死循环会阻塞所有游戏逻辑、玩家连接、定时器。

```javascript
// ❌ 永远不要这样写
yzf.command("bad", function(args) {
    while(true) {} // 服务器立即冻结，只能杀进程恢复
});

// ✅ 如果必须循环，加退出条件和计数上限
yzf.command("safe", function(args) {
    var count = 0;
    while(someCondition && count < 10000) {
        count++;
        // ...
    }
});
```

### 推荐的容错模式

**模式 1：服务调用前检查可用性**

```javascript
yzf.onEnable(function() {
    if (!yzf.service.has("redis-main")) {
        yzf.warn("Redis 未就绪，相关功能禁用");
        return;
    }
    // 正常初始化...
});
```

**模式 2：try/catch 包裹高风险调用**

```javascript
function safeRedisGet(key) {
    try {
        return yzf.redis.get("redis-main", key);
    } catch(e) {
        yzf.err("Redis 读取失败: " + e);
        return null;
    }
}
```

**模式 3：null 检查返回值**

```javascript
var info = yzf.player.find("someName");
if (info === null) {
    yzf.info("找不到玩家");
    return;
}
var data = JSON.parse(info); // 安全：info 不是 null
```

**模式 4：定时器内全包裹**

```javascript
yzf.every(0, 60, function() {
    try {
        var players = JSON.parse(yzf.player.list());
        for (var i = 0; i < players.length; i++) {
            // 处理每个玩家...
        }
    } catch(e) {
        yzf.err("定时任务失败: " + e);
    }
});
```

---

## yzf.service.call 通用调用分发表

`yzf.service.call(serviceId, action, ...args)` 是统一的服务调用入口。`action` 参数**大小写不敏感**，按服务类型分发到不同的底层操作。

### Redis 服务（type: "redis"）

| action     | 参数                | 返回值             | 等价方法             |
| ---------- | ----------------- | --------------- | ---------------- |
| `"get"`    | key               | string | null   | `yzf.redis.get`  |
| `"set"`    | key, value        | `"OK"`          | `yzf.redis.set`  |
| `"delete"` | key               | `"OK"`          | `yzf.redis.del`  |
| `"incr"`   | key               | number (string) | `yzf.redis.incr` |
| `"hget"`   | key, field        | string | null   | `yzf.redis.hget` |
| `"hset"`   | key, field, value | `"OK"`          | `yzf.redis.hset` |

### SQL 服务（type: "mysql" / "mariadb" / "sqlite"）

| action             | 参数  | 返回值                    | 等价方法                     |
| ------------------ | --- | ---------------------- | ------------------------ |
| `"queryfirstcell"` | sql | string | null          | `yzf.sql.queryFirstCell` |
| `"queryjson"`      | sql | JSON array (string)    | `yzf.sql.queryJson`      |
| `"execute"`        | sql | affected rows (string) | `yzf.sql.execute`        |

### RemoteHTTP 服务（type: "remotehttp"）

| action       | 参数         | 返回值                    | 等价方法                  |
| ------------ | ---------- | ---------------------- | --------------------- |
| `"get"`      | path       | response body (string) | `yzf.remote.get`      |
| `"postjson"` | path, body | response body (string) | `yzf.remote.postJson` |

### MinIO 服务（type: "minio"）

| action      | 参数               | 返回值    | 等价方法                |
| ----------- | ---------------- | ------ | ------------------- |
| `"puttext"` | objectName, text | `"OK"` | `yzf.minio.putText` |

### 错误情况

| 场景                                              | 行为                                                                     |
| ----------------------------------------------- | ---------------------------------------------------------------------- |
| serviceId 不存在                                   | 抛出 `IllegalStateException("找不到服务: " + serviceId)`                      |
| action 不匹配任何操作                                  | 抛出 `IllegalArgumentException("不支持的服务操作: " + serviceId + "/" + action)` |
| 服务类型与 action 不匹配（如对 Redis 调 `"queryfirstcell"`） | 同上——不匹配则抛异常                                                            |

```javascript
// 等价写法
yzf.redis.set("redis-main", "key", "val");
yzf.service.call("redis-main", "set", "key", "val");

// 动态调用场景（action 由外部输入决定）
var action = "get";
var result = yzf.service.call("redis-main", action, "my-key");
```

---

## 权限、安全与审计日志

### 权限配置 permissions.hjson

文件位置：`<server-data>/yzf/config/permissions.hjson`

权限系统用于控制 `yzf.adminCommand` 注册的管理员命令。玩家执行命令时，系统按优先级依次检查权限。

#### 字段说明

| 字段             | 类型       | 默认值                             | 说明                   |
| -------------- | -------- | ------------------------------- | -------------------- |
| `default`      | string[] | `[]`                            | 所有非管理员玩家默认拥有的权限      |
| `defaultRoles` | string[] | `[]`                            | 默认角色列表（角色的权限会授予所有玩家） |
| `roles`        | object   | `{moderator: ["yzf.player.*"]}` | 角色定义，键为角色名，值为权限数组    |
| `players`      | object   | `{}`                            | 按玩家 UUID 的单独权限覆盖     |

- 权限字符串支持通配符后缀 `*`，如 `yzf.player.*` 匹配 `yzf.player.join`、`yzf.player.chat` 等。
- `players` 中每个 UUID 的值可以是**权限数组**（直接授予），也可以是**对象**（同时指定 permissions 和 roles）。

#### 权限检查优先级（从高到低）

1. 空权限字符串 → 直接放行
2. 玩家是管理员（Mindustry 原生 admin） → 直接放行
3. `players[uuid]` 中的**直接权限**
4. `players[uuid]` 中的**角色权限**（通过 `roles` 映射）
5. `default` 中的权限
6. `defaultRoles` 中的角色权限

#### 完整示例

```hjson
// <server-data>/yzf/config/permissions.hjson
{
    // 所有玩家默认拥有的权限
    default: ["yzf.player.join", "yzf.player.chat"]

    // 默认角色（其权限授予所有玩家）
    defaultRoles: ["member"]

    // 角色定义
    roles: {
        member: ["yzf.player.join", "yzf.player.chat"]
        moderator: ["yzf.player.*", "yzf.admin.kick", "yzf.admin.mute"]
        admin: ["yzf.*"]
    }

    // 按玩家 UUID 单独配置
    players: {
        // 简写：直接给权限数组
        "abc123-uuid-here": ["yzf.admin.reload"]

        // 完整写法：同时指定权限和角色
        "def456-uuid-here": {
            permissions: ["yzf.admin.ban"]
            roles: ["moderator"]
        }
    }
}
```

#### 在模块中使用权限

权限通过 `yzf.adminCommand` 的第 4 个参数关联：

```javascript
// 需要 yzf.admin.ban 权限才能执行
yzf.adminCommand("ban", "<player>", "封禁玩家", "yzf.admin.ban", function(player, args) {
    // 只有拥有 yzf.admin.ban 权限的玩家才能到达这里
    // ...
});
```

权限不足时，命令不会执行，且会在审计日志中记录 `permission-denied` 事件。

---

### 安全配置 security.hjson `🟡 预留`

文件位置：`<server-data>/yzf/config/security.hjson`

> **当前状态：** 此文件仅在首次启动时生成默认值，运行时**不会读取**。实际的运行时校验由代码硬编码完成。以下为当前声明的默认值，供未来版本参考。

```hjson
{
    // 是否允许进程型模块（node/java/kt/kts）启动
    allowProcessRuntimes: true

    // 允许的运行时类型
    allowedRuntimes: ["js", "node", "java", "kt", "kts"]

    // 是否启用审计日志（当前未生效——审计日志始终写入）
    auditEnabled: true
}
```

---

### 终端配置 terminal.hjson `🟡 预留`

文件位置：`<server-data>/yzf/config/terminal.hjson`

> **当前状态：** 同 security.hjson，仅写入默认值，运行时未读取。交互式终端当前回退到原生命令行。

```hjson
{
    // 是否启用交互式终端 UI（当前未生效）
    enabled: false

    // 是否启用 Foundation 终端库支持
    foundationSupport: true

    // 终端输出每页条目数
    pageSize: 15

    // 检测到哑终端时是否回退到基础 CLI
    fallbackOnDumbTerminal: true
}
```

---

### 审计日志 yzf-audit.log

文件位置：`<server-data>/yzf/logs/yzf-audit.log`

审计日志记录所有关键的模块和服务操作事件，格式为纯文本，每行一条：

```
yyyy-MM-dd HH:mm:ss [kind] subject | detail
```

#### 事件类型一览

| kind                 | 含义        | subject        | detail 示例                         |
| -------------------- | --------- | -------------- | --------------------------------- |
| `boot`               | 服务端启动     | `MindustryYZF` | `runtime=js`                      |
| `module-load`        | 模块加载成功    | `author/mymod` | `js`                              |
| `module-unload`      | 模块卸载      | `mymod`        | `js`                              |
| `module-rollback`    | 模块加载失败，回滚 | `mymod`        | 错误信息                              |
| `module-start`       | 进程型模块启动   | `author/mymod` | `node`                            |
| `module-stop`        | 进程型模块停止   | `mymod`        | `java`                            |
| `module-toggle`      | 模块启用/禁用   | `author/mymod` | `enable` 或 `disable`              |
| `permission-denied`  | 权限不足      | `author/mymod` | `somecmd -> yzf.admin.ban`        |
| `service-reload`     | 服务重载      | `all`          | —                                 |
| `mod-cmd-register`   | 命令注册      | `author/mymod` | `player:mycmd` 或 `callable:mycmd` |
| `mod-cmd-unregister` | 命令注销      | `mymod`        | `mycmd`                           |

#### 查看审计日志

```
yzf audit                  # 在控制台查看最近的审计日志
```

也可以在脚本中通过 `yzf.runtime` 命令间接查看，或直接读取日志文件。

---

## 12. Rhino 引擎注意事项与陷阱

### 12.1 不支持 ES6+ 语法

Rhino 引擎（默认）不支持以下语法：

```javascript
// ❌ 错误 — 不要用 let/const
let x = 1;
const y = 2;

// ✅ 正确 — 使用 var
var x = 1;
var y = 2;

// ❌ 错误 — 不要用箭头函数
var fn = (a, b) => a + b;

// ✅ 正确 — 使用 function
var fn = function(a, b) { return a + b; };

// ❌ 错误 — 不要用模板字符串
var msg = `Hello ${name}`;

// ✅ 正确 — 使用字符串拼接
var msg = "Hello " + name;

// ❌ 错误 — 不要用解构赋值
var {a, b} = obj;
var [x, y] = arr;

// ✅ 正确 — 使用点号或索引
var a = obj.a;
var b = obj.b;
var x = arr[0];

// ❌ 错误 — 不要用 for...of
for (var item of list) { ... }

// ✅ 正确 — 使用 for 循环
for (var i = 0; i < list.length; i++) { ... }

// ❌ 错误 — 不要用 class
class MyClass { ... }

// ✅ 正确 — 使用函数和原型
function MyClass() { ... }
```

### 12.2 不支持 synchronized 关键字

`synchronized` 是 Java 语法，不是 JavaScript。在 Rhino 中不能使用。

```javascript
// ❌ 错误
var lock = new java.lang.Object();
synchronized(lock) {
    // 临界区
}

// ✅ 正确 — 使用 ReentrantLock
var lock = new java.util.concurrent.locks.ReentrantLock();
lock.lock();
try {
    // 临界区
} finally {
    lock.unlock();
}
```

### 12.3 Java 类访问

直接使用完整类名，无需 import：

> 本节示例是 Rhino/JavaScript 写法。KT/KTS 插件应按第 3.1 节映射为 Kotlin 写法，例如 `java.lang.System.currentTimeMillis()` 在 KTS 中写作 `System.currentTimeMillis()`，`mindustry.gen.Call.sendMessage(text)` 可在 `import mindustry.gen.Call` 后写作 `Call.sendMessage(text)`。

```javascript
var File = java.io.File;
var Thread = java.lang.Thread;
var HashMap = java.util.HashMap;
var HttpClient = java.net.http.HttpClient;
var HttpServer = com.sun.net.httpserver.HttpServer;
```

### 12.4 数组和列表转换

Java 数组/列表在 Rhino 中的行为：

```javascript
// Java List 在 Rhino 中不能直接用 for...of
var list = java.util.Arrays.asList("a", "b", "c");

// ❌ 错误
for (var item of list) { ... }

// ✅ 正确 — 使用迭代器
var iter = list.iterator();
while (iter.hasNext()) {
    var item = iter.next();
}

// ✅ 正确 — 转为 JS 数组
var arr = Array.from(list);
for (var i = 0; i < arr.length; i++) { ... }
```

### 12.5 args 参数处理

命令回调的 args 参数是 Rhino 特殊数组：

```javascript
yzf.command("test", "<args...>", "测试命令", function(args) {
    // args.length 获取长度
    var len = args.length;

    // 访问元素
    var first = args[0];        // 或 args.get(0)
    var second = args[1];       // 或 args.get(1)

    // 转为 JS 数组
    var arr = Array.from(args);
    // 或
    var arr = Array.prototype.slice.call(args);

    // 遍历
    for (var i = 0; i < args.length; i++) {
        yzf.info("参数 " + i + ": " + args[i]);
    }
});
```

### 12.6 JDK 模块访问限制

某些 JDK 内部模块需要 JVM 参数才能从 Rhino 访问：

```
# com.sun.net.httpserver 需要：
--add-exports jdk.httpserver/sun.net.httpserver=ALL-UNNAMED

# sun.misc.Unsafe 等需要：
--add-opens java.base/sun.misc=ALL-UNNAMED
```

### 12.7 类型转换注意事项

```javascript
// Java String 和 JS String 不同
var javaStr = new java.lang.String("hello");
var jsStr = "hello";

// Java 方法返回的数字可能是 Java 类型
var count = yzf.player.count(); // 可能是 Java Integer
var jsCount = Number(count);    // 转为 JS Number

// JSON.parse 处理的是 JS 字符串
var result = JSON.parse(yzf.player.list()); // yzf 返回 Java String，JSON.parse 可以处理
```

---

## 13. 服务端命令参考

> 所有命令以 `yzf` 开头，在游戏内控制台或服务端终端执行。支持中英文别名（如 `yzf status` ≡ `yzf 状态`）。`yzf help [页码]` 分页列出全部命令，`yzf help all` 一次性打印。模块 ID 统一用完整格式 `author/moduleId`（如 `monthzifang/yueyu-hud`）。

### 13.1 模块与插件管理

| 命令 | 说明 |
|------|------|
| `yzf modules` | 列出全部已加载模块 |
| `yzf plugins` | 列出所有插件（`plugins/` 目录） |
| `yzf reload` | 重载全部模块（重新 `scan()` 后全量重载） |
| `yzf reload <author/moduleId>` | 只重载指定模块（事务性，级联重载其依赖者，失败则整批回滚） |
| `yzf info <author/moduleId>` | 查看模块详情（版本、运行时、依赖、配置路径等） |
| `yzf enable <author/moduleId>` | 启用模块（写回 `module.hjson` 的 `enabled=true` 并持久化） |
| `yzf disable <author/moduleId>` | 禁用模块（写回 `enabled=false`，下次启动不再加载，并触发重载卸载） |
| `yzf plugin enable\|disable <id>` | 启用 / 禁用插件 |
| `yzf mod <moduleId>` | 查看模块详情与命令管理 |
| `yzf mod register\|unregister\|list` | 注册 / 注销 / 列出模块命令 |
| `yzf scan` | 重新扫描模块目录（仅刷新注册表，不重载） |
| `yzf watch on\|off\|restart\|status` | 控制文件热监听守护线程（0.75s 防抖） |
| `yzf hotmods` | 热重载 YZF 模块与 Mindustry 脚本 mod |

### 13.2 系统监控与诊断

| 命令 | 说明 |
|------|------|
| `yzf status` | 服务端运行状态、目录、模块与服务摘要（对应 `yzf.status` 快照） |
| `yzf health` | 健康摘要：依赖问题、最近失败信息 |
| `yzf metrics` | 运行指标、调用计数、最近一次故障（对应 `YZFMetrics` 计数） |
| `yzf runtime` | 运行时桥状态与已加载模块列表 |
| `yzf verify` | 输出当前可运行验证摘要 |
| `yzf commands` | 列出所有已注册命令 |
| `yzf audit [tail [N]]` | 查看审计日志尾部（默认 N 条） |

### 13.3 服务与外部依赖

| 命令 | 说明 |
|------|------|
| `yzf services` | 列出服务与健康状态 |
| `yzf service reload\|info\|ping\|sqltest\|redistest\|httptest\|miniotest <id>` | 服务管理：重载 / 详情 / 连通性测试 |
| `yzf dbs` | 列出当前可查询的数据库 |
| `yzf <数据库别名> [页码]` | 分页查看该数据库中的玩家信息 |
| `yzf uuid <数据库别名> [页码]` | 同上，并额外显示原生 UUID |
| `yzf players [页码]` | 查看在线玩家详细列表（默认每页 15 人） |

### 13.4 权限与安全

| 命令 | 说明 |
|------|------|
| `yzf permissions reload` | 重新加载权限配置（`permissions.hjson`） |
| `yzf permissions check <uuid\|comid> <permission>` | 检查某玩家是否拥有指定权限 |
| `yzf permissions roles` | 列出所有角色 |
| `yzf api [summary\|list\|info <id>\|manifest\|readOnly\|writeOnly]` | 查看公开能力清单与详细说明（对应 `yzf.openapi`） |

> ⚠️ 纠错：旧文档写作 `yzf permission <player> <permission>`（单数、且语法与源码不符），实际命令为 `yzf permissions`，子命令为 `reload / check / roles`，请以本处为准。

---

## 14. 完整模块模板

### 基础模板

```javascript
// scripts/main.js — 最简模板
yzf.onEnable(function() {
    yzf.info("模块已加载!");
});

yzf.onDisable(function() {
    yzf.info("模块已卸载!");
});
```

### 功能完整模板

```javascript
// scripts/main.js — 包含命令、事件、定时器、配置、数据持久化
(function() {
    "use strict";

    // ============ 初始化 ============
    var killCount = 0;

    yzf.onEnable(function() {
        yzf.info("示例模块 v1.0.0 加载中...");

        // 读取配置
        var welcomeMsg = yzf.config.get("welcomeMessage", "欢迎来到服务器!");
        var bonusAmount = yzf.config.getInt("bonusAmount", 100);

        // --- 控制台命令 ---
        yzf.command("hello", "[name]", "打招呼命令", function(args) {
            var name = args.length > 0 ? String(args[0]) : "World";
            yzf.info("Hello, " + name + "!");
        });

        yzf.command("stats", "显示服务器统计", function(args) {
            yzf.info("=== 服务器统计 ===");
            yzf.info("在线人数: " + yzf.player.count());
            yzf.info("TPS: " + yzf.game.tps());
            yzf.info("波次: " + yzf.game.wave());
            yzf.info("击杀数: " + killCount);
        });

        // --- 玩家命令 ---
        yzf.playerCommand("myinfo", "", "查看个人信息", function(player, args) {
            var comid = yzf.comid.getOrCreate(player.uuid());
            var score = yzf.data.getInt(comid, "score", 0);
            var joinCount = yzf.data.getInt(comid, "joinCount", 0);
            yzf.player.send(player.id, "[cyan]=== 你的信息 ===");
            yzf.player.send(player.id, "名称: " + player.name);
            yzf.player.send(player.id, "comid: " + comid);
            yzf.player.send(player.id, "积分: " + score);
            yzf.player.send(player.id, "加入次数: " + joinCount);
        });

        yzf.playerCommand("score", "", "查看积分排行", function(player, args) {
            var players = JSON.parse(yzf.player.list());
            var scores = [];
            for (var i = 0; i < players.length; i++) {
                var comid = yzf.comid.get(players[i].uuid);
                if (comid >= 0) {
                    scores.push({
                        name: players[i].name,
                        score: yzf.data.getInt(comid, "score", 0)
                    });
                }
            }
            scores.sort(function(a, b) { return b.score - a.score; });
            yzf.player.send(player.id, "[yellow]=== 积分排行 ===");
            for (var i = 0; i < Math.min(10, scores.length); i++) {
                yzf.player.send(player.id, (i + 1) + ". " + scores[i].name + " - " + scores[i].score);
            }
        });

        // --- 管理员命令 ---
        yzf.adminCommand("setscore", "<player> <amount>", "设置玩家积分", "yzf.admin.setscore", function(player, args) {
            if (args.length < 2) {
                yzf.player.send(player.id, "用法: /setscore <player> <amount>");
                return;
            }
            var target = yzf.player.find(String(args[0]));
            if (!target) {
                yzf.player.send(player.id, "[scarlet]找不到玩家");
                return;
            }
            var info = JSON.parse(target);
            var comid = yzf.comid.getOrCreate(info.uuid);
            var amount = parseInt(String(args[1]));
            yzf.data.setInt(comid, "score", amount);
            yzf.player.send(player.id, "[green]已设置 " + info.name + " 的积分为 " + amount);
        });

        // --- 事件监听 ---
        yzf.on("PlayerJoin", function(event) {
            var p = event.player;
            var comid = yzf.comid.getOrCreate(p.uuid());

            // 更新加入次数
            var joinCount = yzf.data.getInt(comid, "joinCount", 0);
            yzf.data.setInt(comid, "joinCount", joinCount + 1);

            // 欢迎消息
            yzf.player.send(p.id, "[cyan]" + welcomeMsg);
            yzf.net.broadcast("[green]" + p.plainName() + " 加入了服务器 (第" + (joinCount + 1) + "次)");

            // 首次加入奖励
            if (joinCount === 0) {
                var score = yzf.data.getInt(comid, "score", 0);
                yzf.data.setInt(comid, "score", score + bonusAmount);
                yzf.player.send(p.id, "[yellow]首次加入奖励: +" + bonusAmount + " 积分!");
            }
        });

        yzf.on("PlayerLeave", function(event) {
            yzf.info(event.player.plainName() + " 离开了服务器");
        });

        yzf.on("WaveEvent", function(event) {
            var wave = yzf.game.wave();
            // 每10波广播
            if (wave % 10 === 0) {
                yzf.net.broadcast("[yellow]=== 第 " + wave + " 波! ===");
                yzf.net.broadcast("[yellow]全队核心 +500 铜");
                yzf.world.fill("copper", 500, 1);
            }
        });

        yzf.on("UnitDestroyEvent", function(event) {
            killCount++;
        });

        // --- 定时器 ---
        // 每30秒显示状态
        yzf.every(0, 30, function() {
            yzf.info("TPS: " + yzf.game.tps() + " | 玩家: " + yzf.player.count() + " | 波次: " + yzf.game.wave());
        });

        // 每5分钟广播一次
        yzf.every(300, 300, function() {
            yzf.net.broadcast("[yellow]提示: 输入 /myinfo 查看个人信息");
        });

        yzf.info("示例模块 v1.0.0 加载完成!");
    });

    yzf.onDisable(function() {
        yzf.info("示例模块已卸载，总击杀: " + killCount);
        // 保存数据（yzf.data 自动持久化，这里可以做额外清理）
    });

    // --- 跨模块导出 ---
    yzf.module.export("getKillCount", function() {
        return killCount;
    });

    yzf.module.export("addScore", function(uuid, amount) {
        var comid = yzf.comid.getOrCreate(uuid);
        var current = yzf.data.getInt(comid, "score", 0);
        yzf.data.setInt(comid, "score", current + amount);
        return current + amount;
    });
})();
```

### WebSocket 集成模板

```javascript
(function() {
    "use strict";
    var wsId = null;

    yzf.onEnable(function() {
        var wsUrl = yzf.config.get("wsUrl", "wss://echo.websocket.org");

        wsId = yzf.ws.connect(wsUrl,
            function(id) {
                yzf.info("WebSocket 已连接: " + id);
                yzf.ws.send(id, JSON.stringify({
                    type: "hello",
                    server: "MindustryYZF"
                }));
            },
            function(id, msg) {
                yzf.info("收到: " + msg);
                // 处理消息
                try {
                    var data = JSON.parse(msg);
                    if (data.type === "broadcast") {
                        yzf.net.broadcast("[cyan][WS] " + data.message);
                    }
                } catch(e) {}
            },
            function(id, code, reason) {
                yzf.info("WebSocket 关闭: " + code);
                // 自动重连
                yzf.after(5, function() {
                    if (!wsId || !yzf.ws.isOpen(wsId)) {
                        yzf.info("尝试重连...");
                        // 重新连接逻辑
                    }
                });
            },
            function(id, err) {
                yzf.err("WebSocket 错误: " + err);
            }
        );

        // 定期发送状态
        yzf.every(0, 30, function() {
            if (wsId && yzf.ws.isOpen(wsId)) {
                yzf.ws.send(wsId, JSON.stringify({
                    type: "status",
                    players: yzf.player.count(),
                    wave: yzf.game.wave(),
                    tps: yzf.game.tps()
                }));
            }
        });

        // 玩家加入时通知 WebSocket
        yzf.on("PlayerJoin", function(event) {
            if (wsId && yzf.ws.isOpen(wsId)) {
                yzf.ws.send(wsId, JSON.stringify({
                    type: "playerJoin",
                    name: event.player.plainName()
                }));
            }
        });
    });

    yzf.onDisable(function() {
        if (wsId) {
            yzf.ws.close(wsId);
            wsId = null;
        }
    });
})();
```

### HTTP 服务器模板（使用 com.sun.net.httpserver）

需要启动参数：`--add-exports jdk.httpserver/sun.net.httpserver=ALL-UNNAMED`

```javascript
(function() {
    "use strict";
    var HttpServer = com.sun.net.httpserver.HttpServer;
    var InetSocketAddress = java.net.InetSocketAddress;
    var httpServer = null;

    yzf.onEnable(function() {
        var port = yzf.config.getInt("port", 8080);

        try {
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);

            httpServer.createContext("/api/status", function(exchange) {
                var json = JSON.stringify({
                    ok: true,
                    players: yzf.player.count(),
                    wave: yzf.game.wave(),
                    tps: yzf.game.tps()
                });
                var bytes = new java.lang.String(json).getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                var os = exchange.getResponseBody();
                os.write(bytes);
                os.close();
            });

            httpServer.setExecutor(null);
            httpServer.start();
            yzf.info("HTTP 服务器已启动: http://0.0.0.0:" + port);
        } catch(e) {
            yzf.err("HTTP 服务器启动失败: " + e);
        }
    });

    yzf.onDisable(function() {
        if (httpServer) {
            httpServer.stop(1);
            httpServer = null;
        }
    });
})();
```

---

## 15. JVM 启动参数

### 基本启动

```bash
java -jar server-release.jar
```

### 访问 JDK 内部模块

```bash
java --add-exports jdk.httpserver/sun.net.httpserver=ALL-UNNAMED \
     --add-opens java.base/sun.misc=ALL-UNNAMED \
     -jar server-release.jar
```

### 常用 JVM 参数

```bash
java -Xms512M -Xmx2G \
     --add-exports jdk.httpserver/sun.net.httpserver=ALL-UNNAMED \
     -jar server-release.jar
```

---

## 16. FAQ

**Q: 模块放进去后没有加载？**  
A: 检查 module.hjson 格式是否正确，检查 enabled 是否为 true，检查目录结构是否符合要求。

**Q: 为什么脚本报错 `missing ; before statement`？**  
A: 可能使用了 ES6+ 语法（let, const, =>, 模板字符串等），Rhino 不支持。请全部使用 var 和 function。

**Q: 为什么 `synchronized` 报错？**  
A: synchronized 是 Java 语法，不是 JavaScript。使用 ReentrantLock 替代。

**Q: 为什么 `com.sun.net.httpserver.HttpServer` 报 IllegalAccessException？**  
A: Java 模块系统限制，需要在启动时加 `--add-exports jdk.httpserver/sun.net.httpserver=ALL-UNNAMED`。

**Q: 热加载是怎么工作的？**  
A: YZFFileWatcher 监控 modules/ 和 plugins/ 目录，文件变更时自动触发型模块重载。修改脚本后无需重启服务器。

**Q: 如何调试模块？**  
A: 使用 `yzf.info()` 输出调试信息，查看服务端日志。也可以用 `yzf.metrics` 查看性能指标。

**Q: 模块之间可以通信吗？**  
A: 可以。使用 `yzf.module.export()` 导出函数，其他模块用 `yzf.module.call()` 调用。

**Q: 数据存储在哪里？**  
A: `yzf.config.*` 存储在模块的 `data/config/config.hjson`（HJSON），`yzf.data.*` 存储在全局 `data/player-data/{comid}.json`。

---

## 17. 内置模块、插件与脚手架

服务端自带了若干示例模块、实用插件和脚手架工具，开箱即用。

### 17.1 内置示例模块

#### `example/open-api-demo` — Open API 演示 `🟢 稳定`

**路径：** `modules/example/open-api-demo/`

演示 `yzf.openapi.*` 和 `yzf.runtime.*` 的用法。启动后注册 `openapi-demo` 控制台命令，可查看 API 能力清单和运行时状态。

```javascript
// 源码摘要（scripts/main.js）
yzf.onEnable(function() {
    yzf.info("summary = " + JSON.stringify(yzf.openapi.summary()));
    yzf.info("runtime mode = " + yzf.runtime.mode());
    yzf.info("watcher running = " + yzf.runtime.watcherRunning());

    yzf.command("openapi-demo", "查看 API 能力", function(args) {
        var sub = args[0] ? String(args[0]).toLowerCase() : "";
        if (sub === "reload-self") { yzf.runtime.reloadSelf(); return; }
        if (sub === "reload-all")  { yzf.runtime.reloadAll();  return; }
        yzf.info("manifest = " + JSON.stringify(yzf.openapi.manifest()));
    });
});
```

#### `example/node-bridge` — Node.js 进程桥接示例 `🟢 稳定`

**路径：** `modules/example/node-bridge/`

演示 `runtime: "node"` 的进程型模块如何通过 stdin/stdout JSON 行协议与服务端通信。启动后注册 `node-bridge-ping` 控制台命令。

**Node.js 桥接协议要点：**

- 服务端 → 模块：JSON 行，`type` 字段区分消息（`enable`/`disable`/`command.invoke`/`reply`/`event`）
- 模块 → 服务端：JSON 行，`type` 字段区分动作（`log`/`registerCommand`/`config.get` 等）
- 每个请求带 `replyId`，服务端回复时携带相同 `replyId`

```javascript
// 源码摘要（Node.js 侧）
const readline = require("node:readline");
const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });

function send(type, fields) {
    process.stdout.write(JSON.stringify({ type, fields }) + "\n");
}

rl.on("line", line => {
    var message = JSON.parse(line);
    var fields = message.fields || {};
    if (message.type === "enable") {
        send("log", { level: "info", message: "已启用" });
        send("registerCommand", { replyId: "1", name: "node-bridge-ping", scope: "server" });
    } else if (message.type === "command.invoke") {
        send("log", { level: "info", message: "收到命令: " + fields.name });
    }
});
```

---

### 17.2 内置插件

#### `Online Map Switcher` — 在线换图投票 `🟢 稳定`

**作者：** Codex  
**路径：** `plugins/Online Map Switcher/`

在线地图切换插件，支持投票机制。注册 8 个命令：

| 命令              | 类型  | 说明         |
| --------------- | --- | ---------- |
| `/maps`         | 玩家  | 浏览可用地图（分页） |
| `/votemap <编号>` | 玩家  | 发起换图投票     |
| `/voteyes`      | 玩家  | 投赞成票       |
| `/voteno`       | 玩家  | 投反对票       |
| `/hostmap <名称>` | 管理员 | 直接切换地图     |
| `/chmap <名称>`   | 管理员 | 直接切换地图（别名） |
| `maplist`       | 控制台 | 列出所有地图     |
| `hostmap`       | 控制台 | 控制台换图      |

**配置文件：** `plugins/Online Map Switcher/config.hjson`

```hjson
{
    includeDefaultMaps: false      // 是否包含默认地图
    mapsPerPage: 9                 // /maps 每页显示数量
    voteSeconds: 30                // 投票持续时间（秒）
    voteRatioPercent: 60           // 投票通过所需百分比
    quickSwitchWhenPlayersAtMost: 1 // 在线≤此人数时直接切换（无需投票）
    adminVoteInstant: true         // 管理员投票是否立即通过
    announceWorldLoad: true        // 是否广播地图加载消息
}
```

---

### 17.3 构建输出中的附加插件

以下插件存在于 `server/build/libs/config/yzf/plugins/`，构建后随 JAR 分发：

| 插件                       | 作者    | 说明                               | 依赖              |
| ------------------------ | ----- | -------------------------------- | --------------- |
| **UHD Status UI**        | nano  | 服务端状态采集 + Web Dashboard（端口 8090） | 可选：Redis, SQL   |
| **UHD Render Framework** | Codex | 通用 HUD 渲染���架                      | `monthzifang/yueyu-hud` |
| **yueyu HUD Activator**  | monthzifang   | 加载 yueyu HUD 库并激活事件              | `monthzifang/yueyu-hud` |
| **UHD Join Tab**         | Codex | 玩家加入提示 + TAB 列表显示 comID          | —               |

---

### 17.4 yueyu HUD 库

**作者：** monthzifang  
**路径：** `modules/yueyu HUD/`

通用 JS 库，提供 20 个 API 模块：弹窗、HUD 状态栏、欢迎、公告、消息队列、维护模式、防刷屏、定时任务、MOTD 等。

**其他模块中加载 UHD 库：**

```javascript
// 在你的模块 scripts/main.js 中
load(yzfModule.root + "/../yueyu HUD/scripts/uhd-loader.js");

yzf.onEnable(function() {
    UHD._internal.cfgInit(yzf);
    UHD._internal.dbInit(yzfModule.dataDir);
    UHD._internal.routeMenu(yzf);

    // 欢迎消息
    yzf.on("PlayerJoin", function(event) {
        UHD.welcome.handleJoin(event.player, yzf);
        UHD.motd.sendTo(event.player);
    });

    // HUD 状态栏（每5秒更新）
    yzf.every(3, 5, function() {
        UHD.statusBar.updateHudAll();
    });

    // 公告轮播
    UHD.announcement.add("[lime]欢迎来到服务器!");
    yzf.every(10, 60, function() {
        UHD.announcement.broadcast();
    });

    // 玩家命令
    yzf.playerCommand("status", "", "查看服务器状态", function(player, args) {
        UHD.statusBar.showPopup(player);
    });
});
```

---

### 17.5 模块脚手架

服务端内置 `YZFModuleScaffold` 工具，可程序化生成模块骨架。虽然没有暴露为 `yzf create` 命令，但生成的内容可作为手动创建模块的参考模板。

**脚手架自动生成的文件：**

```
modules/<author>/<moduleId>/
├── module.hjson          # 完整元数据（所有字段）
├── scripts/main.js       # 入口脚本（onEnable + onDisable + 测试命令）
└── README.md             # 模块说明文档
```

**自动生成的 `scripts/main.js`：**

```javascript
// MindustryYZF JavaScript 服务端模块入口
yzf.onEnable(function(){
  yzf.info("模块已启用: " + yzfModule.fullId);
});

yzf.onDisable(function(){
  yzf.info("模块已停用: " + yzfModule.fullId);
});

yzf.command("mymodule-ping", "简单测试命令", function(args){
  yzf.info("收到 mymodule-ping，参数数量=" + args.length);
});
```

> **手动创建时可参考此结构。** 脚手架的 `sanitize()` 函数会将作者名和模块 ID 转为小写，替换非法字符为 `-`。

---

### 17.6 首次启动自动生成的配置

服务端首次启动时，`MindustryYZF.installDefaults()` 会自动生成以下配置文件（已存在则跳过）：

| 文件                  | 位置        | 内容                   |
| ------------------- | --------- | -------------------- |
| `permissions.hjson` | `config/` | 空默认权限 + moderator 角色 |
| `security.hjson`    | `config/` | 允许所有运行时 + 启用审计       |
| `terminal.hjson`    | `config/` | 禁用交互终端 + 回退 CLI      |

这些文件的格式和字段详见 [权限、安全与审计日志](#权限安全与审计日志) 章节。

---

## 18. 服务端架构：网络配置与进程隔离

> 本章汇总 YZF 服务端**自身的网络边界**与**进程隔离**基础设施，区别于第 8 章“外部服务连接配置”所面向的 Redis / SQL / MinIO 等下游服务。这些是支撑多模块、多进程安全运行的地基。

### 18.1 跨网访问鉴权（external-access）

服务端对外暴露的原始命令套接字与 HTTP 接口，跨越内网边界时需要鉴权。规则由 `config/external-access.hjson` 定义，底层实现见 `YZFExternalAccessConfig`：

| 配置项                                  | 默认值     | 说明                                                |
| ------------------------------------ | ------- | ------------------------------------------------- |
| `enabled`                            | `true`  | 总开关；关闭后所有跨网请求直接放行（仅调试用）                           |
| `requireTokenForPublic`              | `true`  | 公网地址（非私网）必须携带有效 token                             |
| `requireTokenForPrivate`             | `false` | 私网地址是否也要求 token                                   |
| `attachTokenToOutbound`              | `true`  | 出站请求自动附带 `Authorization: Bearer <token>`          |
| `requireTlsForPublic`                | `true`  | 公网出站 URL 必须为 `https` / `wss`，否则拒绝                 |
| `allowInsecurePublicSocket`          | `false` | 公网绑定原始套接字需显式开启（原始 socket 无 TLS）                   |
| `token` / `tokenFile` / `secretFile` | —       | 鉴权令牌；`secretFile` 指向文件按 SHA-512 派生，令牌长度须 ≥ 128 字符 |

鉴权按地址归属区分公网 / 私网（私网、环回、链路本地、ULA 视为私网）；配置文件非法时降级为「公网拒绝 + 强制 token」的安全默认。

### 18.2 集群模式（cluster mode）

多实例部署时通过 `YZFClusterMode` 选择拓扑：

| 模式            | 说明     |
| ------------- | ------ |
| `standalone`  | 单节点    |
| `replication` | 主从复制   |
| `sentinel`    | 哨兵高可用  |
| `cluster`     | 分片集群   |
| `loadbalance` | 负载均衡前端 |

### 18.3 进程间通信协议（region protocol）

`process` 模式的每个内存区都以独立 JVM 子进程运行。父进程通过 `YZFProtocolHost` 经子进程的 **stdout / stdin 收发 JSON 行**（`YZFProtocolMessage.toJsonLine()`）进行管控：注册命令、下发玩家命令、代理事件回调、生命周期 `shutdown` 等。子进程异常退出会被 `YZFProcessRuntime` 自动清理并写入审计日志（`module-stop` / `module-start`）。

### 18.4 进程隔离运行时配置

进程隔离相关的运行时开关集中在 `runtime.hjson` 与 `memory-regions.hjson`（见 [7.29 yzf.memory](#729-yzfmemory--内存区与进程隔离)）：

- `defaultIsolation`：`classloader` / `process` / `logical` / `auto`，新区的默认隔离级别。
- `classLoaderIsolationEnabled`：模块级类加载器隔离总开关。
- `memoryPolicy.{enabled, forceProcess, defaultMin, defaultMax}`：对进程型运行时统一施加堆上限。
- `coldLoad.{enabled, reloadStrategy, defaultIsolation, allowPluginCreateRegion, regionsConfigPath}`：冷加载与新区创建策略。
- `allowPluginCreateRegion`：是否允许脚本通过 `yzf.memory.create(...)` 新建区域。

> 脚本层的网络接口另见 [7.10 yzf.net](#710-yzfnet--网络消息)、[7.14 yzf.remote](#714-yzfremote--远程http服务)、[7.19 yzf.ws](#719-yzfws--websocket)，以及第 8 章外部服务配置。

---

## 19. 模块/插件生命周期、卸载与进程线程

> 这一章把“模块是怎么被卸载的”和“模块跑在什么进程 / 线程上”两套机制讲透。它与 [7.29 yzf.memory](#729-yzfmemory--内存区与进程隔离)（内存区三级隔离）、[第 18 章](#18-服务端架构网络配置与进程隔离)（网络与进程隔离基础设施）、[7.2 生命周期](#72-生命周期)（onEnable / onDisable）互为补充。底层实现见 `YZFJsRuntime`、`YZFJsModuleBridge`、`YZFEmbeddedRuntime`、`YZFProcessRuntime`、`YZFModuleRegistry`、`YZFModHotReloadManager`、`YZFMemoryRegion`、`YZFMainThread`、`YZFTaskBinding`。
>
> 术语说明：本文的“模块（module）”与“插件（plugin）”是**同一套 API、两种扫描目录**。`modules/<author>/<id>/` 与 `plugins/<id>/` 下的 `module.hjson` 格式完全一致，区别只在目录布局与 `loadType`。下文的“卸载”对二者通用。

### 19.1 模块与插件：同一套 API，两套目录

`YZFModuleRegistry.scan()` 启动时同时扫描两个根目录：

| 目录                       | 子目录要求                  | `loadType` | `_source` | 说明            |
| ------------------------ | ---------------------- | ---------- | --------- | ------------- |
| `modules/<author>/<id>/` | 需要 `author` 子目录两层      | `module`   | `modules` | 标准模块，按作者分组    |
| `plugins/<id>/`          | **平级**，无需 `author` 子目录 | `plugin`   | `plugins` | 单目录插件，扫描时自动打标 |

扫描后统一交给 `YZFDependencyResolver` 做依赖拓扑排序（处理 `depends` 硬依赖与 `softDepends` 软依赖），硬依赖缺失的模块被跳过并记录 `dependencyErrors`，随后按序 `register`。

`module.hjson` 中影响加载 / 卸载的关键字段（见 `YZFModuleMeta`）：

- `enabled`（默认 `true`）：为 `false` 时该模块不参与加载，也就不会有后续卸载动作。
- `depends` / `softDepends`：决定拓扑顺序；卸载一个模块时其所有依赖者会被递归纳入重载计划。
- `runtime`：`js` / `kt` / `kts` / `java` / `node` —— 决定它走“脚本”还是“嵌入式 classloader”还是“独立进程”路径（见 19.8）。
- `memoryMin` / `memoryMax`：进程型运行时（`java`/`kt`/`kts`/`node`）的实际 `-Xms`/`-Xmx` 或 Node 堆上限，由 `runtime.hjson` 的 `memoryPolicy` 兜底。

> 模块对象 `YZFModuleDefinition` 持有 `root / metaFile / scriptsDir / dataDir / cacheDir / mainScript` 及全部脚本清单；`YZFLoadedModule` 则保存运行期绑定（命令名、玩家命令、事件、任务、`onEnable`/`onDisable` 回调、脚本作用域、源码文本），是卸载时清理的依据。

### 19.2 生命周期回调 onEnable / onDisable

脚本侧通过 `yzf.onEnable(fn)` / `yzf.onDisable(fn)` 注册回调（底层 `YZFJsModuleBridge.onEnable/onDisable` 存为 Rhino `Function`）。

- **onEnable**：模块首次加载成功、或热重载重新加载成功后调用。
- **onDisable**：模块卸载时调用（热重载、内存变更自动重载、运行时终止、服务器关闭都会触发）。

对于 `java`/`kt`/`kts` 嵌入式模块，生命周期通过 `EmbeddedModuleApi.onEnable/onDisable(Runnable)` 注册，回调收集进 `enableCallbacks` / `disableCallbacks`，由 `EmbeddedModuleState.runEnable()/runDisable()` **顺序执行**，每个回调都用 `YZFCallbackGuard` 包裹——单个 `onDisable` 抛异常**只记错误、不中断其余回调与后续清理**，保证卸载始终能完成。

```javascript
yzf.onEnable(function(){
  yzf.info("模块已启用: " + yzfModule.fullId);
});

yzf.onDisable(function(){
  // 关闭自己打开的资源（定时器由框架自动取消，无需手动）
  yzf.info("模块已卸载: " + yzfModule.fullId);
});
```

### 19.3 卸载的 6 类触发场景

| # | 触发方式       | 入口                                                                | 说明                                                                                                                                                                                                          |
| - | ---------- | ----------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1 | 热重载单个模块    | `yzf runtime reloadModule(id)` / 命令行 `yzf reload <id>`            | 事务性：先逆序卸载依赖链，再顺序加载（见 19.7）                                                                                                                                                                                  |
| 2 | 热重载全部      | `yzf runtime reloadAll()` / `requestReloadAll()`                  | 重新 `scan()` 后全量重载                                                                                                                                                                                           |
| 3 | 内存区 / 堆变更  | `yzf runtime setMemory(id, min, max)` 或改 `module.hjson`           | `setModuleMemoryLimits` 写回 `module.hjson` 后对进程型模块**自动重载**才能生效                                                                                                                                               |
| 4 | 运行时终止      | `yzf runtime terminate(id)`                                       | `terminateModule(id)` → `unloadModule(id, true)`；返回该模块重载前是否存在                                                                                                                                               |
| 5 | 服务器关闭      | `YZFJsRuntime.shutdown()`                                         | 依次 `processRuntime.stopAll()` + `embeddedRuntime.stopAll()`，对所有模块执行卸载                                                                                                                                       |
| 6 | 命令行禁用 / 启用 | `yzf disable <author/id>`、`yzf plugin disable <id>`（启用用 `enable`） | `toggleModule`：写回 `module.meta.enabled` 并 `YZFModuleIO.writeMeta()` **持久化**到 `module.hjson`，随后 `registry.scan()`；禁用时 `reloadAll()`（触发该模块卸载），启用时 `reloadModule()`。**下次启动因 `enabled=false` 不再加载**，是最彻底的“停用”方式 |

此外，**文件监听热重载**（`YZFModHotReloadManager`）会在 `mods/` 目录树中 `.js/.zip/.jar/mod.json/mod.hjson/plugin.json/plugin.hjson` 变化时触发：模块文件变更 → `requestReloadModule`（带防抖）；`runtime.hjson` 变更 → `reloadRuntimeConfig()` + 可选 `reloadAll()`。

### 19.4 卸载全链路（unloadModule）

核心方法 `YZFJsRuntime.unloadModule(moduleId, runDisable=true)`，无论哪种触发场景最终都汇聚到这里：

1. `loadedModules.remove(moduleId)` —— 取走脚本态。
2. `processRuntime.stop(moduleId)` —— 若是进程型模块，走 [19.5](#195-进程模块process-模式卸载细节) 的进程停止流程。
3. `embeddedRuntime.stop(moduleId)` —— 若是嵌入式模块，走 [19.6](#196-嵌入式模块classloader-隔离卸载细节) 的 classloader 关闭流程。
4. `invokeLifecycle(state.onDisable, scope)` —— 调用脚本 `onDisable`（`runDisable=true` 时）。
5. `cleanupModuleResources(...)` —— **资源清理清单**（仅清理本模块拥有的绑定，避免误伤他人）：
   - 服务端命令：`handler.removeCommand(name)`（仅当 `commandOwners` 属于本模块）
   - 玩家命令：`Vars.netServer.clientCommands.removeCommand(name)`
   - 事件绑定：`Events.remove(eventType, handler)`
   - 定时任务：`YZFTaskBinding.cancel()`（内部 `task.cancel()`）
   - WebSocket 连接：`wsManager.closeModule(moduleId)`
   - WebUI 页面：`context.webUi.unregisterModule(fullId)`
   - 跨模块导出函数：`clearExportedFunctions(moduleId)`
   - 跨模块调用记录 `moduleCalls` 清理
6. `context.audit.record("module-unload", moduleId, runtime)` —— 写入审计日志。

> 关键设计：**所有清理都是“按 owner 反向注销”**。模块卸载后，它注册过的命令、事件、任务、连接都不会泄漏到框架全局。

### 19.5 进程模块（process 模式）卸载细节

当模块 `runtime ∈ {java, kt, kts, node}` 且隔离模式为 `process` 时，它运行在**独立子进程**（`YZFProcessRuntime`）。卸载（`stop(moduleId)`）流程：

1. 通过 `YZFProtocolHost` 向子进程 stdout 发送生命周期协议消息 `disable` 再 `shutdown`（JSON 行，`YZF_PROTOCOL=ndjson-stdio`）。
2. `cleanupBindings(state)` —— 注销该模块在父进程侧注册的命令、事件、任务映射。
3. 关闭协议宿主 `state.protocol.close()`。
4. `state.process.destroy()` —— 先发终止信号；等待 **750ms** 优雅退出，超时则 `destroyForcibly()`（强制杀死）。
5. `joinQuietly(...)` —— 依次 `join` 三个守护线程：`protocolReader`、`stderr`、`stdout`，各等待 1000ms（自身线程直接返回，避免死锁）。
6. 写审计 `module-stop`。

子进程侧：协议读取线程 `readProtocol` 在流关闭 / 进程退出时进入 `finally`，通过 `YZFMainThread.post(() -> cleanupExitedProcess(state))` 在**主线程**完成最后的注册表清理与审计 `module-exit`，并打印 `Process module exited and was cleaned up` 警告。宿主还会持续采样子进程内存（`tasklist` / `/proc/<pid>/status` 的 `VmRSS`）与 PID 存活状态，异常退出即触发清理。

### 19.6 嵌入式模块（classloader 隔离）卸载细节

当模块 `runtime ∈ {java, kt, kts}` 且隔离模式为 `classloader`（或未按 process 隔离）时，它运行在**独立 URLClassLoader**（`YZFEmbeddedRuntime`）。卸载（`stop(moduleId)` → `cleanupState(state, runDisable=true)`）：

1. `state.runDisable()` —— 顺序执行 `disableCallbacks`（即 Java/Kotlin 侧的 `onDisable`）。
2. 服务端命令：`handler.removeCommand(...)`；玩家命令：`Vars.netServer.clientCommands.removeCommand(...)`。
3. 事件：`Events.remove(eventType, handler)`；任务：`binding.cancel()`。
4. `context.webUi.unregisterModule(fullId)` —— 注销后台页面。
5. `embeddedExports.remove(fullId)`、`moduleCommands` 映射清理、`wsClose` 关闭该模块所有 WebSocket。
6. **关闭类加载器**：`state.classLoader.close()` —— 这是 classloader 隔离实现模块级内存 / 类回收的关键步骤，确保旧版本的类与静态资源可被 GC。

> **失败安全热重载**：`reload(module)` 会**先编译 Kotlin / 准备新版本，再 `stop` 旧版本**。若新代码有语法 / 编译错误，旧版本保持存活，不会被卸载替换（见 `YZFEmbeddedRuntime.reload` 注释）。同样是先 `validateCompiledKotlinJar` 校验再 `stop` 再加载。

### 19.7 热重载的事务性与失败回滚

`YZFJsRuntime.reloadModule(moduleId)` 是框架最复杂的安全机制之一：

1. `registry.scan()` 重新扫描全部目录。
2. `registry.resolveReloadPlan(moduleId)` —— 用 `collectDependents` **递归收集所有依赖者**（凡 `depends`/`softDepends` 指向本模块者），保证改一个模块时连带它的下游一起重载，且顺序正确。
3. 创建快照 `YZFReloadSnapshot` —— 保存各模块的源码文本与已加载态（`previousJsStates`）。
4. **逆序** `unloadModule(planned.fullId(), true)` 卸载计划内模块（`kt`/`kts` 跳过，因为编译已在子步骤提前完成）。
5. **顺序** `execute(module)` 逐个加载。
6. 任一加载失败 → **回滚**：逆序 `unloadModule` 已应用的，再从快照 `previousJsStates` 用 `executeSource` 恢复旧版本，并记审计 `module-rollback`。

配套机制：

- **防抖**：`requestReloadModule` 对同一模块在 `reloadDebounceMs` 内的重复请求会被合并；调度经 `Timer.schedule(..., 0.05f)` 的 `drainReloadRequests` 一次性派发。
- **指标**：`metrics.moduleReloads` / `moduleFailures` / `moduleRollbacks` 计数，复盘热重载健康度。

### 19.8 进程与线程模型

理解模块“跑在哪”是排障与做高性能插件的基础。

**① 进程隔离（process 模式）**  
每个模块是一支**独立 JVM（java/kt/kts）或 Node（node）子进程**。宿主启动时通过 `ProcessBuilder` 注入环境变量：

```
YZF_MODULE_ID / YZF_MODULE_FULL_ID / YZF_MODULE_ROOT
YZF_DATA_DIR / YZF_CACHE_DIR
YZF_PROTOCOL=ndjson-stdio        # 使用 stdout/stdin 的 JSON 行协议
```

宿主侧为该子进程启动 **2 个 daemon 线程**：`YZF-<runtime>-<id>-protocol`（读协议）与 `YZF-<runtime>-<id>-stderr`（读错误流）。子进程的 PID、存活、内存都被持续监控；异常退出自动清理。

**② 类加载器隔离（classloader 模式）**  
每个模块持有独立 `URLClassLoader`，可经 `replaceClassLoader(loader)` **热替换**（旧 loader 先 `close`）。停止时 `close()` 释放。这实现了“同一 JVM 内模块级类空间隔离”，比进程模式轻量，但不隔离内存 / 崩溃。

**③ 内存区三级隔离**  
`yzf.memory.create(id, mode, minHeap, maxHeap)` 的 `mode` 取 `logical` / `classloader` / `process`（见 [7.29](#729-yzfmemory--内存区与进程隔离)）。`YZFMemoryRegion` 的状态机：`CREATED → STARTING → ACTIVE → DRAINING → STOPPED / FAILED`，`stop()` 进入 `DRAINING` 后向子进程写 `shutdown`、等待 2s、超时强制杀死、最后 `close` classloader，置 `STOPPED`。

**④ 主线程模型**  
`YZFMainThread.post(Runnable)` 是所有跨线程回调的统一入口：有 `Core.app` 时投递到游戏主线程 `Core.app.post`，否则直接执行。协议读取线程、`cleanupExitedProcess` 等都通过它回到主线程，避免并发修改 Mindustry 全局状态。

**⑤ 任务调度线程**  
`yzf.after(delay, fn)` / `yzf.every(interval, fn)` 由 arc `Timer.Task` 驱动，绑成 `YZFTaskBinding`：内含 `AtomicInteger failures`（失败计数，达阈值由 `recordFailure` 判定）与 `AtomicBoolean cancelled`（幂等 `cancel`）。模块卸载时任务被自动 `cancel()`，无需脚本手动管理。

**⑥ 热重载 watch 守护线程**  
`YZFModHotReloadManager` 启动一个名为 `MindustryYZF-ModHotReload` 的 **daemon 线程**，基于 `WatchService` 递归监听 `mods/` 目录树的 `create/modify/delete`，过滤 `.js/.zip/.jar/mod.json/mod.hjson/plugin.json/plugin.hjson`（忽略 `.tmp/.log/.bak/.swp`），变更后经 **0.75s 防抖**才 `requestReload`，避免编辑器保存抖动触发多次重载。停止时 `interrupt()` + 关闭 `WatchService`。

**⑦ WebSocket 线程池**  
`wsExecutor` 是 WebSocket 读写的线程池；服务器关闭 / 模块卸载时对所有连接 `wsClose`，最终 `shutdownWebSockets()` → `wsExecutor.shutdownNow()`。

**⑧ 模块间调用与隔离边界**  
`yzf.module.export/call/exported`（见 [7.22 yzf.module](#722-yzfmodule--跨模块通信)）是插件协作的 IPC 入口。`module.call` 底层**优先查嵌入式导出**（`callEmbeddedExport`，用于 `java/kt/kts` 模块），再回退 JS 导出注册表（`exportedFunctions`）。不同隔离模式下的调用路径：

- `logical` / `classloader`：同一 JVM 内直接反射 / Rhino 调用，无序列化开销。
- `process`：跨进程调用经 `YZFProtocolHost` 的 ndjson-stdio 协议代理（子进程导出的函数由父进程侧注册表映射），调用在**主线程** `YZFMainThread.post` 队列中派发，避免跨进程并发冲突。

> 注意：模块卸载时（`unloadModule` → `cleanupModuleResources` / `clearExportedFunctions`）其导出函数会被同步注销，调用方需先用 `yzf.module.exported(id)` 检查存在性，否则抛 `IllegalArgumentException`。

### 19.9 小结与交叉引用

- **卸载是确定性的**：无论触发源，最终都收敛到 `unloadModule` 的统一清理清单（命令 / 玩家命令 / 事件 / 任务 / WS / 页面 / 导出函数 / classloader 或子进程），并写审计日志。
- **进程隔离最彻底**：`process` 模式给模块独立 JVM/Node 子进程与守护线程，崩溃不影响主进程；`classloader` 模式轻量但共享 JVM；`logical` 仅逻辑隔离。
- **热重载是事务性的**：依赖链整体重载 + 快照回滚，编译失败保留旧版。
- 相关章节：[7.29 yzf.memory 内存区与进程隔离](#729-yzfmemory--内存区与进程隔离) · [7.2 生命周期](#72-生命周期) · [第 18 章 网络配置与进程隔离](#18-服务端架构网络配置与进程隔离) · [6. module.hjson 完整字段说明](#6-modulehjson-完整字段说明)。

---

## 20. 服务端可观测性与容错机制

> 本章描述 YZF 框架**内部**的可观测性与容错能力：分级错误日志、回调保护、运行指标、审计日志、日志保留。它们不直接以 `yzf.*` 脚本 API 暴露，而是框架在运行时自动保障「脚本出错不会拖垮服务端 + 运维可观测」的底层机制。  
> 脚本层如何处理错误请看 [第 9 章 错误处理与容错指南](#错误处理与容错指南)；权限与审计的配置请看 [第 11 章 权限、安全与审计日志](#权限安全与审计日志)；这些指标在 [7.30 yzf.status](#730-yzfstatus--服务端状态快照) 的 `statusSnapshot()` 中有聚合快照。

### 20.1 分级错误日志（YZFErrorLog）

框架对所有脚本异常的捕获与落盘统一走 `YZFErrorLog`，而不是简单打印到控制台。

- **四个级别**：`LOW`（黄）、`MEDIUM`（橙）、`HIGH`（深橙）、`EMERGENCY`（红粗）。
- **统一入口**：`record(Level level, String source, String message, Throwable error)`；便捷方法 `low / medium / high / emergency(source, message, error)` 直接对应四个级别。
- **落盘路径**：`logs/<yyyy-MM-dd>/errors/<level>/<safe-source>.log`
  - 按「日期 → 级别 → 来源」三级分目录；
  - `source` 中的非法字符（含 `\ / : * ? " < > |` 与控制字符）统一替换为 `_`，超过 80 字符截断，缺省为 `yzf`。
- **单文件上限 5 MB**：超出自动新建 `error-N.log` 轮转，不会无限增长。
- **终端回显**：同时向 `System.err` 输出；`terminalColors = true` 时按级别上色（ANSI 转义）。
- **设计铁律**：写入异常（`IOException`）被静默吞掉，日志系统本身**永不**导致服务端崩溃。
- **开关**：启动时调用 `YZFErrorLog.configure(paths, enabled, terminalColors)`；`enabled = false` 关闭落盘但保留终端输出。

```java
// 框架内部用法（插件无需调用，了解机制即可）
YZFErrorLog.high("my-module", "加载配置失败", ex);
YZFErrorLog.emergency("core", "内存区启动失败", ex);
```

### 20.2 回调保护（YZFCallbackGuard）

所有脚本回调——事件监听（`yzf.on`）、定时器（`yzf.after` / `yzf.every`）、命令回调——统一通过 `YZFCallbackGuard.run(moduleId, kind, callback)` 执行。

```java
public static boolean run(String moduleId, String kind, Runnable callback){
    if(callback == null) return true;
    YZFContext context = MindustryYZF.context();
    if(context == null || MindustryYZF.isShuttingDown()) return false;
    try{
        callback.run();
        return true;
    }catch(Throwable error){
        context.metrics.callbackFailures.incrementAndGet();
        context.metrics.markFailure("callback:" + moduleId + ":" + kind + ":" + error.getMessage());
        YZFErrorLog.medium(moduleId, "Callback failed: " + kind, error);
        return false;   // 异常绝不向上传播
    }
}
```

机制要点：

1. 异常被 `catch(Throwable)` 兜底（含 `Error`，不会漏）；
2. 失败时 `metrics.callbackFailures + 1` 并 `markFailure(...)` 记录详情；
3. 记一条 `MEDIUM` 级错误日志；
4. **返回 `false`，异常绝不冒泡**到主循环或事件分发器。

> 意义：某个模块的事件 / 定时器 / 命令回调抛异常，只会被单独隔离、记录，不会中断主循环、不会连累其他模块、更不会导致服务端宕机。这正是 YZF 相对裸 Mindustry 脚本「稳」的关键。

### 20.3 运行指标（YZFMetrics / YZFServerMetrics）

框架维护一组 `AtomicLong` 计数器，记录全生命周期的运行统计（底类 `YZFMetrics`）：

| 分类 | 字段                                                                                                       | 含义                     |
| -- | -------------------------------------------------------------------------------------------------------- | ---------------------- |
| 模块 | `moduleLoads` / `moduleReloads` / `moduleFailures` / `moduleRollbacks`                                   | 模块加载 / 热重载 / 失败 / 回滚次数 |
| 服务 | `serviceLoads` / `serviceFailures`                                                                       | 外部服务加载 / 失败            |
| 调用 | `serverCommandCalls` / `playerCommandCalls` / `remoteCalls` / `serviceCalls` / `sqlCalls` / `redisCalls` | 各类调用计数                 |
| 安全 | `permissionDenied`                                                                                       | 权限被拒绝次数                |
| 协议 | `protocolIn` / `protocolOut`                                                                             | 进程间协议收发条数              |
| 审计 | `auditEvents`                                                                                            | 审计事件总数                 |
| 容错 | `callbackFailures`                                                                                       | 脚本回调失败次数               |

状态字段：`startedAtMillis`、`lastReloadAtMillis`、`lastFailureAtMillis`、`lastFailure`（最近一次失败详情）。便捷方法 `markReload()` / `markFailure(detail)`。

服务端 TPS（稳定 API，`YZFServerMetrics`）：

- `actualTps()` —— 最新 1 秒窗口实测 TPS（`ServerLauncher.actualTps()`）；
- `tpsLimit()` —— 配置的主循环 TPS 上限（`Vars.serverTps`）。

> 这些计数器是「服务端健康度」的第一手数据。`yzf.status.statusSnapshot()`（见 [7.30](#730-yzfstatus--服务端状态快照)）会聚合并对外暴露其中一部分，运维面板可据此做告警。

### 20.4 审计日志（YZFAuditLog）

用于记录关键操作（模块加载 / 卸载、权限变更、命令调用等），供事后追溯。

- **API**：
  - `record(kind, subject, detail)` —— 记录一条审计；
  - `tail(maxLines)` —— 读取「当天」审计日志末尾 N 行（返回 `Seq<String>`）；
  - `path()` —— 返回当天审计日志目录路径。
- **落盘**：`logs/<yyyy-MM-dd>/audit/audit-N.log`；单文件 5 MB 轮转。
- **格式**：`yyyy-MM-dd HH:mm:ss [kind] subject | detail`（`detail` 为空则省略 ` | detail`）。
- **防注入**：`kind / subject / detail` 中的 `\r` `\n` 替换为空格，避免日志注入 / 换行逃逸。
- **启动清理**：构造时对其父目录调用 `YZFLogRetention.prune` 清理过期。

> [第 11 章](#权限安全与审计日志) 列出的审计 `kind`（如 `module-load` / `module-unload` / `permission-denied`）即由本组件落盘。

### 20.5 日志保留（YZFLogRetention）

统一日志保留策略，保证磁盘不被历史日志无限占用：

- **保留窗口**：固定 **14 天**（`days = 14`）；
- `prune(root)`：仅删除 `root` 下**名称可解析为 `LocalDate`**、且早于「今天 - 14 天」的**目录**（即按日期分目录的 `logs/<日期>/`）；
- **保守删除**：非日期目录、解析失败一律忽略——只删它该删的；
- **失败静默**：清理过程中的任何 IO / 解析异常全部吞掉，**绝不**因清理失败阻塞服务端启动；
- **触发时机**：`YZFErrorLog.configure(...)` 与 `YZFAuditLog` 构造时都会调用一次 `prune`。

### 20.6 小结与交叉引用

- 错误日志、回调保护、运行指标、审计、日志保留共同构成 YZF 的「**可观测 + 不崩溃**」底座。
- 它们对插件透明：插件只管写业务逻辑，异常会被 `YZFCallbackGuard` 隔离、被 `YZFErrorLog` 记录、被 `YZFMetrics` 计数。
- 相关章节：[第 9 章 错误处理与容错指南](#错误处理与容错指南)（脚本层 try/catch 写法）· [第 11 章 权限、安全与审计日志](#权限安全与审计日志)（审计 `kind` 与权限配置）· [7.30 yzf.status](#730-yzfstatus--服务端状态快照)（指标聚合快照）· [第 19 章 卸载与进程线程](#19-模块插件生命周期卸载与进程线程)（回调失败计数在此累加）。

---

## 21. 脚本安全边界与运行时白名单

YZF 在 `yzf.*` 脚本 API 之外，还有一层**服务端级安全底座**，用于约束模块能使用什么运行时、命令名/标识符/权限节点的合法形态、以及敏感值在日志中如何脱敏。底层实现见 `YZFSecurity`（工具类）与 `YZFSecurityConfig`（加载 `config/security.hjson`）。这一层对插件透明——插件无需手动调用，注册命令 / 声明运行时 / 写日志时自动生效。

### 21.1 security.hjson 配置

位置 `config/security.hjson`（`YZFPaths.securityFile`）。服务端首次启动由 `MindustryYZF.installDefaults()` 自动生成（已存在则跳过），字段见下表：

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `allowedRuntimes` | `string[]` | `["js","node","java","kt","kts"]` | 允许模块声明的**运行时白名单**；不在表中的 `runtime` 模块**拒绝加载** |
| `allowProcessRuntimes` | `bool` | `false` | 是否允许 `process` 隔离级别（独立 JVM / Node 子进程）。默认关闭——开启需要更多资源与系统权限 |
| `auditEnabled` | `bool` | `true` | 审计日志总开关；关闭后 `YZFAuditLog` 不再落盘 |

```hjson
// config/security.hjson —— 收紧运行时面、默认不放开进程隔离
allowedRuntimes: ["js", "node", "kt"]   // 锁掉 java / kts
allowProcessRuntimes: false
auditEnabled: true
```

- **缺省 / 损坏回退**：配置文件缺失、或解析抛出异常时，`YZFSecurityConfig.load(...)` 回退到安全默认（允许全部运行时 + 启用审计 + 允许进程），并在服务端日志打印错误——**不会因配置损坏导致服务端起不来**。
- 与 [第 18 章 进程隔离](#18-服务端架构网络配置与进程隔离) 的关系：当 `module.hjson` 的 `runtime` 走进程，或 `runtime.hjson` 设 `memoryPolicy: forceProcess` 时，必须 `allowProcessRuntimes: true` 才能生效，否则模块加载被拒。

### 21.2 输入合法性校验（YZFSecurity）

所有对外暴露的「名称类」参数在注册时都经过白名单正则，非法值**直接拒绝**（不会静默绕过或降级）：

| 方法 | 正则 | 用途 |
|------|------|------|
| `validCommandName(name)` | `^[a-z0-9_-]+$` | 服务端命令 / `yzf.command` 的指令名（全小写 + 数字 + 连字符 / 下划线） |
| `validIdentifier(id)` | `^[A-Za-z0-9._/-]+$` | 一般标识符（模块 ID、服务 ID、路径段等） |
| `validRuntime(rt)` | 大小写不敏感匹配 `js / java / kt / kts / node` | 运行时字符串合法性 |
| `validPermission(p)` | `^[a-z0-9._*-]+$` | 权限节点（支持 `*` 通配，如 `yzf.admin.*`） |

```javascript
// 校验在底层自动发生，无需插件手动调用
yzf.command("my-mod-ping", "测试", function(args){ /* ... */ }); // ✅ 合法：全小写 + 连字符
yzf.command("My_Mod.Ping", "测试", function(args){});            // ❌ 被 validCommandName 拒绝（含大写与句点）
```

### 21.3 敏感值脱敏

日志与对外展示中，token / 密码 / 令牌类字段**不会明文出现**，统一由 `YZFSecurity` 脱敏：

| 方法 | 行为 |
|------|------|
| `mask(value)` | 空值显示 `<空>`；长度 ≤ 4 → 全 `****`；否则保留首尾各 2 字符 + 中间 `****`（如 `ab****yz`） |
| `sanitizeLog(value)` | 将 `\r` / `\n` 替换为空格并 `trim`，**防止日志注入**（用换行伪造新日志行） |

```java
YZFSecurity.mask("sk_live_1234567890abcdef"); // → "sk****ef"
YZFSecurity.mask("abc");                       // → "****"
YZFSecurity.sanitizeLog("ok\r\n[FAKE] evil");  // → "ok [FAKE] evil"
```

> 这与 [第 20 章 分级错误日志](#20-服务端可观测性与容错机制) 的「写入异常静默」、[第 11 章 审计日志](#权限安全与审计日志) 的 `\r\n` 防注入，是同一套「日志不泄露、日志不越权」设计。

### 21.4 小结与交叉引用

- 安全底座对插件**透明**：插件只管写业务逻辑，运行时白名单、输入校验、日志脱敏在服务端底层自动执行。
- 唯一可调面是 `config/security.hjson`：收紧运行时白名单、开关进程隔离、开关审计。
- 相关章节：[第 18 章 进程隔离](#18-服务端架构网络配置与进程隔离)（process 隔离开关 `allowProcessRuntimes`）· [第 11 章 权限、安全与审计日志](#权限安全与审计日志)（`permissions.hjson` 角色与审计 `kind`）· [第 20 章 可观测性](#20-服务端可观测性与容错机制)（日志脱敏联动）· [17.6 首次启动自动生成的配置](#176-首次启动自动生成的配置)（security.hjson 默认生成位置）。
