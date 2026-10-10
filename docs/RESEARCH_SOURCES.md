# 研究来源

上游固定版本：OpenCode `1.18.25`，commit `9f69463f1d556af2b5b51d2efa1c04f5f544f911`。

## Public server/API

- [Server docs](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/web/src/content/docs/server.mdx)
- [OpenAPI public composition](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/opencode/src/server/routes/instance/httpapi/public.ts)
- [Complete API composition](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/opencode/src/server/routes/instance/httpapi/api.ts)
- [Global routes](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/opencode/src/server/routes/instance/httpapi/groups/global.ts)
- [Global handlers](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/opencode/src/server/routes/instance/httpapi/handlers/global.ts)
- [Legacy event handler](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/opencode/src/server/routes/instance/httpapi/handlers/event.ts)
- [Legacy session routes](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/opencode/src/server/routes/instance/httpapi/groups/session.ts)
- [Legacy session handlers](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/opencode/src/server/routes/instance/httpapi/handlers/session.ts)

## Current protocol/schema

- [Current session protocol](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/protocol/src/groups/session.ts)
- [Current event protocol](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/protocol/src/groups/event.ts)
- [Current permission protocol](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/protocol/src/groups/permission.ts)
- [Current question protocol](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/protocol/src/groups/question.ts)
- [Session event schema](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/schema/src/session-event.ts)
- [Event manifest](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/schema/src/event-manifest.ts)
- [Legacy session/message schema](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/schema/src/v1/session.ts)

## Official client/Desktop PRIVATE implementation

- [Protocol detection](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/app/src/utils/server-protocol.ts)
- [Model visibility context](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/app/src/context/models.tsx)
- [Prompt model selection](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/app/src/pages/session/composer/prompt-model-selection.ts)
- [Desktop main](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/desktop/src/main/index.ts)
- [Desktop server launcher](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/desktop/src/main/server.ts)
- [Desktop sidecar worker](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/desktop/src/main/sidecar.ts)
- [Electron store](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/desktop/src/main/store.ts)
- [Tab schema](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/app/src/context/tabs.tsx)
- [Persistence filenames](https://github.com/anomalyco/opencode/blob/9f69463f1d556af2b5b51d2efa1c04f5f544f911/packages/app/src/utils/persist.ts)

## Source usage rules

- Public OpenAPI/schema 可以作为兼容合同，但仍 feature-detect deployed server。
- Desktop implementation 是 PRIVATE；版本升级必须重新审计。
- 文档 URL 固定 commit，避免 default branch 漂移。
- 代码中的实际 wire-contract test 比记忆或历史输出更可信。
