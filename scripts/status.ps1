$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

function Section([string]$Title) {
    Write-Output ""
    Write-Output "[$Title]"
}

function Safe-Json([string]$Path) {
    try {
        return [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8) | ConvertFrom-Json
    } catch {
        return $null
    }
}

function Find-Adb {
    $candidates = @(
        "$env:ANDROID_HOME\platform-tools\adb.exe",
        "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe",
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:TEMP\opencode\android-sdk\platform-tools\adb.exe",
        "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    return $null
}

Write-Output "OpenCode Mobile status"
Write-Output "Timestamp: $(Get-Date -Format o)"
Write-Output "Workspace: $root"

Section "Git"
try {
    $branch = git -C $root branch --show-current 2>$null
    $changes = @(git -C $root status --short 2>$null)
    Write-Output "Branch: $($branch | Select-Object -First 1)"
    Write-Output "Worktree entries: $($changes.Count)"
} catch {
    Write-Output "Git status unavailable"
}

Section "OpenCode processes"
$listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {
    $_.LocalPort -in @(4096, 4097)
})
foreach ($port in @(4096, 4097)) {
    $matches = @($listeners | Where-Object LocalPort -eq $port)
    if ($matches.Count -eq 0) {
        Write-Output "Port ${port}: not listening"
        continue
    }
    foreach ($match in $matches) {
        $process = Get-Process -Id $match.OwningProcess -ErrorAction SilentlyContinue
        $started = "unknown"
        try {
            if ($process -and $process.StartTime) { $started = $process.StartTime.ToString("o") }
        } catch {}
        Write-Output "Port ${port}: $($match.LocalAddress), pid=$($match.OwningProcess), process=$($process.ProcessName), started=$started"
    }
}

$desktopPath = Join-Path $env:LOCALAPPDATA "Programs\@opencode-aidesktop\OpenCode.exe"
$desktopProcesses = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
    $_.ExecutablePath -and $_.ExecutablePath.Equals($desktopPath, [StringComparison]::OrdinalIgnoreCase)
})
Write-Output "Desktop running: $([bool]$desktopProcesses), processes=$($desktopProcesses.Count)"
$desktopVersion = if (Test-Path -LiteralPath $desktopPath) {
    ([Version](Get-Item -LiteralPath $desktopPath).VersionInfo.ProductVersion).ToString(3)
} else { "missing" }
Write-Output "Desktop version: $desktopVersion"

$globalCliPath = @(
    "$env:APPDATA\npm\node_modules\opencode-ai\bin\opencode.exe",
    "$env:APPDATA\npm\opencode.cmd"
) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
$globalCliVersion = if ($globalCliPath) {
    try { [string](& $globalCliPath --version 2>$null) } catch { "unavailable" }
} else { "missing" }
$managedCliPath = Join-Path $env:LOCALAPPDATA "OpenCodeMobile\cli\node_modules\opencode-ai\bin\opencode.exe"
$managedCliVersion = if (Test-Path -LiteralPath $managedCliPath) {
    try { [string](& $managedCliPath --version 2>$null) } catch { "unavailable" }
} else { "missing" }
Write-Output "Global CLI version: $globalCliVersion"
Write-Output "Managed CLI version: $managedCliVersion"

$desktopPids = @($desktopProcesses | Select-Object -ExpandProperty ProcessId)
$desktopConnections = if ($desktopPids.Count -gt 0) {
    @(Get-NetTCPConnection -State Established -ErrorAction SilentlyContinue | Where-Object {
        $_.RemotePort -eq 4096 -and $_.OwningProcess -in $desktopPids
    })
} else { @() }
Write-Output "Desktop shared connections: $($desktopConnections.Count)"

$serverListener = $listeners | Where-Object LocalPort -eq 4096 | Select-Object -First 1
if ($desktopProcesses.Count -gt 0 -and $serverListener) {
    $desktopStarted = (Get-Process -Id $desktopProcesses[0].ProcessId -ErrorAction SilentlyContinue).StartTime
    $serverStarted = (Get-Process -Id $serverListener.OwningProcess -ErrorAction SilentlyContinue).StartTime
    if ($desktopStarted -and $serverStarted) {
        $order = if ($serverStarted -le $desktopStarted) {
            "server-before-desktop"
        } elseif ($desktopConnections.Count -gt 0) {
            "desktop-before-server, recovered"
        } else {
            "desktop-before-server, disconnected"
        }
        Write-Output "Startup order: $order"
    }
}

