param(
    [int]$Port = 4096,
    [string]$Username = "opencode",
    [switch]$RestartExisting
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "shared-integration-guard.ps1")
Assert-SharedIntegrationAvailable

$stateDirectory = Join-Path $env:LOCALAPPDATA "OpenCodeMobile"
$passwordPath = Join-Path $stateDirectory "server-password.dat"
$bridgeTokenPath = Join-Path $stateDirectory "bridge-token.dat"
$runtimeDirectory = Join-Path $stateDirectory "runtime"
$workerSource = Join-Path $PSScriptRoot "runtime-worker.ps1"
$bridgeSource = Join-Path $PSScriptRoot "desktop-bridge.mjs"
$worker = Join-Path $runtimeDirectory "runtime-worker.ps1"
$bridgeRuntime = Join-Path $runtimeDirectory "desktop-bridge.mjs"
$stopRequest = Join-Path $stateDirectory "runtime.stop"
$desktopGlobal = Join-Path $env:APPDATA "ai.opencode.desktop\opencode.global.dat"
$desktopExecutable = Join-Path $env:LOCALAPPDATA "Programs\@opencode-aidesktop\OpenCode.exe"
$managedCliDirectory = Join-Path $stateDirectory "cli"
$managedCli = Join-Path $managedCliDirectory "node_modules\opencode-ai\bin\opencode.exe"
$url = "http://127.0.0.1:$Port"
$bridgePort = $Port + 1

New-Item -ItemType Directory -Path $stateDirectory -Force | Out-Null
New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null

function Save-DpapiSecret([string]$Path, [string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { throw "Cannot save an empty secret." }
    $secure = ConvertTo-SecureString $Value -AsPlainText -Force
    [IO.File]::WriteAllText($Path, ($secure | ConvertFrom-SecureString), (New-Object Text.UTF8Encoding($false)))
}

function Read-DesktopPassword {
    if (-not (Test-Path -LiteralPath $desktopGlobal)) { return $null }
    try {
        $global = [IO.File]::ReadAllText($desktopGlobal, [Text.Encoding]::UTF8) | ConvertFrom-Json
        $servers = $global.server | ConvertFrom-Json
        $entry = @($servers.list | Where-Object {
            $_.http -and $_.http.url.Trim().TrimEnd("/") -eq $url
        }) | Select-Object -First 1
        if ($entry -and -not [string]::IsNullOrEmpty([string]$entry.http.password)) {
            return [string]$entry.http.password
        }
    } catch {}
    return $null
}

function Get-RuntimeWorkers {
    return @(Get-CimInstance Win32_Process | Where-Object {
        $_.ExecutablePath -and
        $_.ExecutablePath -like "*\powershell.exe" -and
        $_.CommandLine -and
        $_.CommandLine -match '(?i)-File\s+"?[^\r\n"]*runtime-worker\.ps1' -and
        $_.ProcessId -ne $PID
    })
}

function Get-ActiveSessionCount([string]$Password) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if (-not $listener) { return 0 }
    $auth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${Username}:$Password"))
    try {
        $active = Invoke-RestMethod -Uri "$url/api/session/active" -Headers @{
            Authorization = "Basic $auth"
        } -TimeoutSec 5
        $data = if ($active.data) { $active.data } else { $active }
        return @($data.PSObject.Properties).Count
    } catch {
        throw "Cannot verify active shared sessions; refusing to restart the existing server. $($_.Exception.Message)"
    } finally {
        $auth = $null
    }
}

function Stop-RuntimeWorkers($Workers) {
    if (@($Workers).Count -eq 0) { return }
    [IO.File]::WriteAllText($stopRequest, (Get-Date -Format o), (New-Object Text.UTF8Encoding($false)))
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $remaining = @(Get-CimInstance Win32_Process | Where-Object {
            $_.ProcessId -in @($Workers.ProcessId)
        })
        if ($remaining.Count -eq 0) { break }
        Start-Sleep -Seconds 1
    }
    $remaining = @(Get-CimInstance Win32_Process | Where-Object {
        $_.ProcessId -in @($Workers.ProcessId)
    })
    if ($remaining.Count -gt 0) { throw "Runtime worker did not stop cleanly within 30 seconds." }
    Remove-Item -LiteralPath $stopRequest -Force -ErrorAction SilentlyContinue
}

function Stop-VerifiedListeners {
    foreach ($targetPort in @($Port, $bridgePort)) {
        $listener = Get-NetTCPConnection -State Listen -LocalPort $targetPort -ErrorAction SilentlyContinue
        if (-not $listener) { continue }
        $owner = Get-CimInstance Win32_Process | Where-Object ProcessId -eq $listener.OwningProcess
        $expected = if ($targetPort -eq $Port) {
            $owner.ExecutablePath -like "*\opencode.exe" -and
            $owner.CommandLine -match "\bserve\b" -and
            $owner.CommandLine -match "--port\s+$Port\b"
        } else {
            $owner.ExecutablePath -like "*\node.exe" -and $owner.CommandLine -like "*desktop-bridge.mjs*"
        }
        if (-not $expected) {
            throw "Port $targetPort is owned by an unexpected process; refusing to terminate it."
        }
        Stop-Process -Id $listener.OwningProcess -Force
    }
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {
            $_.LocalPort -in @($Port, $bridgePort)
        })
        if ($listeners.Count -eq 0) { return }
        Start-Sleep -Milliseconds 250
    }
    throw "Ports $Port/$bridgePort did not become free."
}

