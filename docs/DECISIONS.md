# 关键设计决策

## ADR-001 Server 是唯一会话真相

状态：Accepted

决定：Android 只连接 OpenCode Server，不建立第二个 session/message store。

原因：执行、pending permission/question、event bus 是 process-local；复制 DB 无法复制 live state。

后果：离线历史浏览和独立本地草稿不是当前目标；未来若实现也不能冒充 server state。

## ADR-002 Desktop 和 Android 使用同一 shared process

状态：Accepted

决定：显式运行 `opencode web/serve`，Desktop 作为 HTTP client 连接。

拒绝：Android 连接 Desktop 随机 sidecar。

原因：sidecar loopback、随机端口和随机密码是正确安全边界；运行中 process state 不能迁移。

## ADR-003 Native-first + system Custom Tab

状态：Accepted

决定：sessions/run/prompt/stop/permission/question/Todo/Diff 原生实现；长尾 desktop features 打开官方 Web UI。

拒绝：WebView 套壳。

原因：WebView 会复制浏览器认证/cookie/安全维护，又无法提供最佳移动监督体验。

## ADR-004 Bridge 只做只读 metadata enrich

状态：Accepted

决定：Bridge 只暴露 tab identity、recent、model preferences 和 LAN metadata。

拒绝：Bridge 读取 messages、provider auth 或代理 session API。

原因：避免第二 session source，最小权限，Desktop private schema 失败时主功能仍工作。

## ADR-005 Bridge token 独立于 Basic password

状态：Accepted

决定：256-bit random Bearer token，Host DPAPI、Android Keystore。

拒绝：复用 Basic password、由 Basic password 派生 bridge verifier。

原因：bridge Origin 不需要也不应获得控制 shared server 的凭据。

## ADR-006 Debug provisioning 使用 DUMP-protected receiver

状态：Accepted

决定：Receiver 仅在 debug source set，`exported=true` + `android.permission.DUMP`。

拒绝：Launcher Activity extras、普通 exported receiver、Release 自动 provisioning。

原因：普通 App 不应能设置 bridge URL/token，避免 credential exfiltration chain。

## ADR-007 Endpoint-level compatibility

状态：Accepted

决定：每个 endpoint 独立 fallback，通常仅在 404/405。

拒绝：一次 health probe 后设置全局 legacy/current boolean。

原因：OpenCode 1.18.25 同一 server 暴露混合 legacy/current/experimental surface。

## ADR-008 SSE 先订阅后 snapshot

状态：Accepted

决定：subscribe → buffer → snapshot → atomic apply → replay → live。

拒绝：snapshot-first 或 event/snapshot 并行直接写 UI。

原因：global stream 无 replay cursor，必须封闭 lost-update window。

## ADR-009 Generation + epoch 双隔离

状态：Accepted

决定：generation 管连接候选，epoch 管已激活连接上的旧 async result。

原因：只取消 Job 不足以阻止已完成 callback 或竞态更新污染新 state。

## ADR-010 Cross-project bounded concurrency

状态：Accepted

决定：all-project snapshot 对不同 directory 使用 `Semaphore(6)`。

原因：避免大历史库一次性产生过多 status/permission/question 请求，同时保持可接受延迟。

## ADR-011 Partial directory failure preserves old state

状态：Accepted

决定：局部网络错误保留该目录旧 blocking/status state；401/403 全局失败。

原因：瞬时失败不应在 UI 中伪造“请求已消失”。

## ADR-012 Active child projection to root

状态：Accepted

决定：沿 `parentID` 最多 16 层，将 active child 投影到可见 root，2 秒 polling 校准。

原因：root-only session list 否则漏掉 subagent execution。

## ADR-013 Private/shared Desktop identity separation

状态：Accepted

决定：Bridge 和 UI 分开存储/显示 private/shared sets。

拒绝：只按 session ID 判断 Desktop opened。

原因：同一 DB 中相同 session ID 不代表同一 live process。

## ADR-014 Model list follows connected providers + Desktop visibility

状态：Accepted

决定：server `connected` 决定可选 provider，Desktop show/hide/MRU 和 six-month family 规则决定展示。

拒绝：展示完整 models.dev catalog 或伪“默认”模型。

原因：与 Desktop 实际可用项一致，避免选择未配置模型。

## ADR-015 Composer relies on Scaffold insets

状态：Accepted

决定：Scaffold `safeDrawing` 负责 layout；Composer 不重复 `imePadding/navigationBarsPadding`。

原因：OnePlus 真机双重 inset 产生大块键盘空白。

## ADR-016 Wireless ADB reverse as unplugged development path

状态：Accepted for development

决定：同一可信 Wi-Fi 中用 TCP ADB 保留 reverse。

拒绝：把明文 server 直接公网暴露。

原因：不需要管理员防火墙即可保持 phone loopback URL，但不适合作为发布架构。

## ADR-017 Desktop migration creates new window IDs

状态：Accepted

决定：迁移 tab data 后生成新的 window IDs。

原因：旧 Chromium Local Storage 可按旧 ID 恢复 sidecar last-active-route，覆盖迁移意图。

## ADR-018 Desktop migration is backup-first

状态：Accepted

决定：health/auth 验证后、写入前备份 settings/global/referenced windows。

原因：electron-store 是 PRIVATE schema，任何写入都必须可回退。

## ADR-019 Cleartext only for local development boundaries

状态：Accepted with constraints

决定：Android 允许 HTTP 以支持 loopback/LAN；UI 对非 loopback HTTP 警告。

原因：ADB reverse 和 local OpenCode server 不需要证书；WAN 必须 VPN/HTTPS。

## ADR-020 Documentation distinguishes baseline from history

状态：Accepted

决定：`BASELINE` 保存稳定事实，`status.cmd` 保存实时检查方法，`STATE_SNAPSHOT`/`ARCHIVE` 保存历史证据。

原因：进程、标签、ADB、IP 和 session 数快速变化，混写会误导新对话。