Section "Persistent runtime"
$retirementMarkerPath = Join-Path $env:LOCALAPPDATA "OpenCodeMobile\integration-retired.json"
$retirementMarker = Safe-Json $retirementMarkerPath
Write-Output "Shared integration entry points retired: $(Test-Path -LiteralPath (Join-Path $PSScriptRoot 'shared-integration.retired'))"
Write-Output "Host retirement phase: $(if ($retirementMarker) { $retirementMarker.Phase } else { '<not retired>' })"
$runPath = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
$runValue = (Get-ItemProperty -LiteralPath $runPath -Name "OpenCode Mobile Runtime" -ErrorAction SilentlyContinue)."OpenCode Mobile Runtime"
$runtimeProcesses = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
    $_.ExecutablePath -and
    $_.ExecutablePath -like "*\powershell.exe" -and
    $_.CommandLine -and
    $_.CommandLine -match '(?i)-File\s+"?[^\r\n"]*runtime-worker\.ps1'
})
Write-Output "Login startup: $([bool]$runValue)"
if ($runValue) {
    Write-Output "Login startup uses installed runtime: $($runValue -like "*$env:LOCALAPPDATA\OpenCodeMobile\runtime\runtime-worker.ps1*")"
}
Write-Output "Runtime worker: running=$([bool]$runtimeProcesses), processes=$($runtimeProcesses.Count)"
if ($runtimeProcesses.Count -gt 0) {
    Write-Output "Runtime worker pid: $($runtimeProcesses[0].ProcessId)"
}
$runtimeLog = Join-Path $env:LOCALAPPDATA "OpenCodeMobile\runtime.log"
if (Test-Path -LiteralPath $runtimeLog) {
    Write-Output "Runtime log: $runtimeLog"
    Get-Content -LiteralPath $runtimeLog -Tail 3 | ForEach-Object { Write-Output "  $_" }
}
if ($desktopProcesses.Count -gt 0 -and $serverListener) {
    $connectionHealthy = $desktopConnections.Count -gt 0
    Write-Output "Desktop shared transport: $(if ($connectionHealthy) { 'connected' } else { 'not connected' })"
}

Section "Desktop state"
$desktopData = Join-Path $env:APPDATA "ai.opencode.desktop"
$settings = Safe-Json (Join-Path $desktopData "opencode.settings")
$global = Safe-Json (Join-Path $desktopData "opencode.global.dat")
$sharedUrl = if ($settings.defaultServerUrl) { [string]$settings.defaultServerUrl } else { "" }
$tabs = @()
foreach ($windowID in @($settings.windowIds)) {
    $window = Safe-Json (Join-Path $desktopData "opencode.window.$windowID.dat")
    if (-not $window -or -not ($window.tabs -is [string])) { continue }
    try {
        $parsedTabs = @(ConvertFrom-Json -InputObject $window.tabs)
        foreach ($tab in $parsedTabs) { $tabs += $tab }
    } catch {}
}
$sessionTabs = @($tabs | Where-Object type -eq "session")
$sharedTabs = @($sessionTabs | Where-Object { $_.server -eq $sharedUrl -and $sharedUrl })
$privateTabs = @($sessionTabs | Where-Object server -eq "sidecar")
Write-Output "Default server: $(if ($sharedUrl) { $sharedUrl } else { '<none>' })"
Write-Output "Windows: $(@($settings.windowIds).Count)"
Write-Output "Session tabs: total=$($sessionTabs.Count), shared=$($sharedTabs.Count), private-sidecar=$($privateTabs.Count)"

$serverPassword = $null
if ($global -and ($global.server -is [string])) {
    try {
        $serverStore = ConvertFrom-Json -InputObject $global.server
        $entry = @($serverStore.list | Where-Object {
            $_.http -and $_.http.url -eq $sharedUrl
        }) | Select-Object -First 1
        if ($entry) { $serverPassword = [string]$entry.http.password }
    } catch {}
}

if ($sharedUrl -and $serverPassword) {
    try {
        $username = if ($entry.http.username) { [string]$entry.http.username } else { "opencode" }
        $basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${username}:$serverPassword"))
        $health = Invoke-RestMethod -Uri "$sharedUrl/global/health" -Headers @{ Authorization = "Basic $basic" } -TimeoutSec 5
        Write-Output "Shared health: healthy=$($health.healthy), version=$($health.version)"
    } catch {
        Write-Output "Shared health: unavailable"
    }
} else {
    Write-Output "Shared health: credentials unavailable from Desktop profile"
}
$serverPassword = $null
$basic = $null

