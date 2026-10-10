# OpenCode Mobile Agent Guide

This file is the mandatory entry point for a new coding-agent conversation.

## Current Retirement Work

The Android/Desktop shared runtime is being retired. Read `docs/RETIREMENT.md`
before any host operation; old enabling entry points are intentionally disabled.
The current host phase is `startup-disabled`, not fully retired. Recheck live
state rather than assuming the worker/server have stopped.

Unsent editor contents and attachments must not be lost. Preserve and verify
Desktop's actual draft database (including WAL/blob data) before a normal close;
tab IDs or migration backups alone do not prove input safety. Never close or
restart Desktop as a shortcut, and preserve current draft/server/window identity.

## Read First

Read these files in order before changing code:

1. `docs/START_HERE.md`
2. `docs/BASELINE.md`
3. `docs/ARCHITECTURE.md`
4. The task-specific documents linked from `docs/INDEX.md`

Run this before assuming anything about the live environment:

```powershell
.\scripts\status.cmd
```

## Project Contract

- OpenCode Server is the only source of truth for sessions, messages, execution, permissions, questions, Todo and Diff.
- Desktop and Android must connect to the same server process for live-state synchronization.
- The Desktop bridge is read-only metadata enrichment. It is never a second session source.
- Preserve both legacy and current OpenCode 1.18.25 API/event compatibility.
- Never store a second local session database.
- Never expose the OpenCode Basic password to the bridge origin.
- Never merge private sidecar tabs with shared-server tabs.
- Never accept debug bridge provisioning through the exported launcher activity.
- Never commit Desktop state, OpenCode databases, migration backups, DPAPI files, bridge tokens, provider auth, APK signing keys or command transcripts containing credentials.
- Do not change Desktop server bindings while an Agent is running. Process-local execution cannot migrate.
- Keep user work and unrelated worktree changes intact.

## Current Quality Gate

Run both commands before declaring a change complete:

```powershell
.\scripts\build.cmd
.\scripts\build.cmd -Release
```

The expected baseline is:

- 15 unit tests passing
- Android Lint with no issues
- Debug APK assembled and v2-signed with the debug certificate
- Release APK assembled and R8-minified, but unsigned
- PowerShell and Node scripts passing syntax checks

The gate does not currently automate ViewModel race scheduling, Android Keystore instrumentation, Compose UI, Desktop migration or host-process lifecycle tests. If a change touches those areas, perform the targeted manual/integration checks in `docs/TESTING.md` and preserve the gap in documentation until automated coverage exists.

## High-Risk Areas

- `AppViewModel.kt`: connection generation, epoch isolation, SSE/snapshot barrier and job cancellation
- `OpenCodeApi.kt`: origin-bound Basic Auth, explicit bridge Bearer origin and endpoint fallback
- `desktop-bridge.mjs`: token authentication and private/shared tab identity
- `configure-desktop-shared.ps1`: Desktop backups, tab migration and new window IDs
- `ConnectionStore.kt`: Keystore encryption and decryption-failure behavior
- `OpenCodeApp.kt`: permission/question semantics, model parity and IME layout

## Editing Guidance

- Prefer the smallest correct change.
- Add endpoint-level compatibility rather than a single global protocol switch.
- Keep unknown JSON/event fields forward-compatible.
- Add a parser or wire-contract test for every protocol-shape change.
- Treat `docs/STATE_SNAPSHOT_2026-09-01.md` as historical evidence, not guaranteed current state.
- Update documentation when architecture, security, scripts, ports, credentials, migration behavior or verification commands change.
