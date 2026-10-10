# Android/Desktop 共享机制退役

更新：2026-10-06。用户要求退役整套 Android/Desktop 共享与自启动机制，并明确要求输入框内容绝不能丢失。Desktop 使用自身的 local/sidecar 独立运行，本项目不再接管 Desktop 后端或提供共享运行前提；原两窗及标签已恢复，全部输入的 UI 验收仍有缺口。

## 当前阶段与证据

**主机退役清理已于 2026-10-04 执行完成，标记为 `complete`。** 本项目登录启动已移除，精确 Mobile Worker/managed Server 已停止，Desktop 共享默认连接与指定 server entry 已移除，Tailscale 4096/4097 映射已撤销，其他 443/8443 映射保留，managed runtime 和凭据已离线归档。旧入口继续拒绝启用。

2026-10-06 15:44 的只读状态显示：4096/4097 均未监听、登录启动不存在、Worker 未运行、managed CLI 已不在活动安装位置、Desktop 默认共享绑定为空、共享连接数为 0。随后在任务空闲后正常关闭唯一窗口，未强制终止进程；补回缺失的原窗口文件，并在最新 settings 追加原窗口引用，保留两原窗口 UUID、现有窗口及今天新增标签。Desktop `1.18.34` 原两窗已实际恢复，分别有 11 和 12 个 sidecar 标签，共 23 个。3 个原共享的已关闭 session 标签在草稿 namespace 检查后重绑定到 sidecar。139 个非空输入尚未逐条通过 UI 验收。ADB ready device 为 0，设备 reverse 和防火墙残留检查仍有缺口。

输入保全已经执行：

- 2026-10-04 只读备份并 SHA-256 核对了 118 个 Desktop electron-store 文件；这只证明当时磁盘文件的副本完整，不证明编辑器输入已保存。
- 另用 SQLite online backup 保存真实 `drafts.sqlite`，包含 WAL 中已提交状态和附件 blobs；快照 integrity check 通过。
- 2026-10-06 恢复前冷备份与恢复启动后的快照 SHA-256 完全一致：`d75881a2ad9ffe4d4097353a3c6632af0ad9a01f4665250341dbf1bcce9929df`；快照包含 654 documents、139 个非空 text parts、0 个附件 blobs。2026-10-04 副本中的 4 个附件 blobs 仍保留，附件 SHA-256 与引用完整性检查通过。
- 14 字符数据库见证文本仍在已验证副本中，恢复用 JSON 副本回读一致；未输出草稿内容。这不是当前可见输入或后续新输入的验证结果。

这些数字仅是对应时点的核对快照。两窗和标签实际恢复、独立副本完整及重启前后哈希一致，均不能代替全部 139 个非空输入和附件的逐条 UI 验收。继续输入后必须重新取得有效快照；旧快照不证明新编辑内容已经保存。

## 当前入口

从仓库根目录运行：

```powershell
.\scripts\status.cmd
.\scripts\retire-opencode-mobile.cmd
```

第二条命令为预览，只检查清理计划和共享服务空闲状态，不停止进程或写 Desktop 状态。不能确认资源归属、响应 shape 或活动任务时拒绝继续。

只禁用登录自启动并保持所有当前窗口、输入和进程：

```powershell
.\scripts\retire-opencode-mobile.cmd -DisableStartupOnly
```

该模式曾作为先行保护阶段执行。它不停止现有 Worker/Server、不移除网络映射、不改 Desktop 文件，不能单独作为主机清理完成证据；之后已执行完整清理，当前标记为 `complete`。

## 输入保全硬门槛

禁止用 current tabs、draft IDs、window IDs 或旧 migration backup 代替真实输入备份。Desktop 的未发送内容持久化在 `drafts.sqlite`，附件也存于该数据库；直接复制一个正在写 WAL 的主数据库文件不能证明快照一致。

`scripts/backup-desktop-inputs.py` 以 SQLite read-only source + online backup 创建一致快照，验证数据库、附件内容哈希、附件引用和恢复用 JSON；使用 `--expected-text-file` 对当前可见输入作精确见证。见证文本和快照都含用户私有内容，必须放在仓库外，不能打印到日志或提交。

完整退役前必须满足：

1. 真实输入/附件快照成功，当前可见输入精确验证通过；任何其他窗口或输入变化也须得到对应保全。
2. 原始输入数据库及可读恢复副本可用，校验凭据与快照 SHA-256 一致。
3. 完成或停止 Desktop 的私有任务与共享任务，再正常关闭 Desktop；本脚本不会关闭或强制终止 Desktop。
4. 关闭后再次备份真实输入数据库文件和最新 Desktop 状态，确认没有新的写入竞态。

SQLite backup 只能覆盖已提交数据；仅有 500ms 自动保存或 quit flush 的实现说明不能代替当前输入的实际验证。不要为了取快照而先关闭 Desktop。

## 全量退役

在上述输入保全和任务安全条件满足后，提供包含 `drafts.sqlite`、`draft-documents.json` 和 `input-backup-verification.json` 的已验证外部目录：

```powershell
.\scripts\retire-opencode-mobile.cmd -Apply -InputBackupDirectory "<verified external draft-database directory>"
```