Section "Desktop bridge"
$tokenPath = Join-Path $env:LOCALAPPDATA "OpenCodeMobile\bridge-token.dat"
if (-not (Test-Path -LiteralPath $tokenPath)) {
    Write-Output "Bridge token: not provisioned"
} else {
    $pointer = [IntPtr]::Zero
    try {
        $secureToken = ([IO.File]::ReadAllText($tokenPath, [Text.Encoding]::UTF8)) | ConvertTo-SecureString
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
        $bridgeToken = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
        $bridgeState = Invoke-RestMethod -Uri "http://127.0.0.1:4097/state" -Headers @{
            Authorization = "Bearer $bridgeToken"
        } -TimeoutSec 5
        Write-Output "Bridge: reachable, desktopVersion=$($bridgeState.desktopVersion), desktopUsesShared=$($bridgeState.desktop.desktopUsesSharedServer)"
        Write-Output "Bridge tabs: shared=$(@($bridgeState.desktop.sharedOpenSessionIds).Count), private=$(@($bridgeState.desktop.privateOpenSessionIds).Count)"
        Write-Output "Model preferences: $(@($bridgeState.models.user).Count)"
    } catch {
        Write-Output "Bridge: unavailable or token mismatch"
    } finally {
        if ($pointer -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        }
        $bridgeToken = $null
    }
}

Section "Android connectivity"
$adb = Find-Adb
if (-not $adb) {
    Write-Output "ADB: not found"
} else {
    $deviceLines = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\S" })
    $ready = @($deviceLines | Where-Object { $_ -match "\tdevice$" })
    $unauthorized = @($deviceLines | Where-Object { $_ -match "\tunauthorized$" })
    $offline = @($deviceLines | Where-Object { $_ -match "\toffline$" })
    $wireless = @($ready | Where-Object { $_ -match "^\d+\.\d+\.\d+\.\d+:\d+" })
    Write-Output "ADB devices: ready=$($ready.Count), wireless=$($wireless.Count), unauthorized=$($unauthorized.Count), offline=$($offline.Count)"
    foreach ($line in $ready) {
        $serial = ($line -split "\s+")[0]
        $reverse = @(& $adb -s $serial reverse --list 2>$null)
        $serverReverse = [bool]($reverse | Where-Object { $_ -match "tcp:4096\s+tcp:4096" })
        $bridgeReverse = [bool]($reverse | Where-Object { $_ -match "tcp:4097\s+tcp:4097" })
        Write-Output "ADB transport: wireless=$($serial -match ':'), reverse4096=$serverReverse, reverse4097=$bridgeReverse"
    }
}

Section "Build baseline"
$testDirectory = Join-Path $root "app\build\test-results\testDebugUnitTest"
$tests = 0
$failures = 0
$errors = 0
if (Test-Path -LiteralPath $testDirectory) {
    foreach ($file in Get-ChildItem -LiteralPath $testDirectory -Filter "TEST-*.xml" -ErrorAction SilentlyContinue) {
        try {
            $xml = [xml]([IO.File]::ReadAllText($file.FullName))
            $tests += [int]$xml.testsuite.tests
            $failures += [int]$xml.testsuite.failures
            $errors += [int]$xml.testsuite.errors
        } catch {}
    }
}
Write-Output "Unit tests: tests=$tests, failures=$failures, errors=$errors"
$lintPath = Join-Path $root "app\build\reports\lint-results-debug.txt"
$lint = if (Test-Path -LiteralPath $lintPath) {
    ([IO.File]::ReadAllLines($lintPath) | Select-Object -First 1)
} else { "not run" }
Write-Output "Lint: $lint"
foreach ($artifact in @(
    "app\build\outputs\apk\debug\app-debug.apk",
    "app\build\outputs\apk\release\app-release-unsigned.apk"
)) {
    $path = Join-Path $root $artifact
    if (Test-Path -LiteralPath $path) {
        $item = Get-Item -LiteralPath $path
        Write-Output "${artifact}: present, bytes=$($item.Length), modified=$($item.LastWriteTime.ToString('o'))"
    } else {
        Write-Output "${artifact}: missing"
    }
}

Section "Migration"
$migrationLog = Join-Path $env:LOCALAPPDATA "OpenCodeMobile\desktop-migration.log"
if (Test-Path -LiteralPath $migrationLog) {
    Write-Output "Latest migration log entries:"
    Get-Content -LiteralPath $migrationLog -Tail 4 | ForEach-Object { Write-Output "  $_" }
} else {
    Write-Output "No migration log"
}
