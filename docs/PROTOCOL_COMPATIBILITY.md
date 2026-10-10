# OpenCode 1.18.25 协议兼容

本文档固定到 OpenCode `1.18.25` 和上游提交 `9f69463f1d556af2b5b51d2efa1c04f5f544f911`。

标签：

- `PUBLIC-LEGACY`：root/unprefixed OpenAPI。
- `PUBLIC-CURRENT`：`/api/*` typed/current API。
- `PUBLIC-EXPERIMENTAL`：`/experimental/*`，公开但不稳定。
- `PRIVATE`：Desktop sidecar、IPC、electron-store schema。

## 1. Endpoint matrix

| 能力 | Legacy/experimental | Current | Android 策略 |
|---|---|---|---|
| Health | `GET /global/health` | `GET /api/health` | 使用 legacy health/version |
| Global SSE | `GET /global/event` | `GET /api/event` | 先 global legacy；404/405 回退 `/event` |
| Located SSE | `GET /event?directory=` | `GET /api/session/:id/event?after=` | 当前实现兼容 global/located envelope |
| Global sessions | `GET /experimental/session?roots=true` | `GET /api/session` | experimental；404/405 回退 `/session` |
| Project sessions | `GET /session?directory=&roots=true` | `GET /api/session?directory=` | legacy session UI 依赖 |
| Running | `GET /session/status?directory=` | `GET /api/session/active` | 合并两个结果，child 投影到 root |
| Session detail | `GET /session/:id` | `GET /api/session/:id` | legacy top-level directory shape |
| Create | `POST /session?directory=` | `POST /api/session` | 创建必须显式 directory |
| Rename/Delete | `PATCH/DELETE /session/:id` | transitional | 使用 legacy |
| Prompt | `POST /session/:id/prompt_async` | `POST /api/session/:id/prompt` | Android 使用 legacy async 204 |
| Abort | `POST /session/:id/abort` | `POST /api/session/:id/interrupt` | Android 使用 legacy |
| Messages | `GET /session/:id/message` | `GET /api/session/:id/message` | 解析 legacy `{info,parts}` |
| Todo | `GET /session/:id/todo` | 无稳定 snapshot | 使用 legacy + event |
| Diff | `GET /session/:id/diff` | 无稳定 snapshot | 使用 legacy |
| Permissions | `GET /permission` | `GET /api/permission/request` | legacy list/reply，兼容 v2 event shape |
| Questions | `GET /question` | `GET /api/question/request` | legacy list/reply/reject |
| Providers | `GET /provider` | `GET /api/provider` | legacy `connected` 决定 selectable provider |
| Models | provider `models` | `GET /api/model` | Android 当前使用 legacy catalog |
| Agents | `GET /agent` | `GET /api/agent` | 使用 legacy |
| Share | `/session/:id/share` | 无 tagged current contract | 使用 legacy |
| Revert | `/revert`, `/unrevert` | stage/clear/commit | Android 使用 legacy |

## 2. SSE envelope

### Legacy global

```json
{
  "directory": "C:/workspace",
  "project": "project-id",
  "workspace": "workspace-id",
  "payload": {
    "id": "evt_...",
    "type": "session.updated",
    "properties": {}
  }
}
```

`server.connected` 等 control frame 可省略 `directory`。

### Legacy located

```json
{
  "id": "evt_...",
  "type": "message.part.delta",
  "properties": {
    "sessionID": "ses_...",
    "messageID": "msg_...",
    "partID": "prt_...",
    "field": "text",
    "delta": "..."
  }
}
```

### Current

```json
{
  "id": "evt_...",
  "type": "session.next.text.delta",
  "data": {
    "sessionID": "ses_...",
    "assistantMessageID": "msg_...",
    "textID": "text-id",
    "delta": "..."
  },
  "location": {
    "directory": "C:/workspace"
  },
  "durable": {
    "aggregateID": "ses_...",
    "seq": 12,
    "version": 1
  }
}
```

