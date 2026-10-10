# Codebase Map

> Compatibility alias. Canonical source map is `CODE_MAP.md`; if they differ, use `CODE_MAP.md`.

## Root Build Files

| Path | Responsibility |
|---|---|
| `settings.gradle.kts` | Plugin/dependency repositories and `:app` module |
| `build.gradle.kts` | AGP and Kotlin/Compose plugin versions |
| `gradle.properties` | JVM memory, AndroidX and Kotlin style |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 8.11.1 URL, checksum and timeout |
| `.gitignore` | Build output plus credential/Desktop/OpenCode-data exclusions |
| `AGENTS.md` | Mandatory future-agent startup contract |

## Android Entry And Configuration

| Path | Responsibility | Preserve |
|---|---|---|
| `app/src/main/AndroidManifest.xml` | App/activity, network and backup policy | No release provisioning receiver; backups remain disabled |
| `app/src/debug/AndroidManifest.xml` | Debug bridge provisioning receiver | Keep `android.permission.DUMP` and debug source set |
| `OpenCodeApplication.kt` | Manifest Application class | No hidden mutable app state |
| `MainActivity.kt` | Single Compose activity | Do not accept bridge extras on exported launcher |
| `BridgeProvisionReceiver.kt` | ADB-only Debug provisioning | Validate URL, nonblank token, save through `ConnectionStore` |

## Data Layer

### `OpenCodeModels.kt`

Defines all transport-neutral state:

- `ConnectionProfile`
- `OpenCodeSession`, `SessionStatus`, `SessionModel`
- `MessageInfo`, `MessagePart`, `SessionMessage`
- `OpenCodeTodo`, `FileDiff`
- `PermissionRequest`, `QuestionRequest`
- `AgentChoice`, `ModelChoice`, `ModelCatalog`
- `DesktopBridgeState`
- `ServerEvent`, `SessionDetail`

Do not introduce database entities unless the architecture changes explicitly.

### `OpenCodeJson.kt`

Manual/lenient parsers for:

- session/status/message/part shapes
- permission/question old and v2 shapes
- global/instance/current SSE envelopes
- model catalog and Desktop bridge projection
- unknown-field-compatible helpers

Add parser fixtures whenever a wire shape changes.

### `OpenCodeApi.kt`

Owns REST and SSE transport:

- origin-bound Basic Auth
- explicit bridge Bearer Auth
- directory routing
- endpoint fallback
- active/root/session lookups
- prompt, abort, permission and question mutations
- callback-to-coroutine cancellation

Do not reuse `requestBuilder()` for the bridge origin.

### `ConnectionStore.kt`

Stores the connection profile:

- AES-GCM encrypted password/token
- Android Keystore key alias
- private SharedPreferences
- decryption failure returns empty credential rather than crashing

## State Layer

### `AppViewModel.kt`

This is the most sensitive file.

Responsibilities:

- candidate connection bootstrap
- generation and epoch isolation
- SSE buffering and snapshot barrier
- reconnect/backoff and untrusted LAN suggestions
- per-directory snapshot fan-out
- active child-to-root projection
- legacy/current event reduction
- detail/message/part merging
- Desktop bridge polling
- model visibility/default resolution
- all user mutations

Review these invariants before editing:

- one committed API instance at a time
- no stale job can write after connection change
- snapshot and event application share `stateSync`
- permanent auth errors disconnect
- private/shared Desktop IDs remain separate
- action jobs validate `ActionContext`
- bridge failure cannot fail the main server connection

## UI Layer

### `OpenCodeApp.kt`

Contains:

- Connect form and security warnings
- session groups and Desktop badges
- session toolbar and actions
- timeline/message/part rendering
- permission/question docks
- Todo and Diff UI
- composer, Agent and model controls
- Custom Tab launcher
- dialogs and small Markdown renderer

Semantics to preserve:

- shared and private Desktop groups have different labels
- running group takes priority
- `once`, `always`, `reject` values are exact wire values
- question answers are ordered `List<List<String>>`
- composer does not add a second IME/navigation inset
- Custom Tab owns browser authentication

### `OpenCodeTheme.kt`

Contains OC-2-derived color tokens, explicit light/dark schemes and typography.

## Tests

| Path | Coverage |
|---|---|
| `OpenCodeJsonTest.kt` | Session/status, messages/tools, v2 permission, event envelopes, connected model and bridge parsing |
| `OpenCodeApiTest.kt` | Auth/query routing, async prompt body, explicit-directory session creation, global SSE, 401 and URL normalization |

Missing automated coverage:

- ViewModel concurrency/barrier races
- Android Keystore instrumentation
- Compose screenshot/interaction tests
- host bridge and migration behavior
- active child projection

These are explicit quality-gate gaps, not covered by the 15 JVM tests. Changes in these areas require the manual/integration matrix in `TESTING.md`.

## Scripts

| Script | Responsibility |
|---|---|
| `build.ps1/.cmd` | Discover portable tools; test, lint and assemble |
| `status.ps1/.cmd` | Read-only live-state summary without printing secrets |
| `start-opencode-mobile.ps1/.cmd` | Start server/bridge, create token, configure ADB/firewall |
| `install-debug.ps1/.cmd` | Non-streaming OnePlus-compatible install, dual reverse, Debug provisioning |
| `enable-wireless-adb.ps1/.cmd` | Enable/reuse Wi-Fi ADB and dual reverse |
| `desktop-bridge.mjs` | GET-only Desktop metadata projection |
| `configure-desktop-shared.ps1/.cmd` | Back up and migrate Desktop to shared server |
| `migrate-desktop-now.ps1` | Delayed one-time migration helper with DPAPI password file |

## Resource Files

- `values/strings.xml` and `values-zh-rCN/strings.xml`: English/Chinese parity
- `network_security_config.xml`: system CAs and intentional cleartext development support
- `data_extraction_rules.xml`: exclude every backup/transfer domain
- `themes.xml`: edge-to-edge host theme
- `ic_launcher.xml`: vector app icon

## Verification

```powershell
.\scripts\build.cmd
.\scripts\build.cmd -Release
.\scripts\status.cmd
```

Script syntax:

```powershell
$failed=$false
Get-ChildItem .\scripts -Filter *.ps1 | ForEach-Object {
  $tokens=$null; $errors=$null
  [System.Management.Automation.Language.Parser]::ParseFile($_.FullName,[ref]$tokens,[ref]$errors) | Out-Null
  if($errors.Count){$errors;$failed=$true}
}
node --check .\scripts\desktop-bridge.mjs
if($failed){exit 1}
```
