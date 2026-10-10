# Operations Runbook

> Compatibility alias. Canonical operational guidance is `OPERATIONS.md`; if they differ, use `OPERATIONS.md`.

## Inspect First

```powershell
.\scripts\status.cmd
git status --short
```

Check listener ownership before starting another service:

```powershell
$listeners = Get-NetTCPConnection -State Listen -LocalPort 4096,4097
$listeners
Get-CimInstance Win32_Process | Where-Object {
  $_.ProcessId -in $listeners.OwningProcess
} | Format-List ProcessId,ParentProcessId,Name,ExecutablePath,CommandLine
```

Do not terminate a process until its executable and command line are verified.

## Build

Debug quality gate:

```powershell
.\scripts\build.cmd
```

Release quality gate:

```powershell
.\scripts\build.cmd -Release
```

Outputs:

```text
app\build\outputs\apk\debug\app-debug.apk
app\build\outputs\apk\release\app-release-unsigned.apk
```

## Start Server And Bridge

LAN-capable default:

```powershell
.\scripts\start-opencode-mobile.cmd
```

Loopback-only for ADB reverse:

```powershell
.\scripts\start-opencode-mobile.cmd -UsbOnly
```

Headless API server instead of Web host:

```powershell
.\scripts\start-opencode-mobile.cmd -ServeOnly
```

Options can be combined.

Startup does the following:

- securely prompts for the Basic password
- creates/reuses an independent DPAPI bridge token
- reverses 4096 and 4097 on connected ADB devices
- provisions the Debug receiver when present
- attempts a LocalSubnet firewall rule in LAN mode
- starts the hidden bridge
- runs `opencode web` or `opencode serve`
- health-checks both children and fails nonzero on startup/runtime failure
- gives the bridge only its Bearer token and OpenCode only its Basic credentials

Keep the launcher window open. Normal `Ctrl+C` cleanup stops the bridge and restores environment variables.

## Install Debug APK

```powershell
.\scripts\install-debug.cmd
```

The script:

- chooses one authorized device, preferring USB
- installs with non-streaming mode for OnePlus compatibility
- creates both reverse entries
- decrypts the host bridge token temporarily
- sends a `DUMP`-protected provisioning broadcast
- zeroes the BSTR and restarts the app

## Enable Wireless ADB

Connect exactly one authorized USB device and run:

```powershell
.\scripts\enable-wireless-adb.cmd
```

The script:

- reads `wlan0` IPv4
- runs `adb tcpip 5555`
- reconnects over the discovered phone Wi-Fi address on port 5555
- reverses 4096 and 4097

Verify:

```powershell
.\scripts\adb.cmd devices -l
$serial = & ".\scripts\adb.cmd" devices | Select-String '^\d+\.\d+\.\d+\.\d+:5555\s+device' | ForEach-Object { ($_ -split '\s+')[0] } | Select-Object -First 1
if ($serial) { .\scripts\adb.cmd -s $serial reverse --list }
```

Expected reverse list includes both ports.

Wireless ADB is volatile. Rerun after reboot, IP change, Wi-Fi change, adbd reset or transport failure.

To return a reachable wireless device to USB mode:

```powershell
if ($serial) { .\scripts\adb.cmd -s $serial usb }
```

## Migrate Desktop To Shared Server

Preconditions:

- finish/abort active Agent
- return Desktop Home
- close Desktop completely
- shared server already running

Run:

```powershell
.\scripts\configure-desktop-shared.cmd -ConfirmHome
```

Enter the same Basic password used by the shared server.

Verify after manually restarting Desktop:

```powershell
.\scripts\status.cmd
```

Expected:

- `Default server:` is the shared URL
- Desktop tab servers contain the same shared URL
- bridge reports shared tabs and no unintended sidecar tabs
- Android private warning disappears after bridge refresh

## Roll Back Desktop Migration

1. finish/abort active Agent
2. close Desktop
3. locate `%APPDATA%\ai.opencode.desktop\mobile-shared-backup-<timestamp>`
4. copy its files back into `%APPDATA%\ai.opencode.desktop`
5. restart Desktop
6. verify old `windowIds` and sidecar identity

There is no automated rollback script. The backup contains a Desktop server password and must stay private.

## Stop Managed Runtime

Preferred:

```text
Ctrl+C in the original start-opencode-mobile window
```

Then verify:

```powershell
.\scripts\status.cmd
```

If the launcher died and children are orphaned:

1. inspect listeners
2. inspect exact executable/command line
3. finish/abort any running Agent
4. stop only the verified server/bridge PIDs

Remove reverse entries if needed:

```powershell
.\scripts\adb.cmd reverse --remove tcp:4096
.\scripts\adb.cmd reverse --remove tcp:4097
```

## Direct LAN

Requirements:

- server and bridge listening on `0.0.0.0`
- LocalSubnet firewall permission
- Android server URL set to computer LAN IP
- bridge URL separately reachable or reverse-forwarded

Server and bridge are separate origins. A bridge-supplied LAN URL is only a suggestion; selecting it clears the password and requires explicit reconnection.

## Cold OpenCode Data Backup

The repository has no automated DB backup.

For a consistent manual backup:

1. finish/abort all Agents
2. stop Desktop sidecar and shared OpenCode server writers
3. copy the entire `%USERPROFILE%\.local\share\opencode` directory
4. preserve DB, WAL and SHM together
5. protect the backup as sensitive conversation/source data

Do not run a file-copy backup of only `opencode.db` while writers are active.
