<div align="center">
  <a href="https://github.com/MonthZifang/YUEYUEDAO-TECH">
    <img src="docs/assets/yueyuedao-tech-logo.png" alt="月月岛科技 · YUEYUEDAO TECH" width="720" />
  </a>

  <p><strong>月月岛科技 · YUEYUEDAO TECH 维护</strong></p>

  <p>
    <a href="https://github.com/MonthZifang/YUEYUEDAO-TECH"><strong>查看月月岛科技详情</strong></a>
  </p>
</div>

# Mindustry YF Framework

基于 Mindustry 159.2 的服务端扩展框架。项目保留原版游戏多端构建能力，并在 `server` 模块中提供 YZF 模块系统、脚本运行时、服务连接、数据存储、权限控制和可发现的 API 能力清单。

> 本仓库仅包含源码与示例配置。构建产物、服务器运行数据、日志以及本地 AI 聊天记录均不会提交。

## 功能概览

- 基于 Java 17 与 Gradle 的 Mindustry 159.2 工程，支持桌面端、服务端、Android、iOS、测试及开发工具。
- YZF 服务端框架支持 Java、JavaScript 和 Kotlin 扩展运行时。
- 提供模块发现与热重载、命令、事件、权限、审计日志、指标、数据库、缓存、远程 HTTP 与 WebSocket 能力。
- 运行时 API 能力清单区分只读与写入接口，方便模块按最小权限集成。

## 快速开始

### 环境要求

- JDK 17 或更高版本（推荐 JDK 17）。
- Windows 使用 `gradlew.bat`；macOS/Linux 使用 `./gradlew`。
- Android 构建额外需要 Android SDK，并设置 `ANDROID_HOME`。

| 目标 | Windows | macOS / Linux |
| --- | --- | --- |
| 启动桌面端 | `gradlew.bat desktop:run` | `./gradlew desktop:run` |
| 构建桌面端 | `gradlew.bat desktop:dist` | `./gradlew desktop:dist` |
| 启动服务端 | `gradlew.bat server:run` | `./gradlew server:run` |
| 构建服务端 JAR | `gradlew.bat server:dist` | `./gradlew server:dist` |
| 打包服务端部署包 | `gradlew.bat server:deploy` | `./gradlew server:deploy` |
| 运行测试 | `gradlew.bat tests:test` | `./gradlew tests:test` |
| 资源打包 | `gradlew.bat tools:pack` | `./gradlew tools:pack` |

服务端构建完成后的 JAR 位于 `server/build/libs/server-release.jar`。该目录是本地构建输出，已被 Git 忽略。
## 强烈建议
我们建议你安装前端管理面板以及它的依赖
https://github.com/tomorrowsetout/YFM/tree/main/plugins/WEB%20YFUI 该前端管理面板可适用于更好的管理如玩家检测等等 
https://github.com/tomorrowsetout/YFM/tree/main/plugins/WEB%20YFUI%20Adapter 请使用前端的依赖来作为基础如果其他服务器想使用该前端直接将依赖移植即可 请在依赖更换登录token或名称
## 项目结构

| 路径 | 说明 |
| --- | --- |
| [`core`](core/) | 游戏核心逻辑、资源、网络与 UI。 |
| [`server`](server/) | 专用服务器与 YZF 扩展框架。 |
| [`server/src/mindustry/yzf`](server/src/mindustry/yzf/) | YZF 主要 Java API、模块、服务与脚本桥接实现。 |
| [`server/config`](server/config/) | 可随源码分发的服务端与 YZF 示例配置。 |
| [`runtime-sdk`](runtime-sdk/) | 服务端运行时 SDK。 |
| [`server/kotlin-plugin-template`](server/kotlin-plugin-template/) | Kotlin 插件开发与打包模板。 |
| [`desktop`](desktop/) / [`android`](android/) / [`ios`](ios/) | 各客户端平台模块。 |
| [`annotations`](annotations/) / [`tools`](tools/) / [`tests`](tests/) | 注解生成、开发工具与自动化测试。 |

## API 与开发文档

完整 API 入口见 [`docs/API.md`](docs/API.md)。详细开发规范见 [`docs/API规范/README.md`](docs/API规范/README.md)，由 **月月岛科技 · YUEYUEDAO TECH** 维护（[组织主页](https://github.com/MonthZifang/YUEYUEDAO-TECH)）。

- [API 能力注册表](server/src/mindustry/yzf/YZFOpenApiRegistry.java)：运行时生成可查询的能力清单，按只读/写入权限区分。
- [JavaScript API 桥接](server/src/mindustry/yzf/YZFJsModuleBridge.java)：`yzf.*` 脚本 API 实现入口。
- [YZF 框架源码目录](server/src/mindustry/yzf/)：模块、事件、命令、服务、数据、权限与 WebSocket 核心接口。
- [服务端 YZF 配置](server/config/yzf/)：兼容层、数据库注册和远程数据库模板。
- [运行时 SDK](runtime-sdk/) 与 [Kotlin 库说明](runtime-sdk/kotlin-libs/README.txt)。
- [Kotlin 插件模板](server/kotlin-plugin-template/README.md)：插件 JAR 构建和模块元数据示例。
- [API 规范目录](docs/API规范/README.md)：完整 API、外部访问安全、远程接口/回放与配置目录规范。

## 服务端扩展开发

服务端入口类为 `mindustry.server.ServerLauncher`，YZF 在服务端启动时装配。

1. 从 [`server/config/yzf`](server/config/yzf/) 的配置和兼容示例开始。
2. 通过 [API 文档](docs/API.md) 和能力清单确认当前运行时提供的接口。
3. JavaScript 模块通过 `YZFJsModuleBridge` 暴露的 `yzf.*` 能力调用服务器功能。
4. Kotlin 插件从 [模板](server/kotlin-plugin-template/) 构建 JAR，并按模板 README 的模块元数据安装。
5. 外部 HTTP、WebSocket、数据库或对象存储应先配置相应权限与服务定义。

## 配置与数据安全

- `server/build/`（包含 `server/build/libs/`）是构建/运行目录，不会上传。
- 请勿把密钥、令牌、生产数据库密码或个人数据写入可提交的配置文件。

## 贡献

提交规范和代码风格见 [`CONTRIBUTING.md`](CONTRIBUTING.md)。问题说明参考 [`ISSUES.md`](ISSUES.md)，翻译说明见 [`TRANSLATING.md`](TRANSLATING.md)。

Mindustry 资料：[Wiki](https://mindustrygame.github.io/wiki) · [上游 JavaDoc](https://mindustrygame.github.io/docs/) · [上游仓库](https://github.com/Anuken/Mindustry)
