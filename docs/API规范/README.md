# API 规范

> **月月岛科技 · YUEYUEDAO TECH**
>
> 本目录的 API 规范由月月岛科技维护，面向 Mindustry YF Framework 的模块、插件与外部服务集成。
>
> - 组织主页：[YUEYUEDAO TECH](https://github.com/MonthZifang/YUEYUEDAO-TECH)
> - 项目 API 总入口：[`../API.md`](../API.md)

## 规范目录

| 文档 | 说明 |
| --- | --- |
| [YZF API 文档](YZF-API-文档.md) | 模块、插件、脚本运行时、JavaScript API、外部服务与命令的完整开发参考。 |
| [外部访问安全规范](外部访问安全规范.md) | Token、TLS、命令 Socket、外发 HTTP/HTTPS 与 WebSocket 的安全策略。 |
| [远程接口与服务端回放指南](远程接口与服务端回放指南.md) | 远程服务集成、外部进程协议，以及纯服务端回放记录方案。 |
| [配置目录架构](配置目录架构.md) | YZF 运行配置、数据库、服务和存储目录的结构说明。 |

## 使用约定

1. 对外集成前先查阅安全规范，确保公网访问启用 TLS、认证、路径白名单和最小权限。
2. 以运行时 API 能力清单为准；对应源码入口见 [`../API.md`](../API.md)。
3. 配置中的密钥、Token、生产密码和运行数据不应提交到仓库。
