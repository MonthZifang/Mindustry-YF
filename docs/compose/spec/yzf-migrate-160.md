---
feature: yzf-migrate-160
status: delivered
updated: 2026-09-13
branch: n/a (Mindustry-160.3 非 git 工作区；用户指定目标目录)
commits: n/a
---

# YZF 框架迁移到 Mindustry 160.3

## Report

**What was built** — 将月见草 YF 框架从 Mindustry 159.7 完整迁移到 160.3。服务端 `mindustry.yzf` 包（99 个类）、YZFBridge/品牌/性能配置、core 网络钩子（Net/NetConnection/NetServer/NetClient/Administration/ArcNetProvider）、自定义事件、构建脚本与配套资产（config/frontend/runtime-sdk/core-network-modules/docs）均已就位。以只读备份 `新建文件夹/Mindustry-160.3` 与 GitHub 原版为基底核对：原版 159.7→160.3 的 ServerControl 仅多 2 处 `RulesLoadEvent`，已合入 YZF 版 ServerControl；ServerLauncher/NetConnection 原版无差异，YZF 增量保留。`PhysicsProcess` 在 160 不再回写实体位移，`yzfNoPlayerHitBox` 的 end 钩子无需移植。

**Verification** — `gradlew.bat :core:compileJava :server:compileJava --rerun-tasks` BUILD SUCCESSFUL；`gradlew.bat :server:dist` BUILD SUCCESSFUL，产出 `server/build/libs/server-release.jar`（约 35MB）。烟测启动：`[MindustryYZF] 游戏版本: release build 160.3`，YZFBridge 外编 99 个源文件成功，性能/品牌配置应用，`Server loaded`。功能复验：`help`/`help 2`/`help host`/`help yzf` 输出 159.7 同款简体中文（默认 `helpLanguage=zh`、分页、别名「帮助/开服/状态/版本」、用法/说明/备注）；`host` 成功加载地图并 `Playing on map … / Wave 1`；TCP+UDP 6567 监听，JoinProbe 连接成功并收到 ArcNet 握手字节 `00 06 fe 04 6e 09 30 66`。完整 GUI 客户端入局未在本机自动化跑通（官方 client 下载中断），协议层已验证。

**Journey log** — 整文件覆盖 ServerControl 会丢失 160.3 的 `RulesLoadEvent`；应以原版 diff 驱动合并。Windows 路径 + CRLF 使 `git apply` 失败，改用「YZF 基底 + 原版小 diff」更稳。`新建文件夹/Mindustry-160.3` 作为只读原版基线，避免对上游再下载。

## [S1] Problem
159.7 上的 YZF 服务端框架需要在 Mindustry 160.3 上可用，并兼容 160 新增行为。

## [S2] Design
- 纯新增层整包迁移：`server/src/mindustry/yzf/**`、`YZFBridge`、`ServerBrandingConfig`、`ServerPerformanceConfig`、core YZF 网络类与事件、`arc/net/ShapedConnection`。
- 原版修改层以 160.3 为基底打钩子；ServerControl 以 159.7 YZF 版为基底并补上 160 的 `Events.fire(new RulesLoadEvent(state.rules))`（host/load 与 reload 两处）。
- 构建：`server/build.gradle` 使用 YZF 依赖与 `yzfCoreSources` 打包；`编译服务端.ps1` 设定 `buildversion=160.3`。
- 烟测验收：`java -jar server-release.jar` 能完成 YZFBridge bootstrap 并打出 build 160.3 公告；`help` 保持 159.7 简体中文分页/备注；`host` 后 6567 可被 TCP 握手。

## [S3] Out of Scope
- 不迁移 desktop/android 客户端 UI 之外的非服务端玩法改动。
- 不把 160.3 目录初始化为 git 仓库；备份目录只读。
- 不处理烟测时端口 7100/7101 被其它进程占用的环境问题。

## Tasks
- [x] T1: 分析 159.7 框架改动面与 160.3 API 差异 — acceptance: 列出 YZF 包、core 钩子与原版 delta (covers: S2)
- [x] T2: 迁移 YZF 服务端源码与 ServerControl/启动器集成 — acceptance: yzf 包 + Bridge + ServerControl 含 RulesLoadEvent (covers: S2)
- [x] T3: 迁移 core 网络/事件钩子并适配 160 API — acceptance: Net* 等含 YZF 钩子且 core 可编译 (covers: S2)
- [x] T4: 迁移构建配置、脚本、配置与配套资产 — acceptance: build.gradle/脚本/config/docs 存在 (covers: S2)
- [x] T5/T8: 编译验证 — acceptance: `:server:dist` 成功且烟测启动到 Server loaded (covers: S2)
