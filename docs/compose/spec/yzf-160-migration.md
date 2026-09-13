---
feature: yzf-160-migration
status: delivered
updated: 2026-09-13
branch: local-160.3 (in-place, no git)
commits: n/a
---

# YZF 框架迁移至 Mindustry 160.3

## Report

**What was built** — 将月见草 YF 框架从 Mindustry 159.7 完整迁移到 160.3：YZF 服务端包（99 个类）、YZFBridge、增强版 ServerControl/ServerLauncher、core 网络与事件钩子（同步指标、可靠性、流量整形、Health/Player/Logic/包事件）、构建与配套资产（build.gradle、编译脚本、config/yzf、docs、frontend、runtime-sdk、core-network-modules）。兼容 160.3 底座差异：保留其数据资产与缓冲区逻辑；PhysicsProcess 无 end 回写，故未移植 noPlayerHitBox 物理跳过钩子；补回 `Vars.serverTps/actualServerTps` 与 `NetworkIO` 版本广告覆盖 API。

**Verification** — `$env:ORG_GRADLE_PROJECT_noLocalArc='true'; $env:ORG_GRADLE_PROJECT_buildversion='160.3'; gradlew.bat :server:dist --no-daemon` → BUILD SUCCESSFUL。产物 `server/build/libs/server-release.jar`（约 35MB）含 `mindustry/yzf/MindustryYZF.class`、`YZFBridge`、带 TPS 监控的 ServerLauncher、`yzf/yzf-core-sources.zip`。

**Journey log** — 159.7 无干净原版基线，增量靠 YZF 引用扫描 + 增量文件拷贝 + 钩子补丁；ServerControl 增强面过大，直接采用 159.7 全量作为基座；160.3 无 git，就地迁移（未建 worktree）。评审后补回 writeStateSnapshot 真实 TPS、ArcNetProvider 收包计数，并将编译脚本/品牌/兼容层/frontend 版本字符串统一为 160.3；二次编译通过。

## [S1] Problem
将月见草 YF 框架从 Mindustry 159.7 完整迁移到 Mindustry 160.3，并兼容 160.3 新增特性，使服务端可编译、可启动。

## [S2] Design
- 以 160.3 原版为底座，移植 159.7 中全部 YZF 增量。
- 服务端：`server/src/mindustry/yzf/**`、`YZFBridge`、`ServerControl`、`ServerLauncher`、`ServerBrandingConfig`、`ServerPerformanceConfig`。
- core 钩子：网络事件、同步指标、Administration 配置、Health/Player/Logic 事件、ShapedConnection 流量整形。
- 构建与配套：`server/build.gradle`、编译脚本、`config/yzf`、`docs`、`frontend`、`runtime-sdk`、`core-network-modules`。
- 160.3 新特性：保留其 ServerControl 数据资产、缓冲区、rhino 版本等底座差异；PhysicsProcess 无 end 回写，`noPlayerHitBox` 钩子不再需要。
- 工作区：目标目录 `Mindustry-160.3` 无 git，就地迁移；源目录 `Mindustry-159.7` 只读。

## [S3] Out of Scope
- 不修改 159.7 源目录。
- 不引入 Android/desktop 客户端完整重打包（以 server:dist 为主）。
- 不重写 YZF 业务模块逻辑，仅做版本适配。

## Tasks
- [x] T1: 分析改动面与 API 差异 — acceptance: 清单覆盖 server/core/构建/资产 (covers: S2)
- [x] T2: 迁移 YZF 服务端与启动器 — acceptance: yzf 包与 ServerControl/Launcher 集成齐全 (covers: S2)
- [x] T3: 迁移 core 网络/事件钩子 — acceptance: Net/Administration/Events/ArcNetProvider 钩子到位 (covers: S2)
- [x] T4: 迁移构建配置与配套资产 — acceptance: build.gradle/脚本/config/docs 已拷贝并改版本 (covers: S2)
- [x] T5: 编译服务端验证 — acceptance: `:server:dist` 成功产出 server-release.jar (covers: S2)
