# YZF API 文档索引

本目录提供 YZF 服务端扩展 API 的文档入口。API 的权威定义在源码中，并由运行时能力注册表生成机器可读清单；本文档直接链接到实现，避免手写接口列表与代码脱节。

## 运行时能力清单

[`YZFOpenApiRegistry`](../server/src/mindustry/yzf/YZFOpenApiRegistry.java) 提供以下 JSON 输出：

| 方法 | 作用 |
| --- | --- |
| `manifestJson()` | 完整能力清单、运行时信息和读写分组。 |
| `listJson()` | 全部能力分组。 |
| `readOnlyJson()` | 只读能力分组。 |
| `writeOnlyJson()` | 写入能力分组。 |
| `infoJson(id)` | 指定能力分组的详细说明。 |
| `summaryJson()` | 能力与模块统计。 |
| `statusJson()` | 服务端状态数据。 |

调用接口前应查询能力清单，并根据 `read` / `write` 标记配置最小权限。能力清单由当前运行时生成，能够反映已启用模块和支持的脚本运行时。

## API 源码入口

| 类型 | 入口 |
| --- | --- |
| 脚本 API | [`YZFJsModuleBridge`](../server/src/mindustry/yzf/YZFJsModuleBridge.java) |
| 模块加载与注册 | [`YZFModuleLoader`](../server/src/mindustry/yzf/YZFModuleLoader.java)、[`YZFModuleRegistry`](../server/src/mindustry/yzf/YZFModuleRegistry.java) |
| 脚本运行时 | [`YZFScriptRuntime`](../server/src/mindustry/yzf/YZFScriptRuntime.java)、[`YZFJsRuntime`](../server/src/mindustry/yzf/YZFJsRuntime.java) |
| 服务调用 | [`YZFServiceManager`](../server/src/mindustry/yzf/YZFServiceManager.java)、[`YZFServiceRegistry`](../server/src/mindustry/yzf/YZFServiceRegistry.java) |
| 数据库与缓存 | [`YZFDatabaseRegistry`](../server/src/mindustry/yzf/YZFDatabaseRegistry.java)、[`YZFCacheClient`](../server/src/mindustry/yzf/YZFCacheClient.java) |
| 远程 HTTP / WebSocket | [`YZFRemoteHttpClient`](../server/src/mindustry/yzf/YZFRemoteHttpClient.java)、[`YZFWebSocketManager`](../server/src/mindustry/yzf/YZFWebSocketManager.java) |
| 权限与安全 | [`YZFPermissionManager`](../server/src/mindustry/yzf/YZFPermissionManager.java)、[`YZFSecurityConfig`](../server/src/mindustry/yzf/YZFSecurityConfig.java) |
| 事件、命令与状态 | [`YZFEventRegistry`](../server/src/mindustry/yzf/YZFEventRegistry.java)、[`YZFCommandRegistry`](../server/src/mindustry/yzf/YZFCommandRegistry.java)、[`YZFStatusUi`](../server/src/mindustry/yzf/YZFStatusUi.java) |

完整类目录见 [`server/src/mindustry/yzf`](../server/src/mindustry/yzf/)。

## 配置、运行时与插件

- [`server/config/yzf`](../server/config/yzf/)：源码随附的配置、兼容与数据库示例。
- [兼容层说明](../server/config/yzf/compat/README.md)：旧接口兼容配置。
- [`runtime-sdk`](../runtime-sdk/)：运行时 SDK；[Kotlin 库说明](../runtime-sdk/kotlin-libs/README.txt)。
- [`server/kotlin-plugin-template`](../server/kotlin-plugin-template/)：Kotlin 插件模板和构建说明。
- [`server/server_template`](../server/server_template/)：服务端部署脚本模板。

## 构建与验证

```text
# 编译并运行服务端
gradlew.bat server:run

# 构建服务端 JAR
gradlew.bat server:dist

# 构建包含部署脚本的服务器包
gradlew.bat server:deploy
```

生成的文件位于 `server/build/`，该目录属于本地构建输出，不应提交到 Git。
