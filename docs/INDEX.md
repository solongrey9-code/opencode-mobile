# 文档索引

## 核心入口

| 文档 | 角色 | 更新条件 |
|---|---|---|
| `START_HERE.md` | 新对话最短入口 | 项目目标、阅读顺序或约束变化 |
| `BASELINE.md` | 当前稳定基线 | 版本、功能、质量门槛或拓扑变化 |
| `ARCHITECTURE.md` | 数据流和并发架构 | 状态源、同步屏障、组件边界变化 |
| `CODE_MAP.md` | 文件/职责地图 | 文件新增、移动、职责重构 |
| `RETIREMENT.md` | 当前退役流程、输入保全、验收和离线回退边界 | 清理范围、保护条件或实际验收变化 |

## 专题参考

| 文档 | 内容 |
|---|---|
| `PROTOCOL_COMPATIBILITY.md` | OpenCode 1.18.25 API、SSE、legacy/current 兼容 |
| `DESKTOP_INTEGRATION.md` | 独立 Desktop 边界及历史 sidecar、bridge、迁移设计 |
| `SECURITY.md` | 威胁模型、凭据、Origin、LAN/WAN、禁入库数据 |
| `OPERATIONS.md` | 当前检查、退役、构建；历史启动/连接流程 |
| `TROUBLESHOOTING.md` | 错误签名、根因、诊断和修复 |
| `TESTING.md` | 测试矩阵、质量门槛、真机验收 |
| `UI_MODEL_PARITY.md` | OC-2 UI、模型筛选、运行/标签语义、IME |
| `DECISIONS.md` | 关键设计决策与被拒绝方案 |
| `GLOSSARY.md` | 项目术语 |
| `ROADMAP.md` | 已知缺口和优先级 |

## 兼容别名文档

以下是早期英文参考，保留供搜索和外部链接使用；若有冲突，以 canonical 中文文档为准：

| 旧文档 | Canonical 文档 |
|---|---|
| `CODEBASE_MAP.md` | `CODE_MAP.md` |
| `RUNBOOK.md` | `OPERATIONS.md` |
| `MODEL_PARITY.md` | `UI_MODEL_PARITY.md` |

## 历史和研究归档

| 文档 | 类型 | 注意事项 |
|---|---|---|
| `DEVELOPMENT_ARCHIVE_2026-09-01.md` | 本轮开发历程/经验 | 说明踩坑、实验和演进，不代表实时状态 |
| `STATE_SNAPSHOT_2026-09-01.md` | 脱敏状态证据 | 历史证据，不能替代 `status.cmd` |
| `RESEARCH_SOURCES.md` | 官方源码/文档索引 | 固定 OpenCode 1.18.25 参考来源 |

## 文档使用规则

- 当前共享、自启动和桥接机制正在退役；先以 `RETIREMENT.md` 判断阶段和运维入口，旧操作仅供历史参考。
- `BASELINE.md` 只写稳定事实和明确标注的核对快照。
- `STATE_SNAPSHOT_*` 和 `DEVELOPMENT_ARCHIVE_*` 永远是历史证据。
- 私有 Desktop schema 必须标记为 PRIVATE，不应被描述成公共 API。
- 所有凭据示例使用占位符，不保存真实密码、token、设备序列号或 Authorization header。
- 文档中的命令必须能从仓库根目录执行，除非明确写出工作目录。
- 代码、脚本、端口、架构、安全边界和验证命令变更必须同步更新索引相关文档。
