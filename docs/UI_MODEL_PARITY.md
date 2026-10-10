# UI 与模型 Parity

## 1. Visual language

目标不是通用 Material dashboard，而是适配手机的 OC-2 监督界面。

Dark palette：

```text
deep #080808
base #161616
layer1 #242424
layer2 #2E2E2E
layer3 #3A3A3A
text #FAFAFA
muted #AEAEAE
blue #3B5CF6 / soft #A2BCFF
peach #FAB283
green #6BD586
yellow #F2CF76
red #F17471
```

Light palette 使用白/浅灰 surface 和 OC blue，不启用 dynamic color，以保持品牌一致。

## 2. Information hierarchy

Session list 顺序：

1. Running now
2. Open on Desktop shared
3. Open on private Desktop
4. Other sessions grouped by day

同一 session 只出现一次，优先级高的组消费后不再进入后续组。

## 3. Status semantics

- Busy：blue executing badge/spinner。
- Retry：yellow retry badge，显示 attempt/message。
- Idle：不显示执行 badge。
- Child active：根会话显示 Busy，详情仍是 root timeline。

## 4. Desktop labels

Shared：

- group：`Open on Desktop` / `桌面已打开`
- badge：`Desktop open` / `桌面打开`
- recent：`Desktop recent` / `桌面最近`

Private sidecar：

- group：`Open on private Desktop` / `桌面私有标签`
- badge：`Desktop private` / `桌面私有`
- recent：`Private recent` / `私有最近`

若 bridge 判断最近 tab 是 sidecar，显示明确 warning：历史可共用，但当前运行态不能同步。

## 5. Composer

- Text input 支持 multiline。
- Agent 和 Model selector 在 composer 内。
- Running 时主要 action 变 Stop。
- Idle 时主要 action 变 Send。
- 空 prompt 不发送。
- 新 session 入口在 top app bar，不使用遮挡列表的 FAB。

## 6. IME contract

Scaffold 使用 `WindowInsets.safeDrawing`，Activity edge-to-edge。Composer 不叠加 IME 和 navigation padding。

不要重新同时加入：

```text
imePadding()
navigationBarsPadding()
```

否则 resize/inset 会重复，键盘上方出现大块空白。

## 7. Model source

Server provider catalog 是模型能力来源，Desktop bridge 只 enrich visibility/MRU。

Selectable models：

- provider ID 必须在 `/provider.connected`
- model status 不为 deprecated
- model key = `providerID/modelID`

## 8. Desktop visibility algorithm

每个模型：

1. override `hide` → 隐藏。
2. override `show` → 显示。
3. release date 缺失/无效 → 显示。
4. release date 位于约六个月窗口 → 参与 provider/family 分组。
5. 每组仅保留 release date 最大者。
6. 其余隐藏。

`(latest)` suffix：

- 从 display name 去除。
- 转换成 latest badge。
- 不直接决定可见性。

## 9. Provider/model order

Popular provider：

```text
opencode
opencode-go
anthropic
github-copilot
openai
google
openrouter
vercel
```

其他 provider 按 display name。Group 内 model 按 display name。

Desktop recent models 去重、新到旧、最多 5。

## 10. Selection precedence

```text
explicit session/prompt model
agent-specific model
configured default model
valid Desktop recent model
first connected provider default/model
```

Model popup 不添加伪 `Default` option。当前 model 通过 checkmark 和 selected style 表达。

## 11. Message rendering

- User prompt：紧凑 card。
- Assistant text：Markdown。
- Reasoning：可折叠/弱化。
- Tool：名称、status、input/output、error。
- Error：显式 red state，不吞掉 server message。
- Unknown part：安全忽略，不 crash。

## 12. Native vs Web

Native 优先覆盖低延迟监督路径：

- sessions
- run state
- prompt/stop
- permission/question
- Todo/Diff

桌面导向功能通过 system Custom Tab：

- terminal
- full file tree
- plugin/MCP management
- advanced settings

不使用 WebView，避免复制官方 UI、cookie/auth 复杂度和扩大攻击面。