function Same-File([string]$First, [string]$Second) {
    if (-not (Test-Path -LiteralPath $First) -or -not (Test-Path -LiteralPath $Second)) { return $false }
    return (Get-FileHash -LiteralPath $First -Algorithm SHA256).Hash -eq
        (Get-FileHash -LiteralPath $Second -Algorithm SHA256).Hash
}

if (-not (Test-Path -LiteralPath $desktopExecutable)) {
    throw "OpenCode Desktop was not found at $desktopExecutable."
}
$desktopVersion = ([Version](Get-Item -LiteralPath $desktopExecutable).VersionInfo.ProductVersion).ToString(3)
if ($desktopVersion -notin @("1.18.25", "1.18.26", "1.18.27")) {
    throw "OpenCode Mobile runtime supports Desktop 1.18.25, 1.18.26 and 1.18.27 only; found $desktopVersion."
}

$password = Read-DesktopPassword
if (-not $password) {
    $secure = Read-Host "OpenCode shared server password" -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}
Save-DpapiSecret $passwordPath $password

if (-not (Test-Path -LiteralPath $bridgeTokenPath)) {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $token = ([BitConverter]::ToString($bytes)).Replace("-", "").ToLowerInvariant()
    Save-DpapiSecret $bridgeTokenPath $token
    [Array]::Clear($bytes, 0, $bytes.Length)
    $token = $null
}

$managedVersion = if (Test-Path -LiteralPath $managedCli) {
    try { ([string](& $managedCli --version 2>$null)).Trim() } catch { "" }
} else { "" }
$updateNeeded =
    -not (Same-File $workerSource $worker) -or
    -not (Same-File $bridgeSource $bridgeRuntime) -or
    $managedVersion -ne $desktopVersion

$workers = @(Get-RuntimeWorkers)
$listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {
    $_.LocalPort -in @($Port, $bridgePort)
})
if ($RestartExisting -and ($workers.Count -gt 0 -or $listeners.Count -gt 0)) {
    $activeCount = Get-ActiveSessionCount $password
    if ($activeCount -gt 0) {
        throw "The shared server has $activeCount active session(s); refusing to restart it."
    }
    Stop-RuntimeWorkers $workers
    Stop-VerifiedListeners
    $workers = @()
} elseif ($workers.Count -gt 0 -and $updateNeeded) {
    throw "The runtime is running and requires an update. Rerun with -RestartExisting after shared tasks finish."
} elseif ($workers.Count -eq 0 -and $listeners.Count -gt 0) {
    throw "Ports $Port/$bridgePort are already in use without a runtime worker. Rerun with -RestartExisting after shared tasks finish."
}

if ($workers.Count -eq 0) {
    Copy-Item -LiteralPath $workerSource -Destination $worker -Force
    Copy-Item -LiteralPath $bridgeSource -Destination $bridgeRuntime -Force

    if ($managedVersion -ne $desktopVersion) {
        $npm = @(
            "$env:ProgramFiles\nodejs\npm.cmd",
            "$env:APPDATA\npm\npm.cmd",
            (Get-Command npm.cmd -ErrorAction SilentlyContinue).Source
        ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
        if (-not $npm) { throw "npm.cmd was not found; cannot install OpenCode CLI $desktopVersion." }
        & $npm install --prefix $managedCliDirectory --no-audit --no-fund --save=false "opencode-ai@$desktopVersion"
        if ($LASTEXITCODE -ne 0) { throw "Failed to install the managed OpenCode CLI $desktopVersion." }
    }
    if (-not (Test-Path -LiteralPath $managedCli)) {
        throw "Managed OpenCode CLI installation did not produce $managedCli."
    }
    $verifiedManagedVersion = ([string](& $managedCli --version 2>$null)).Trim()
    if ($verifiedManagedVersion -ne $desktopVersion) {
        throw "Managed CLI version $verifiedManagedVersion does not match Desktop $desktopVersion."
    }
}

$runPath = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
$runCommand = "powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$worker`" -Port $Port -Hostname 127.0.0.1 -Username `"$Username`""
Set-ItemProperty -LiteralPath $runPath -Name "OpenCode Mobile Runtime" -Value $runCommand

Remove-Item -LiteralPath $stopRequest -Force -ErrorAction SilentlyContinue
if ($workers.Count -eq 0) {
    Start-Process -FilePath "powershell.exe" -ArgumentList @(
        "-NoLogo",
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-WindowStyle", "Hidden",
        "-File", $worker,
        "-Port", $Port,
        "-Hostname", "127.0.0.1",
        "-Username", $Username
    ) -WindowStyle Hidden | Out-Null
}

try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try {
            $auth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${Username}:$password"))
            $health = Invoke-RestMethod -Uri "$url/global/health" -Headers @{
                Authorization = "Basic $auth"
            } -TimeoutSec 2
            if ($health.healthy -and $health.version -eq $desktopVersion) {
                $ready = $true
                break
            }
        } catch {}
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw "Runtime was installed but the shared server did not become healthy at version $desktopVersion." }

    $runningWorkers = @(Get-RuntimeWorkers)
    if ($runningWorkers.Count -ne 1) { throw "Expected one runtime worker, found $($runningWorkers.Count)." }
    $serverListener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop
    $serverOwner = Get-CimInstance Win32_Process | Where-Object ProcessId -eq $serverListener.OwningProcess
    if ($serverOwner.ParentProcessId -ne $runningWorkers[0].ProcessId) {
        throw "The healthy server is not owned by the runtime worker."
    }

    Write-Host "OpenCode Mobile runtime is healthy on $url (version $($health.version))."
    Write-Host "Managed CLI version: $desktopVersion"
    Write-Host "Login startup installed: HKCU Run\OpenCode Mobile Runtime"
} finally {
    $password = $null
    $auth = $null
}