## 3. SSE 规则

- `/global/event` 首帧是 `payload.type=server.connected`。
- `/event` 首帧是 top-level `server.connected`。
- heartbeat 约每 10 秒一次。
- JSON event ID 不等于 SSE `id:`；global/legacy stream 没有 replay cursor。
- reconnect 后必须 REST snapshot。
- `/api/session/:id/event?after=seq` 是可 replay 的 durable 例外，但 Android 当前未用它替代全局流。
- global `payload.type=sync` 可忽略，除非未来实现 durable replication。

## 4. Event families

### Legacy / transitional

```text
server.connected
server.heartbeat
server.instance.disposed
session.created
session.updated
session.deleted
session.status
session.idle
session.diff
session.error
message.updated
message.removed
message.part.updated
message.part.removed
message.part.delta
todo.updated
permission.asked
permission.replied
question.asked
question.replied
question.rejected
```

### Current tagged

```text
session.next.prompted
session.next.prompt.admitted
session.next.agent.switched
session.next.model.switched
session.next.moved
session.next.step.started|ended|failed
session.next.text.started|delta|ended
session.next.reasoning.started|delta|ended
session.next.tool.input.started|delta|ended
session.next.tool.called|progress|success|failed
session.next.retried
session.next.compaction.started|delta|ended|failed
session.next.revert.staged|cleared|committed
permission.v2.asked|replied
question.v2.asked|replied|rejected
todo.updated
```

Desktop vendored client还可能使用 transitional 名称，例如 `session.text.*`、`session.execution.*`、`session.retry.scheduled`。这些不能替代 tagged public schema，但 reducer 对已知 shape 保留兼容。

## 5. Core JSON shape

### Session

关键字段：

```text
id, slug, projectID, workspaceID?, directory, parentID?, title,
summary?, share?, agent?, model?, time.created, time.updated, revert?
```

`GlobalSession` 还可能带 project summary。

### Message

- User：`role=user`, agent, model, time, summary。
- Assistant：`role=assistant`, parentID, providerID, modelID, path, cost, tokens, error, finish。

### Part

已渲染：

- text
- reasoning
- file
- tool
- step-start / step-finish
- patch

未知 part 保留 `raw` 并安全忽略。

### Permission aliases

```text
legacy: permission, patterns, always
current: action, resources, save
```

Reply：`once | always | reject`。

### Question

```text
questions[]: question, header, options[], multiple?, custom?
reply: { answers: string[][] }
```

## 6. Directory routing

实例 endpoint 支持：

```text
?directory=<absolute-path>
?workspace=<workspace-id>
```

Session-bound path 优先使用 server 存储的 session location。Permission/Question request 从 global SSE 保留 envelope directory，以便 reply 落到正确实例。

`GET /session` 只有 query 中显式带 directory 才启用精确目录过滤。全项目模式优先 `/experimental/session`。

## 7. Model catalog compatibility

Legacy `/provider`：

```json
{
  "all": [],
  "default": {},
  "connected": []
}
```

Android 只保留 `connected` Provider 的 models，并去掉 deprecated model。Desktop show/hide/MRU 是 PRIVATE 本地偏好，由 bridge enrich。

## 8. Error policy

- 401/403：永久认证失败，停止 event reconnect。
- 404/405：仅在明确 endpoint fallback 位置触发兼容回退。
- 某一目录 status/permission/question 的普通网络错误：保留该目录上次状态。
- Unknown event/part/field：忽略或保留 raw，不 crash。
- 204：成功且无 body，不能强制 JSON parse。

## 9. Public vs private

公共 API 可作为客户端合同，仍需按 server `/doc` feature-detect。

以下是 PRIVATE：

- Desktop sidecar port/password lifecycle
- Electron IPC
- `opencode.*.dat` schema
- Desktop model visibility store
- Desktop open tabs

PRIVATE detail 必须版本门控、read-only、失败可降级，不能成为 session truth。
