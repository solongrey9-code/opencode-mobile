# Desktop Model Parity

> Compatibility alias. Canonical model/UI reference is `UI_MODEL_PARITY.md`; if they differ, use `UI_MODEL_PARITY.md`.

## Goal

The Android model selector should show the same practical choices as Desktop for the same server/directory while keeping Android-local UI state separate from session state.

## Eligibility

For the legacy `/provider` response:

1. Read provider definitions from `all`.
2. Keep only providers whose ID appears in `connected`.
3. Keep their models.
4. Remove models with `status = deprecated`.

For current `/api/provider` and `/api/model`, the server already returns available providers and enabled models.

## Display Name

- Provider group label is provider display name.
- Model row label is normalized model display name.
- Remove literal `(latest)` from the displayed name.
- Preserve a boolean Latest badge flag from that substring.

## Desktop Visibility Overrides

Desktop persists user overrides keyed by `(providerID, modelID)`:

```text
hide -> always hidden
show -> always shown
none -> automatic visibility
```

Android receives only the projected `user` array through the bridge.

## Automatic Visibility

For every model without a user override:

1. Parse `release_date` as ISO date.
2. Missing or invalid date means visible.
3. Keep dated candidates only if the date is strictly within six months before or after now.
4. Group by `(providerID, family)`.
5. Show only the newest release date in each group.

An empty family is a real group key. Do not replace it with model ID.

## Provider Order

Priority order:

1. `opencode`
2. `opencode-go`
3. `anthropic`
4. `github-copilot`
5. `openai`
6. `google`
7. `openrouter`
8. `vercel`

Other providers sort by provider display name.

Models within a provider sort by normalized model display name.

## Recent Models

- newest first
- unique by provider/model
- maximum five
- does not alter list ordering
- used as current-model fallback

Android reads Desktop recent models through the bridge but does not write Desktop state.

## Current Model Resolution

Resolution precedence:

1. explicit session/prompt selection
2. current Agent model override
3. configured default model
4. first valid recent model
5. first catalog fallback

The current model can remain selected even if it is hidden from the normal list.

## Agent Interaction

- Exclude subagents from the primary Agent selector.
- Selecting an Agent with a valid configured model selects that model.
- Session-stored Agent/model values seed the initial selection.

## Why Android Cannot Be Bit-Identical Without The Bridge

Desktop show/hide overrides, recent list and unsent prompt choice are local Electron state, not OpenCode server state. Without the bridge Android can match connected providers and defaults, but not Desktop-local visibility preferences.

## Source References

- `packages/app/src/context/models.tsx`
- `packages/app/src/hooks/use-providers.ts`
- `packages/app/src/components/dialog-select-model.tsx`
- `packages/app/src/pages/session/composer/prompt-model-selection.ts`
- `packages/app/src/context/local.tsx`
- `packages/core/src/catalog.ts`
