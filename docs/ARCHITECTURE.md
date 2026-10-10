# 架构

## 当前运行边界（2026-10-06）

Android/Desktop 共享、自启动和桥接的主机清理已完成，标记为 `complete`，旧复活入口拒绝启用。精确 Mobile Worker/managed Server 与共享默认绑定已清理，Desktop 使用自身的 local/sidecar。原两窗 UUID 和共 23 个 sidecar 标签已恢复，现有窗口及今天新增标签保留；任务空闲后正常关闭执行，未强制终止进程。恢复前后草稿快照哈希一致，不能代替全部 139 个非空输入的逐条 UI 验收；设备 reverse、防火墙和 Android SDK 构建缺口仍保留。

全量退役只定点移除共享默认连接及指定 server entry，保留当前标签、窗口 IDs 和数据库；已关闭的指定共享标签历史会映射回 sidecar，避免 Desktop 删除 server entry 时丢弃历史。输入保全必须单独覆盖 `drafts.sqlite` 的 WAL 和附件，不能把 tab/draft metadata 备份等同于文本保存。Apply 还须确认任务空闲并正常关闭 Desktop，见 `RETIREMENT.md`。

以下章节保留 Android 的实现架构及退役前共享设计。共享拓扑描述技术上的同步条件，不表示当前允许启动。两个 Server 进程不能因为访问同一数据库而共享执行内存态。

## 1. 系统边界

OpenCode Server 是以下状态的唯一权威来源：

- sessions
- messages / message parts
- execution status
- permission requests
- questions
- Todo
- Diff

Android 不建立第二个 session/message 数据库，也不从 Desktop UI 抓取消息。

```text
OpenCode database
       |
       v
shared OpenCode Server :4096
       | REST + SSE
       +----------------------+----------------------+
       |                      |                      |
       v                      v                      v
OpenCode Desktop        OpenCode Mobile         TUI / Web

Desktop electron-store
       |
       v
read-only bridge :4097 -- Bearer --> OpenCode Mobile
```

Bridge 只做 UI 元数据 enrich，不改变会话状态，不执行 Agent，不代理 OpenCode API。

## 2. Android 分层

### 2.1 Data model

`OpenCodeModels.kt` 定义：

- `ConnectionProfile`
- session/status/message/part/Todo/Diff
- permission/question
- agent/model catalog
- Desktop bridge state
- SSE event

`MessagePart.raw` 保留原始 `JsonObject`，使未知字段和未知 part type 前向兼容。

### 2.2 JSON compatibility

`OpenCodeJson.kt` 使用宽松 `Json`，手工解析而不是反射 DTO：

- legacy global event：`{directory,payload:{type,properties}}`
- legacy located event：`{type,properties}`
- current event：`{type,data,location}`
- permission aliases：`permission/action`、`patterns/resources`、`always/save`
- model aliases：`id/modelID`
- unknown field：忽略但保留 raw part

协议 shape 改动必须增加 parser test。

### 2.3 Transport

`OpenCodeApi.kt` 拥有两个 OkHttp client：

- 普通 REST client：有限 read/write/connect timeout
- SSE client：无限 read timeout

认证边界：

- OpenCode Origin 使用 Basic Auth。
- Desktop bridge 使用显式 bridge URL 和独立 Bearer token。
- 不根据 OpenCode 端口推导 bridge Origin。
- 不向 bridge 发送 Basic password。

### 2.4 State coordinator

`AppViewModel.kt` 是状态同步核心：

- `MutableStateFlow<AppUiState>` 是 UI 状态。
- `connectionGeneration` 取消过期候选连接。
- `connectionEpoch` 拒绝旧服务器请求结果。
- `stateSync` 串行化 snapshot/event/detail 写入。
- connection-scoped Job 在服务器切换时取消并 join。
- bridge 和 active session 每 2 秒轮询。

### 2.5 UI

`OpenCodeApp.kt` 是单 Activity Compose UI：

- Connection
- Sessions
- Session timeline
- Changes
- Full Web entry via Custom Tab

UI 不直接访问网络或存储；所有 mutation 委托给 ViewModel。

## 3. 连接事务

连接切换是事务式的：

1. 规范化候选 URL、directory 和 username。
2. 保留当前 API/event loop，不立即销毁。
3. 候选 API 先打开 SSE。
4. 等待 `server.connected`。
5. 在 SSE 缓冲期间加载 REST snapshot。
6. 候选成功后取消旧 event loop 和旧 action/detail jobs。
7. 增加 `connectionEpoch`。
8. 原子应用 snapshot。
9. 重放 snapshot 期间缓存的事件。
10. 激活新 event loop。

候选失败时，只有旧 API 仍是当前且旧 event job 仍存活，才恢复旧连接。

## 4. SSE / Snapshot 屏障

这是最高风险架构点。

OpenCode 的 global SSE 没有 replay cursor。以下两种顺序都不安全：

