# 运行时 SDK 与 Kotlin 运行时（runtime-sdk）超详细开发文档

## 稳定性标记说明

| 标记 | 含义 |
| --- | --- |
| `🟢 稳定` | 签名和行为已定型，向后兼容，可用于生产。 |
| `🟡 实验性` | 可用但细节可能调整，建议做好容错。 |
| `🔴 已弃用` | 未来版本移除，文中给出替代方案。 |

## 目录

- [五分钟快速上手](#五分钟快速上手)
- [1. 系统概述](#1-系统概述)
- [2. 目录结构](#2-目录结构)
- [3. Kotlin 四种运行模式详解](#3-kotlin-四种运行模式详解)
- [4. runtime.hjson 配置完全指南](#4-runtimehjson-配置完全指南)
- [5. runtime-sdk/kotlin-libs 目录说明](#5-runtime-sdkkotlin-libs-目录说明)
- [6. Kotlin 插件模板（server/kotlin-plugin-template）](#6-kotlin-插件模板serverkotlin-plugin-template)
- [7. 嵌入式 Kotlin 编译器内部机制](#7-嵌入式-kotlin-编译器内部机制)
- [8. 外部 Kotlin 编译器内部机制](#8-外部-kotlin-编译器内部机制)
- [9. 服务端部署模板（server/server_template）](#9-服务端部署模板serverserver_template)
- [10. 错误处理与容错](#10-错误处理与容错)
- [11. FAQ](#11-faq)
- [12. 待确认清单](#12-待确认清单)

---

## 五分钟快速上手

`runtime-sdk/` 目录承载 YZF 框架的**可插拔运行时依赖**——当前唯一的成员是 `kotlin-libs/`，用于存放嵌入式 Kotlin 编译器/运行时 jar。它解决的核心问题是：**Kotlin 编译器体积大、版本敏感，不应打进 server.jar**，而是放在独立目录，由隔离的 ClassLoader 按需加载。

最常用的一条配置（`config/yzf/config/runtime.hjson`）：

```hjson
kotlin: { mode: "embedded-kotlin", libsPath: "runtime-sdk/kotlin-libs" }
```

含义：用**进程内嵌入**的 Kotlin 编译器编译 KT/KTS 模块，编译器 jar 从 `runtime-sdk/kotlin-libs/` 加载。把 Kotlin 2.3.20 的编译器系列 jar 放进该目录即可。

---

## 1. 系统概述

### 1.1 它解决什么问题

YZF 支持 JavaScript / Node / Java / Kotlin 多种脚本运行时。其中 Kotlin（`.kt` / `.kts` 模块）需要真正的 Kotlin 编译器。把编译器打进 server.jar 会带来：

- 服务端 jar 体积膨胀数十 MB；
- 编译器版本与框架绑死，升级困难；
- 不需要 Kotlin 的部署也要背着编译器。

因此框架把编译器**外置**到 `runtime-sdk/kotlin-libs/`，并通过 `runtime.hjson` 的 `kotlin.mode` 提供四种取舍（见 [第 3 章](#3-kotlin-四种运行模式详解)）。

### 1.2 关键源码入口

| 组件 | 文件 | 职责 |
| --- | --- | --- |
| 运行时配置 | `server/src/mindustry/yzf/YZFRuntimeConfig.java` | 解析 `runtime.hjson`，产出全部开关 |
| 嵌入式编译器 | `server/src/mindustry/yzf/YZFEmbeddedKotlinRuntime.java` | 进程内编译 KT/KTS（隔离 ClassLoader 加载编译器） |
| 外部编译器 | `server/src/mindustry/yzf/YZFExternalKotlinCompiler.java` | 调外部 `kotlinc` 进程编译 |
| 嵌入式运行时宿主 | `server/src/mindustry/yzf/YZFEmbeddedRuntime.java` | 编译产物加载、`EmbeddedModuleApi` 注入、生命周期 |
| Kotlin 插件模板 | `server/kotlin-plugin-template/` | 预编译 jar 插件的 Gradle 模板 |
| 部署模板 | `server/server_template/` | `run_server.bat` / `run_server.sh` 启动脚本 |

---

## 2. 目录结构

```text
runtime-sdk/
└── kotlin-libs/            # Kotlin 编译器/运行时 jar 放置目录
    └── README.txt          # 官方说明（版本要求、配置方法）
```

运行时该目录位于 yzf 根目录下（与 `config/`、`netmods/` 平级）。`libsPath` 可配置为绝对路径或相对 yzf 根目录的路径。

相关目录一览：

```text
server/kotlin-plugin-template/
├── build.gradle.kts        # Gradle Kotlin DSL 构建脚本
├── settings.gradle.kts
├── gradle.properties
├── README.md               # 构建与部署说明
└── src/main/kotlin/example/KotlinRuntimeTest.kt

server/server_template/
├── run_server.bat          # Windows 启动脚本
└── run_server.sh           # Linux 启动脚本
```

---

## 3. Kotlin 四种运行模式详解

`🟢 稳定`（模式开关机制本身；具体默认值可能随版本调整）

`runtime.hjson` 的 `kotlin.mode`（或旧式 `mode`）取值：

| 模式 | 编译发生地 | 编译器来源 | 适用场景 |
| --- | --- | --- | --- |
| `embedded-kotlin`（默认） | **服务端进程内** | `runtime-sdk/kotlin-libs/` 的 jar，经隔离 URLClassLoader 加载 | 开箱即用；源码即部署；开发环境 |
| `external-kotlin` | 独立 `kotlinc` 子进程 | `compilerPath` 指定，或 PATH / `KOTLIN_HOME` 搜索 | 已装独立 Kotlin 工具链；希望与 server.jar 完全解耦 |
| `precompiled` | **开发期**（本机 Gradle） | 开发者的构建环境 | 生产部署：只加载编译好的 jar，服务端零编译开销 |
| `disabled` | 不编译 | 无 | 明确禁用 Kotlin 运行时 |

**模式决议规则**（源码 `YZFRuntimeConfig.load`）：

1. 顶层 `mode` 只接受 `precompiled` / `external-kotlin`，其余回落 `precompiled`；
2. `kotlin.mode`（嵌套）接受全部四值，非法值回落 `precompiled`；
3. 若 `kotlin.mode` 为空：`externalKotlin.enabled=true` → `external-kotlin`，否则 → **`embedded-kotlin`**；
4. 即：**不做任何配置时，默认就是 embedded-kotlin**（源码注释："Source Kotlin is compiled in-process by default"）。

### 3.1 embedded-kotlin（嵌入式，默认）

- 编译线程：独立单线程执行器 `MindustryYZF-KotlinCompiler`，编译超时 **120 秒**。
- 编译器参数固定：`-no-stdlib -no-reflect -jvm-target 17 -classpath <java.class.path>`（stdlib/reflect 由 server.jar 的父 ClassLoader 提供，避免每个热重载产物重复携带）。
- 编译产物：模块缓存目录下 `embedded-kotlin/<入口类>-<纳秒>.jar`。
- 找不到编译器 jar 时报错：`Embedded Kotlin compiler is unavailable. Put the Kotlin compiler/runtime jars in runtime-sdk/kotlin-libs.`

### 3.2 external-kotlin（外部进程）

- 编译器定位顺序：`runtime.hjson` 的 `compilerPath`（相对 yzf 根目录解析）→ PATH 里的 `kotlinc.bat`/`kotlinc` → `KOTLIN_HOME/bin/`。都找不到抛错：`External Kotlin mode is enabled, but kotlinc was not found. Set compilerPath in runtime.hjson.`
- 编译命令（固定）：`kotlinc <生成源文件> -classpath <server.jar> -include-runtime -jvm-target 17 -d <输出jar>`。
- 输出 jar 带 `-include-runtime`（自带 Kotlin 运行时）；产物名带纳秒后缀，**避免覆盖仍被旧 ClassLoader 锁定的 jar**（Windows 文件锁问题，源码注释明确说明）。
- 超时同为 120 秒，超时后 destroy → 5 秒宽限 → destroyForcibly。
- ⚠️ 注意区别：`runtime-sdk/kotlin-libs/README.txt` 明确写道 "This is not external-kotlin: no kotlinc process is started"——kotlin-libs 目录**只服务 embedded 模式**，external 模式走的是独立 kotlinc 安装。

### 3.3 precompiled（预编译）

- 服务端**不编译**，只加载模块目录里现成的 jar（`module.hjson` 的 `main` 指向 jar，`runtime: "java"`）。
- 开发期构建参照 [第 6 章 Kotlin 插件模板](#6-kotlin-插件模板serverkotlin-plugin-template)。
- 生产环境推荐：启动最快、无编译器依赖、行为最可预测。

### 3.4 disabled

- Kotlin 模块不会被编译/加载（`kotlinEnabled()` 返回 false）。JS 等其他运行时不受影响。

---

## 4. runtime.hjson 配置完全指南

**用途：** 控制全部脚本运行时的开关、热重载、隔离与 Kotlin 编译策略。位于 `config/yzf/config/runtime.hjson`，不存在时由框架写入下方默认内容。

**默认内容（框架自动生成的原文）：**

```hjson
{
  // precompiled: load Kotlin jars built during development
  // kotlin.mode: precompiled | embedded-kotlin | external-kotlin | disabled
  mode: "precompiled"
  precompiled: { enabled: true }
  externalKotlin: { enabled: false }
  kotlin: { mode: "embedded-kotlin", libsPath: "runtime-sdk/kotlin-libs" }
  # Production switches. Set enabled/hotReload false for unused runtimes.
  features: { js: { enabled: true, hotReload: true }, node: { enabled: false, hotReload: false }, java: { enabled: false, hotReload: false }, kotlin: { enabled: false, hotReload: false }, fileWatcher: { enabled: false }, classLoaderIsolation: { enabled: false } }
  fileWatcherEnabled: false
  classLoaderIsolationEnabled: false
  externalNodeEnabled: false
  errors: { enabled: true, terminalColors: true }
  memoryPolicy: { enabled: false, forceProcess: false, defaultMin: "", defaultMax: "" }
  coldLoad: { enabled: false, reloadStrategy: "original", defaultIsolation: "classloader", allowPluginCreateRegion: true, regionsConfigPath: "config/memory-regions.hjson" }
  // Optional absolute path to kotlinc or kotlinc.bat. Empty means search PATH/KOTLIN_HOME.
  compilerPath: "runtime-sdk/kotlin/bin/kotlinc.bat"
}
```

> 注意：默认模板中 `features.kotlin.enabled: false` 与 `kotlin.mode: embedded-kotlin` 并存——前者控制"是否加载 Kotlin 运行时模块"，后者控制"加载时怎么编译"。要跑 KTS 插件需把 `features.kotlin.enabled` 改为 `true`。

### 4.1 字段总表

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `mode` | string | `precompiled` | 旧式顶层模式；仅 `precompiled`/`external-kotlin` 有效，其余回落 precompiled |
| `precompiled.enabled` | bool | true | 允许加载预编译 jar |
| `externalKotlin.enabled` | bool | false | 旧式外部 Kotlin 开关（影响 `externalKotlin()` 判定） |
| `kotlin.mode` | string | `embedded-kotlin` | 四值之一，见 [第 3 章](#3-kotlin-四种运行模式详解) |
| `kotlin.libsPath` | string | `runtime-sdk/kotlin-libs` | 嵌入式编译器 jar 目录；可绝对路径 |
| `compilerPath` | string | `runtime-sdk/kotlin/bin/kotlinc.bat` | external 模式的 kotlinc 路径；文件不存在时回落 PATH/KOTLIN_HOME 搜索 |
| `features.js.enabled` / `hotReload` | bool | true / true | JS 运行时与热重载 |
| `features.node.enabled` / `hotReload` | bool | true / true（模板给 false） | Node 运行时（另受 `externalNodeEnabled` 约束） |
| `features.java.enabled` / `hotReload` | bool | true / true（模板给 false） | Java 运行时（预编译 jar 插件走这里） |
| `features.kotlin.enabled` / `hotReload` | bool | true / true（模板给 false） | Kotlin 运行时与热重载 |
| `features.fileWatcher.enabled` | bool | true（模板给 false） | 文件监听热重载总开关（与 `fileWatcherEnabled` 合并） |
| `features.classLoaderIsolation.enabled` | bool | true（模板给 false） | ClassLoader 隔离开关 |
| `fileWatcherEnabled` | bool | true（模板 false） | 文件监听开关（features.fileWatcher 优先） |
| `classLoaderIsolationEnabled` | bool | true（模板 false） | ClassLoader 隔离（features.classLoaderIsolation 优先） |
| `externalNodeEnabled` | bool | 同 node 开关 | Node 外部进程运行时 |
| `errors.enabled` | bool | true | 错误日志 |
| `errors.terminalColors` | bool | true | 终端彩色错误输出 |
| `memoryPolicy.enabled` | bool | false | 进程内存策略（仅 node/java/kt/kts 进程型运行时适用） |
| `memoryPolicy.forceProcess` | bool | false | 强制进程隔离 |
| `memoryPolicy.defaultMin/Max` | string | `""` | JVM 堆默认上下限（如 `"256m"`/`"1g"`） |
| `coldLoad.enabled` | bool | false | 冷加载 |
| `coldLoad.reloadStrategy` | string | `original` | `original` / `cold` |
| `coldLoad.defaultIsolation` | string | `classloader` | `classloader` / `process` / `logical` / `auto` |
| `coldLoad.allowPluginCreateRegion` | bool | true | 允许插件创建内存区域 |
| `coldLoad.regionsConfigPath` | string | `config/memory-regions.hjson` | 内存区域配置文件 |
| `reloadStrategy` | string | `original` | 顶层重载策略，值同上 |

**解析容错：** 整个文件解析失败时记录 `Failed to parse runtime.hjson; using safe defaults` 并使用全量安全默认值（embedded-kotlin + 全运行时开启）——不会因配置错误导致服务端无法启动。

### 4.2 典型配置场景

**场景 A：开发期，KTS 源码即改即生效**

```hjson
kotlin: { mode: "embedded-kotlin", libsPath: "runtime-sdk/kotlin-libs" }
features: { kotlin: { enabled: true, hotReload: true } }
```

**场景 B：生产部署，只跑编译好的 jar**

```hjson
kotlin: { mode: "precompiled" }
features: { java: { enabled: true, hotReload: false }, kotlin: { enabled: false } }
```

**场景 C：用系统安装的 kotlinc**

```hjson
kotlin: { mode: "external-kotlin" }
compilerPath: "D:/kotlin/bin/kotlinc.bat"   # 或留空走 PATH
```

---

## 5. runtime-sdk/kotlin-libs 目录说明

**官方 README.txt 原文要点：**

- 用途：存放与服务端匹配的 **Kotlin 2.3.20** 编译器/运行时 jar；服务端把 Kotlin 编译器放在 server.jar 之外，通过**隔离的依赖 ClassLoader** 在进程内加载。
- 需要的构件族（Required families）：
  - `kotlin-compiler-embeddable`
  - `kotlin-scripting-compiler-embeddable`
  - `kotlin-scripting-common`
  - `kotlin-scripting-jvm`
  - `kotlin-scripting-jvm-host`
  - `kotlin-stdlib`
  - 以及它们的传递性 Kotlin 编译器依赖
- 启用配置：

```hjson
kotlin: { mode: "embedded-kotlin", libsPath: "runtime-sdk/kotlin-libs" }
```

- 明确声明：**这不是 external-kotlin**——不会启动任何 kotlinc 进程。

**加载机制**（源码 `YZFEmbeddedKotlinRuntime.kotlinClassLoader`）：

1. 目录不存在或无 `.jar` 文件 → 回落到服务端自身 ClassLoader（此时编译器类通常找不到，编译报错提示放入 jar）。
2. 目录下全部 `.jar` 组装成 `URLClassLoader`（父加载器 = 框架 ClassLoader），**只构建一次**并缓存。
3. 运行时关闭（`close()`）时释放该 ClassLoader。

**操作步骤：为嵌入式 Kotlin 补齐依赖**

1. 从 Maven Central 下载 Kotlin 2.3.20 的上述构件及其传递依赖（可用 `mvn dependency:copy-dependencies` 或 Gradle 生成）。
2. 全部 jar 放入 `runtime-sdk/kotlin-libs/`。
3. 确认 `runtime.hjson` 为 embedded-kotlin 模式、`features.kotlin.enabled: true`。
4. 启动服务端，加载一个 KTS 模块观察是否编译成功。

预期成功日志由模块加载流程给出；失败时的典型报错见 [第 10 章](#10-错误处理与容错)。

---

## 6. Kotlin 插件模板（server/kotlin-plugin-template）

`🟢 稳定`

**用途：** 开发**预编译** Kotlin 插件（precompiled 模式）的 Gradle 工程模板。服务端不含 Kotlin 编译器，只加载构建产物 jar。

**README.md 原文流程：**

1. 构建：

```text
gradlew.bat jar
```

2. 把产物 `build/libs/kotlin-runtime-test.jar` 复制进插件目录，并使用以下模块元数据：

```hjson
id: "kotlin-runtime-test"
name: "Kotlin Runtime Test"
author: "your-name"
main: "kotlin-runtime-test.jar"
runtime: "java"
enabled: true
loadType: "plugin"
```

**关键点：**

- `runtime: "java"`——预编译 jar 走 Java 运行时加载路径，不需要服务端具备 Kotlin 编译能力。
- 示例源码位于 `src/main/kotlin/example/KotlinRuntimeTest.kt`。
- 该模板产出的插件即仓库 `yzf/plugins/kotlin-runtime-test` 的来源。

---

## 7. 嵌入式 Kotlin 编译器内部机制

`🟡 实验性`（产物命名与包装规则可能演进）

源码 `YZFEmbeddedKotlinRuntime`：

### 7.1 编译流程

1. 缓存目录：`<模块缓存>/embedded-kotlin/`（不存在则创建）。
2. 按入口脚本扩展名分流：`.kts` → KTS 包装流程；`.kt` → 常规流程。
3. 生成源文件：KTS 为 `GeneratedKtsPlugin.kt`；KT 为 `<入口类>.kt`。
4. 输出产物：`<入口类>-<System.nanoTime()>.jar`（纳秒后缀防覆盖）。
5. 提交给单线程编译执行器，120 秒超时；超时取消任务并删除半成品 jar。

### 7.2 入口类解析规则（.kt）

按优先级（源码 `resolveEntryClass`）：

1. 文件中的 `package x.y` 声明作为前缀；
2. 顶层第一个 `object Name` → `x.y.Name`；
3. 否则顶层第一个 `class Name` → `x.y.Name`；
4. 都没有 → `x.y.<文件名（非字母数字转下划线）>Kt`。

### 7.3 KTS 包装规则（.kts）

两种写法都支持（源码 `prepareKts`）：

**写法 A：自带 `fun install(...)`**——仅补一行 `import mindustry.yzf.YZFEmbeddedRuntime`，入口类为 `GeneratedKtsPluginKt`：

```kotlin
fun install(api: YZFEmbeddedRuntime.EmbeddedModuleApi) {
    // api 即模块 API；api.module() 取模块定义
}
```

**写法 B：裸脚本**——自动包装进 `object GeneratedKtsPlugin { @JvmStatic fun install(api) { ... } }`，脚本体内预置两个变量：

- `val yzf = api`（即 `EmbeddedModuleApi`）
- `val yzfModule = api.module()`

顶层 `import` 语句会被提取到包装之外（保持编译合法）。

### 7.4 EmbeddedModuleApi

`YZFEmbeddedRuntime.EmbeddedModuleApi` 接口是 KT/KTS 模块与框架交互的入口，源码可见的成员包括 `onEnable(Runnable)`、`module()` 等；完整方法面以 `YZFEmbeddedRuntime.java` 为准（本文档不逐一展开，JS 侧等价 API 见 `YZF-API-文档.md`）。

---

## 8. 外部 Kotlin 编译器内部机制

源码 `YZFExternalKotlinCompiler`：

1. **源包装**：无论原始脚本内容如何，一律包装为 `GeneratedKtsPlugin.install(api)` 对象（与嵌入式写法 B 相同的包装，但不做"自带 install 检测"）。
2. **classpath**：server.jar 自身路径（`-classpath <server.jar>`）。
3. **产物**：`<模块缓存>/external-kotlin/<模块id>-runtime-<纳秒>.jar`，带 `-include-runtime`（自含 Kotlin 运行时）。
4. **Windows 文件锁对策**：源码注释明确——绝不覆盖上一个 URLClassLoader 仍在使用的产物路径，故每次生成新纳秒文件名。
5. **超时与强杀**：120 秒 → destroy → 5 秒 → destroyForcibly。

---

## 9. 服务端部署模板（server/server_template）

```text
server/server_template/
├── run_server.bat    # Windows 启动脚本模板
└── run_server.sh     # Linux 启动脚本模板
```

**用途：** 拷贝到部署目录后按环境修改 JVM 参数（堆大小、GC、编码）再使用的启动脚本起点。⚠️ 暂未从源码确认：两个脚本的具体参数清单（本次未逐行读取脚本内容），使用前请打开脚本按注释修改。

JVM 启动参数的完整建议见 `YZF-API-文档.md` 第 15 章。

---

## 10. 错误处理与容错

| 场景 | 行为 | 解决 |
| --- | --- | --- |
| `runtime.hjson` 解析失败 | 记录 `Failed to parse runtime.hjson; using safe defaults`，全量安全默认 | 修复 hjson 语法 |
| kotlin-libs 为空/不存在（embedded） | 回落到框架 ClassLoader，编译时报 `Embedded Kotlin compiler is unavailable. Put the Kotlin compiler/runtime jars in runtime-sdk/kotlin-libs.` | 按 [第 5 章](#5-runtime-sdkkotlin-libs-目录说明) 补齐 jar |
| 找不到 kotlinc（external） | `External Kotlin mode is enabled, but kotlinc was not found. Set compilerPath in runtime.hjson.` | 配置 `compilerPath` 或安装并加入 PATH |
| 编译超时（两种模式） | 120 秒终止，模块加载失败 | 简化模块或提高机器性能；超时值硬编码不可配 |
| 编译失败（embedded） | `Kotlin compilation failed for <fullId>: <编译器输出>` | 看输出修语法 |
| 编译失败（external） | `External Kotlin compilation failed for <fullId>: <输出>` | 同上 |
| `compilerPath` 指向不存在的文件 | 静默回落 PATH/KOTLIN_HOME 搜索 | 非错误，但建议修正路径 |

**容错设计要点：**

- 编译器在**独立单线程执行器**运行，编译卡死不会阻塞游戏线程（超时后取消）。
- 产物纳秒后缀 + 不覆盖旧 jar：热重载期间旧模块 ClassLoader 仍可安全持有旧 jar。
- 嵌入式编译器 ClassLoader 与主 ClassLoader 隔离，编译器依赖不污染服务端类路径。

---

## 11. FAQ

**Q1：kotlin-libs 应该放哪个版本的 jar？**
A：README 指明 **Kotlin 2.3.20** 系列（编译器与运行时需同版本）。混用版本可能导致编译器内部错误。

**Q2：embedded 和 external 能同时用吗？**
A：`kotlin.mode` 是单值，同一时刻只有一种编译路径生效。`externalKotlin.enabled` 与 `mode` 的组合判定见 `externalKotlin()`/`embeddedKotlin()` 方法逻辑。

**Q3：我的 KTS 模块没被加载？**
A：检查顺序：① `features.kotlin.enabled` 是否 true（默认模板是 false）；② `kotlin.mode` 不是 `disabled`；③ embedded 模式下 kotlin-libs 是否有 jar；④ 模块 `module.hjson` 的 runtime 是否为 kt/kts。

**Q4：预编译 jar 插件需要 kotlin-libs 吗？**
A：不需要。precompiled/`runtime: "java"` 路径不触发 Kotlin 编译。kotlin-libs 只服务源码级（embedded）编译。

**Q5：热重载 KTS 时旧 jar 会被删除吗？**
A：每次编译生成新的纳秒后缀 jar，旧文件保留（Windows 文件锁安全）。缓存目录会随热重载次数累积产物。⚠️ 暂未从源码确认：是否有自动清理旧产物的逻辑。

---

## 12. 待确认清单

| 项目 | 缺失内容 | 建议确认方式 |
| --- | --- | --- |
| server_template 脚本细节 | run_server.bat/sh 的参数清单未逐行核对 | 打开脚本阅读注释 |
| EmbeddedModuleApi 完整方法面 | 本文只确认了 `onEnable`/`module()` 等部分成员 | 阅读 `YZFEmbeddedRuntime.java` 接口定义 |
| 嵌入式编译产物清理策略 | 纳秒后缀 jar 是否有自动清理 | 与框架维护者确认 |
| kotlin-libs 传递依赖完整清单 | README 只列了构件族名 | 用 Gradle/Maven 依赖树导出实际清单 |

---

*本文档由源码逐行核对生成；与实现冲突时以 `server/src/mindustry/yzf/` 下对应类为准。*
