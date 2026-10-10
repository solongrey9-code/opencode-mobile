# 术语

| 术语 | 含义 |
|---|---|
| Shared server | 显式启动、Desktop 和 Android 共用的 OpenCode Server process |
| Sidecar | Desktop 内置私有 OpenCode process，loopback + 随机端口/password |
| Session truth | session/message/execution/blocking request 的权威状态 |
| Bridge | 读取 Desktop metadata 的独立 Node HTTP service，不是 OpenCode API |
| Shared tab | Desktop tab 的 `server` 等于 shared URL |
| Private tab | Desktop tab 的 `server` 等于 `sidecar` |
| Root session | `parentID` 为空，主列表展示的 session |
| Child/subagent session | `parentID` 指向父 session，可能独立运行 |
| Snapshot | REST 获取的某时刻完整 sessions/status/blocking state |
| Reducer | 将 SSE event 归并到 `AppUiState` 的逻辑 |
| Barrier | SSE buffer 和 REST snapshot 之间避免 lost update 的同步阶段 |
| Generation | 一个 connect attempt 的身份 |
| Epoch | 一个已激活 server connection 的身份 |
| Located API | 由 directory/workspace 路由到 OpenCode instance 的 endpoint |
| Legacy API | root/unprefixed OpenCode API 和 event shape |
| Current API | `/api/*` 与 `session.next.*` typed protocol |
| Experimental | OpenAPI 暴露但不保证稳定的 endpoint，如 `/experimental/session` |
| Desktop parity | Android 在语义、可见模型和标签身份上接近 Desktop，而不是复制像素 |
| ADB reverse | Android loopback port 反向转发到 Host TCP port |
| Wireless ADB | TCP 5555 上的开发调试 transport，非产品网络方案 |
| DPAPI | Windows current-user Data Protection API |
| Keystore | Android hardware/OS protected key storage |
