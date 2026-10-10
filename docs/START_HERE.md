# 新对话从这里开始

本文档是 OpenCode Mobile 项目的快速入口。新 Agent 或新对话在修改代码前，按以下顺序读取：

1. `docs/BASELINE.md`
2. `docs/ARCHITECTURE.md`
3. `docs/INDEX.md`
4. 与任务对应的专题文档

先运行：

```powershell
.\scripts\status.cmd
```

不要根据历史对话、端口号、手机 IP 或 `docs/STATE_SNAPSHOT_2026-09-01.md` 假设当前环境。`status.cmd` 才是无敏感输出的实时检查入口。

## 项目目标

**Android/Desktop 共享与自启动的主机退役清理已完成，原两窗和共 23 个 sidecar 标签已恢复。** Desktop 使用自身的 local/sidecar 独立运行。Android 客户端源码保留；本项目不再安装、启动、监督或迁移 Desktop 到共享服务。

2026-10-06 15:44 核对时，主机 marker 为 `complete`，Mobile Worker/managed Server 已停止，共享默认绑定和指定连接已移除，4096/4097 均未监听。随后在任务空闲后正常关闭唯一窗口完成恢复，未强制终止进程；两原窗口 UUID、现有窗口及今天新增标签保留。草稿冷备份与恢复启动后的快照哈希完全一致，包含 654 documents、139 个非空 text parts、0 个附件 blobs。全部 139 个非空输入尚未逐条通过 UI 验收，ADB reverse、防火墙和 Android SDK 构建缺口仍保留。标签 metadata 或独立副本完整不能代替全部输入 UI 验收。

先读 `docs/RETIREMENT.md`。仓库禁止启动标记只证明旧入口拒绝启用，不能证明主机已清理完成。

## 当前最重要事实

- 当前主机阶段为 `complete`；原两窗 UUID 及 23 个 sidecar 标签已恢复，全部输入的逐条 UI 验收仍有缺口。
- `retire-opencode-mobile.cmd` 默认只预览；`-Apply` 才进行全量退役。
- 完整退役要求已验证的输入备份、Desktop 正常关闭、共享任务为 0 和精确资源身份。
- 保留数据库、当前标签、窗口 IDs 和离线回退材料；不恢复旧日期的整个 Desktop 快照来退役。
- 旧复活入口 fail closed；历史操作说明不构成重新启用授权。
- OpenCode 协议研究基线仍为 `1.18.25`；Desktop、CLI 和端口现状必须重新核对。
- Android 不保存第二份会话数据库，Basic 与 Bridge 凭据的 Origin 隔离继续保留。

## 任务导航

| 任务 | 先读 |
|---|---|
| 退役、输入保全、清理残留、独立 Desktop、回退 | `docs/RETIREMENT.md` |
| API、事件、解析、兼容 | `docs/PROTOCOL_COMPATIBILITY.md` |
| ViewModel、竞态、重连 | `docs/ARCHITECTURE.md`, `docs/CODE_MAP.md` |
| Desktop 标签/历史迁移 | `docs/RETIREMENT.md`, `docs/DESKTOP_INTEGRATION.md` |
| 密码、令牌、网络边界 | `docs/SECURITY.md` |
| 当前运维和历史启动机制 | `docs/OPERATIONS.md` |
| 报错和设备问题 | `docs/TROUBLESHOOTING.md` |
| 模型列表和 UI parity | `docs/UI_MODEL_PARITY.md` |
| 构建、测试、验收 | `docs/TESTING.md` |
| 为什么这样设计 | `docs/DECISIONS.md` |
| 本轮探索和踩坑 | `docs/DEVELOPMENT_ARCHIVE_2026-09-01.md` |

## 不可破坏的约束

- 不要为手机建立第二个 session/message 数据库。
- 不要读取或绕过 Desktop 私有 sidecar 的随机密码。
- 不要把 OpenCode Basic 密码发送到 bridge Origin。
- 不要把 sidecar 标签标记成共享标签。
- 不要在 Agent 运行中切换 Desktop 后端。
- 不要把 Debug bridge provisioning 放到导出的 Launcher Activity。
- 不要将未知 JSON/event 字段视为错误。
- 不要用单个全局“协议版本”替代端点级 fallback。
- 不要提交 Desktop 状态、OpenCode DB、迁移备份、DPAPI 文件、Provider auth、签名密钥或含凭据日志。

## 完成定义

修改代码后至少运行：

```powershell
.\scripts\build.cmd
.\scripts\build.cmd -Release
```

协议 shape 变化必须增加 parser 或 wire-contract test。安全、脚本、端口、凭据、迁移行为或验证命令变化必须同步更新文档。