- 先 snapshot 再订阅：snapshot 后、订阅前的事件丢失。
- 订阅后直接更新 UI，同时 snapshot：旧 snapshot 可能覆盖新事件。

正确算法：

```text
subscribe SSE
    |
buffer events
    |
load REST snapshot
    |
lock state
    |
apply snapshot
    |
replay buffered events
    |
switch to live reduction
```

Reconnect 使用同一算法。手动 refresh 在 `stateSync` 下执行，避免覆盖正在归并的 event。

## 5. Event reduction

### 5.1 Legacy

直接 reducer：

- `session.created/updated/deleted`
- `session.status/idle/diff/error`
- `message.updated/removed`
- `message.part.updated/removed/delta`
- `todo.updated`
- permission/question asked/replied/rejected

### 5.2 Current

支持 `session.next.*`：

- step started/ended/failed
- text started/delta/ended
- reasoning started/delta/ended
- tool input/called/progress/success/failed
- retried

Text/reasoning delta 在本地直接拼接。其他 current event 触发 conflated detail refresh。

### 5.3 Active child projection

Server 可能报告正在运行的 child/subagent session，而列表只展示 root session。

算法：

1. 获取 active/status IDs。
2. 若 ID 不在 root list，调用 session detail。
3. 沿 `parentID` 最多向上 16 层。
4. 将 Busy 投影到第一个可见 root。

Snapshot 和 2 秒 polling 都执行该投影。SSE child event 到 root UI 最多有约 2 秒延迟。

## 6. Endpoint-level compatibility

不使用一个全局 protocol boolean 决定所有 API。

当前 fallback：

- sessions：`/experimental/session`，仅 404/405 回退 `/session`
- global events：`/global/event`，仅 404/405 回退 `/event`
- provider：`/provider` 仅在 404/405 时尝试 `/config/providers`
- permission/question 缺失 endpoint：404/405 视为空列表
- 401/403：永久认证错误，停止重连并返回 Connection

为什么：OpenCode 1.18.25 同一服务可同时存在 legacy、current 和 transitional endpoint/event shape。

## 7. Cross-project snapshot

All-project 模式：

1. 全局获取 root sessions。
2. 提取不同 directory。
3. 以 `Semaphore(6)` 限制并发。
4. 每个 directory 拉取 status、permissions、questions。
5. 某个目录暂时失败时保留该目录旧状态，不用空列表覆盖。

401/403 仍向上抛出，不能被当作局部失败。

## 8. Desktop metadata bridge（已退役的 Host 入口）

Bridge 读取：

- `opencode.settings`
- `opencode.window.<id>.dat`
- `opencode.global.dat` 中的 `model` 值

Bridge 返回：

- `privateOpenSessionIds`
- `sharedOpenSessionIds`
- `privateRecentSessionId`
- `sharedRecentSessionId`
- Desktop default server
- model show/hide/MRU
- LAN URLs

Bridge 不返回：

- messages
- provider credentials
- OpenCode Basic password
- private sidecar password
- Desktop whole store

Android 展示语义：

- shared tab：`桌面打开/桌面最近`
- sidecar tab：`桌面私有/私有最近`
- private tab 绝不用于证明 live state shared

## 9. Persistence

Android 只持久化 connection profile：

- base URL
- explicit bridge URL
- username
- directory
- encrypted Basic password
- encrypted bridge token

加密：Android Keystore AES-GCM，随机 IV。

解密失败行为：返回空 secret，不返回 ciphertext，不 crash。

Android backup 和 device transfer 全部禁用。

## 10. Model parity

Model catalog 只保留 connected providers。可见性：

1. Desktop override `hide`：隐藏。
2. Desktop override `show`：显示。
3. release date 缺失/无效：显示。
4. 有效日期在约六个月窗口：按 provider/family 选最新。
5. 其他：隐藏。

Provider group order：

```text
opencode
opencode-go
anthropic
github-copilot
openai
google
openrouter
vercel
others alphabetically
```

当前模型解析：session/prompt → agent model → configured default → Desktop MRU → provider fallback。

## 11. IME layout

Activity 使用 edge-to-edge，Scaffold 处理 safe drawing 和 IME resize。Composer 本身不再叠加 `imePadding()` 和 `navigationBarsPadding()`，避免双重 inset。

真机验证时 Composer card 到 IME 顶部只剩 28px 设计边距。

## 12. 关键不变量

- Server 是 session truth；Bridge 不是。
- Desktop 和 Android 必须连接同一 server process 才有 live-state parity。
- SSE 先订阅、后 snapshot、再重放 buffer。
- 连接代次和 epoch 不能删除。
- Unknown JSON/event 必须容忍。
- Private/shared tab identity 不能合并。
- Basic 和 Bridge credential 必须 origin/type 分离。
- Debug provisioning 不能通过 exported Launcher。
- 任何协议 shape 改动都要增加测试。
