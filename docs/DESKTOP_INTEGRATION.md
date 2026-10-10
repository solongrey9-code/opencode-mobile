# Desktop 集成

## 当前：解除共享接管

共享服务器、bridge 和自启动的主机退役清理已完成，标记为 `complete`；原 managed Server 已停止，共享默认绑定和指定 server entry 已移除，旧迁移入口拒绝启用。Desktop 使用自身的 local/sidecar。原两窗 UUID 和共 23 个 sidecar 标签已恢复，现有窗口及今天新增标签保留；任务空闲后正常关闭唯一窗口执行，未强制终止进程，只补缺失窗口文件并在最新 settings 追加原引用。3 个原共享已关闭 session 标签在草稿 namespace 检查后重绑定。草稿冷备份与恢复启动后的哈希一致，全部 139 个非空输入尚未逐条通过 UI 验收，不能用旧完整状态快照覆盖当前工作。

退役从当前文件做精确变更，不恢复旧 migration backup。保留当前 tabs、draft IDs、recent/info 和 window IDs；只移除指定共享默认/server entry，并把已关闭的指定 shared session 身份改回 sidecar。仍打开的 shared session/draft 或已关闭 shared draft 会拒绝自动处理。

输入文本实际位于 `drafts.sqlite`。恢复期间继续用一致的 SQLite online backup 保全 WAL、文本及附件，并按时点核验输入；tab metadata 和 electron-store SHA-256 备份不能替代该门槛。数据库见证文本仍在不等同于当前可见输入或全部 UI 恢复已验证。任何后续关闭/Apply 前仍须确认任务安全，详见 `RETIREMENT.md`。

以下第 1～10 节是退役前的 PRIVATE schema 和同步设计参考。旧 bridge/migration 入口已拒绝启用，不是当前操作流程；旧版本白名单不能推导新版本安全可迁移。

## 1. 为什么不能直连 Desktop 私有 sidecar

OpenCode Desktop 1.18.25 每次启动：

- 只绑定 `127.0.0.1`
- 选择随机空闲端口
- 生成随机 UUID Basic password
- username 为 `opencode`
- CORS 仅允许 `oc://renderer`
- password 通过 Electron process/IPC 传递，不公开持久化

这是一条安全边界。不要读取进程内存、绕过 401 或从私有 renderer 提取密码。

更重要的是，执行状态、event bus、pending permission/question 是进程内状态。即使两个进程读同一个 SQLite DB，也不能共享正在运行的 Agent。

## 2. Live sync 的正确拓扑

Desktop、Android 和 TUI 必须连接同一个显式 shared server：

```text
opencode web/serve :4096
```

切换前必须完成或停止当前 Agent。进程中的执行无法迁移。

## 3. Electron store

Windows production user-data：

```text
%APPDATA%\ai.opencode.desktop
```

关键文件：

```text
opencode.settings
opencode.global.dat
opencode.window.<window-id>.dat
opencode.workspace.*.dat
```

文件是 UTF-8 JSON，不是加密二进制。但很多 value 是第二层 JSON string。

`electron-store` 配置：

- 无扩展名
- `accessPropertiesByDotNotation=false`
- dotted key 是字面 key

## 4. Tab schema

```ts
type Tab =
  | { type: "session"; server: string; sessionId: string }
  | { type: "draft"; server: string; draftID: string; directory: string; worktree?: string }
```

Window file：

```json
{
  "tabs": "JSON.stringify(Tab[])",
  "tabs.recent": "JSON.stringify({key?})",
  "tabs.info": "JSON.stringify(Record<tabKey,info>)",
  "tabs.closed": "JSON.stringify([...])"
}
```

Server identity：

- built-in：`sidecar`
- HTTP：normalized URL
- WSL：`wsl:<distro>`
- SSH：`ssh:<host>`

Session tab key：

```text
<server>\n/server/<base64url(server)>/session/<sessionID>
```

`tabs.recent` 是最近标签，不一定是 OS 当前前台窗口。多个 Desktop window 时，bridge 只能报告每个 window 的 recent；全局 focused window 在内存中。

`desktopUsesSharedServer` 只表示 Desktop `defaultServerUrl` 是否为 shared URL。当前/最近标签身份仍必须由 `privateRecentSessionId` 和 `sharedRecentSessionId` 判断。

## 5. Read-only bridge

`scripts/desktop-bridge.mjs`：

- GET-only `/health` 和 `/state`
- 独立 256-bit random Bearer token
- timing-safe token compare
- `Cache-Control: no-store`
- `X-Content-Type-Options: nosniff`

读取：

- window IDs
- open session tabs
- recent tab
- model show/hide/MRU
- host LAN interfaces

不会读取/返回：

- message content
- provider auth
- OpenCode Basic password
- private sidecar password
- whole Desktop store

## 6. Shared/private identity

Bridge 分别返回：

```text
privateOpenSessionIds
sharedOpenSessionIds
privateRecentSessionId
sharedRecentSessionId
```

Android 不得把两组 union 后展示为共享：

- shared：蓝色“桌面打开/桌面最近”
- private：黄色“桌面私有/私有最近”

private 标签只证明 Desktop UI 打开同一数据库 session，不证明执行状态共享。

## 7. Bridge credential

Host：

```text
%LOCALAPPDATA%\OpenCodeMobile\bridge-token.dat
```

- 32 random bytes → 64 hex chars
- Windows current-user DPAPI via SecureString
- 不从 Basic password 派生
- 不打印 plaintext token

Android：

- explicit bridge URL
- AES-GCM encrypted bridge token
- 与 Basic password 分开字段

Debug 自动 provisioning：

- receiver 仅存在于 debug source set
- exported 但要求 `android.permission.DUMP`
- 只有 ADB shell/system 可调用
- Launcher Activity 不读取 provisioning extras

Release 需要手工输入 bridge URL/token 或未来受控 pairing UX。

## 8. Desktop migration

安全前提：

1. 当前 Agent 已完成/停止。
2. Desktop 回到 Home。
3. Desktop 已关闭。
4. Shared server health + Basic auth 成功。

执行：

```powershell
.\scripts\configure-desktop-shared.cmd -ConfirmHome
```

脚本：

1. 验证 Desktop process 已关闭。
2. 验证 shared health/auth。
3. 创建 timestamped backup。
4. 设置 `defaultServerUrl`。
5. 添加 shared HTTP server entry。
6. 将 sidecar session tabs/recent/info key 重写到 shared URL。
7. 创建新 window IDs。
8. 保留旧 window files 作为回退证据。
9. 要求手动启动 Desktop。

新 window IDs 避免复用 Chromium Local Storage 中旧 window ID 的 `last-active-url`，从而强制新窗口从 Home 路由开始。

## 9. Backup / rollback

Backup：

```text
%APPDATA%\ai.opencode.desktop\mobile-shared-backup-<timestamp>
```

包含：

- `opencode.settings`
- `opencode.global.dat`
- 被引用 window files

Rollback：

1. 关闭 Desktop。
2. 将 backup 中文件复制回 user-data。
3. 启动 Desktop。

新迁移 window files 可以留在磁盘，恢复后的 `windowIds` 不会引用它们。

## 10. 当前状态解释

Desktop default server 指向 shared URL 不代表每个 tab 都是 shared。用户仍可打开 built-in local/sidecar，导致 mixed tab state。

每次诊断运行：

```powershell
.\scripts\status.cmd
```

重点看：

```text
Session tabs: total=..., shared=..., private-sidecar=...
Bridge: desktopUsesShared=...
```