脚本要求 Desktop 已关闭，核验输入见证与快照哈希，读取当前状态并备份，然后再次确认共享任务为 0 和 Desktop 未重新打开。无法确认时拒绝操作。脚本只处理明确属于本项目的资源：

Apply 还会以 `scripts/backup-desktop-inputs.py` 创建关闭后的最终一致快照，并按这份最新副本检查即将重绑定的关闭会话；如果共享命名空间仍有未发送文字、附件或上下文，则拒绝重绑定，避免保留了数据库却改变恢复查找键。此步骤需要 Python 3.10 或更新版本。

| 资源 | 处理方式 |
|---|---|
| HKCU 登录启动 | 仅移除 `OpenCode Mobile Runtime` 且命令指向已知 Mobile Worker 的项 |
| Mobile Worker/Server | 匹配精确脚本路径、managed binary、4096 端口和父子关系；通过 stop request 等待清理，不批量 kill OpenCode/Node |
| Desktop 默认/server entry | 仅移除 `http://127.0.0.1:4096` 的匹配连接；保留其他服务器 |
| 当前 Desktop 标签与窗口 | 原样保留，当前 shared tab/draft 若仍打开则拒绝 Apply；不创建新 window IDs |
| 已关闭共享 session 标签 | 仅将指定 server 身份改为 `sidecar`，保留 session ID、顺序、index 及未知字段；共享 draft 则拒绝自动处理 |
| Tailscale Serve | 仅移除检查确认的 TCP 4096/4097 → 同端口 loopback 映射；保留其他 Serve 服务与 VPN 设置，不执行全局 reset |
| Mobile runtime、managed CLI、DPAPI 文件 | 移入本次离线备份，保留回退材料 |
| OpenCode 数据库、会话与 Provider auth | 不修改、不删除 |

Apply 的 Desktop 写入失败会从本次备份恢复原文件。阶段标记为 `complete` 只表示脚本清理完成，最终仍须核对 Desktop 重开、输入恢复和独立运行。

## 禁止复活

仓库 `scripts/shared-integration.retired` 和主机 `%LOCALAPPDATA%\OpenCodeMobile\integration-retired.json` 用于阻止旧机制再次启动。以下旧入口拒绝启用：

- `install-runtime`、`runtime-worker`、`start-opencode-mobile`。
- `configure-desktop-shared`、`migrate-desktop-now`、`desktop-bridge`。
- `configure-tailnet-mobile`、`install-debug` 的共享 provisioning、`enable-wireless-adb`、`attach-shared`。

Android 源码、构建、签名检查和只读状态检查仍保留。不要删除 marker 或把历史命令当作当前修复方案。退役后 `4096/4097` 不再监听本项目服务是预期结果。

## 验收与未完成项

最终完成需要以下全部证据：

- 登录启动不存在，仓库及未来 Worker/Bridge 入口拒绝启用。
- 仅精确 Mobile Worker/Server 被停止，无同名批量 kill；Desktop 私有任务安全结束。
- Desktop 默认共享连接及指定 entry 已移除，当前标签和窗口 IDs 保留。
- 本项目 Tailscale 4096/4097 映射不存在，其他映射保留。
- 核对本项目防火墙规则及已授权设备上的精确 ADB reverse；只按确认归属清理，不清空整个 ADB reverse 或关闭无关网络服务。无设备在线时应保留该验收缺口。
- 正常重开 Desktop，全部未发送输入及附件实际可恢复，历史会话和当前标签可用，Desktop 独立 local/sidecar 可执行。
- Debug 与 Release 两条质量门槛、文档检查及退役专项验证通过。

本次脚本管理的主机清理已完成，原两窗 UUID 和共 23 个 sidecar 标签已恢复，现有窗口及今天新增标签保留。全部 139 个非空输入及附件的逐条 UI 验收、设备 reverse 和防火墙残留核验仍有缺口。主机 marker `complete`、独立副本完整和草稿哈希一致，均不能作为全部用户工作恢复已经验收的证据。

本轮代码验证：Desktop 纯规划 fixture 15 项通过，输入在线备份专项 4 项通过；PowerShell/Node 语法与 24 份文档检查通过。Debug、Release 两条构建均已执行，但当前主机缺少 Android SDK，均在任务依赖解析阶段报 `SDK location not found`，未取得新的 Android 单元测试、Lint 或 APK 验证结果。旧构建产物不能替代本轮门槛。

## 离线回退边界

本次退役备份位于 `%LOCALAPPDATA%\OpenCodeMobile\retirement-backup-<timestamp>-<id>`；输入保全目录位于仓库外，主机 marker 会记录本次备份路径。不要提交或传播其中的 Desktop 状态、草稿、DPAPI 文件或凭据。

回退必须先停止相关任务并正常关闭 Desktop，核对所需文件与本次备份，再恢复必要的当前状态。不得直接覆盖旧日期的完整 migration backup，因为那会丢弃其后新增的标签和输入。输入恢复优先使用已校验快照与可读副本；处理当前数据库前还须保留当前版本。

恢复文件不会自动恢复共享执行内存态。重新启用旧共享运行时需要用户明确的新决定和新的安全验证，本退役脚本不自动复活服务、登录启动、Tailscale 或凭据注入。
