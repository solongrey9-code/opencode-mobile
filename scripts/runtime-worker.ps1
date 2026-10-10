param(
    [int]$Port = 4096,
    [string]$Hostname = "127.0.0.1",
    [string]$Username = "opencode"
)

$ErrorActionPreference = "Stop"
$repositoryMarker = Join-Path $PSScriptRoot "shared-integration.retired"
$hostMarker = Join-Path $env:LOCALAPPDATA "OpenCodeMobile\integration-retired.json"
if ((Test-Path -LiteralPath $repositoryMarker) -or (Test-Path -LiteralPath $hostMarker)) {
    Write-Output "OpenCode Mobile shared integration is retired; runtime worker will not start."
    exit 0
}

$stateDirectory = Join-Path $env:LOCALAPPDATA "OpenCodeMobile"
$passwordPath = Join-Path $stateDirectory "server-password.dat"
$bridgeTokenPath = Join-Path $stateDirectory "bridge-token.dat"
$runtimeLog = Join-Path $stateDirectory "runtime.log"
$runtimePid = Join-Path $stateDirectory "runtime.pid"
$runtimeLock = Join-Path $stateDirectory "runtime.lock"
$stopRequest = Join-Path $stateDirectory "runtime.stop"
$serverOut = Join-Path $stateDirectory "server.stdout.log"
$serverErr = Join-Path $stateDirectory "server.stderr.log"
$bridgeOut = Join-Path $stateDirectory "bridge.stdout.log"
$bridgeErr = Join-Path $stateDirectory "bridge.stderr.log"
$bridgePort = $Port + 1
$server = $null
$bridge = $null
$lock = $null
$lastDesktopVersion = ""
$serverHealthFailures = 0

New-Item -ItemType Directory -Path $stateDirectory -Force | Out-Null

function Write-RuntimeLog([string]$Message) {
    if ((Test-Path -LiteralPath $runtimeLog) -and (Get-Item -LiteralPath $runtimeLog).Length -gt 1MB) {
        Move-Item -LiteralPath $runtimeLog -Destination "$runtimeLog.previous" -Force
    }
    [IO.File]::AppendAllText(
        $runtimeLog,
        "$(Get-Date -Format o) $Message`r`n",
        [Text.Encoding]::UTF8
    )
}

function Resolve-Executable([string]$Name, [string[]]$Candidates) {
    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
    }
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) {
        if ($command.Source -like "*.ps1") {
            $cmd = [IO.Path]::ChangeExtension($command.Source, ".cmd")
            if (Test-Path -LiteralPath $cmd) { return $cmd }
        }
        return $command.Source
    }
    throw "$Name was not found."
}

function Read-DpapiSecret([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { throw "Secret file is missing: $Path" }
    $secure = ([IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8)) | ConvertTo-SecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Get-DesktopVersion {
    $path = Join-Path $env:LOCALAPPDATA "Programs\@opencode-aidesktop\OpenCode.exe"
    if (-not (Test-Path -LiteralPath $path)) { return "missing" }
    return ([Version](Get-Item -LiteralPath $path).VersionInfo.ProductVersion).ToString(3)
}

function Test-PortFree([int]$TargetPort) {
    return -not (Get-NetTCPConnection -State Listen -LocalPort $TargetPort -ErrorAction SilentlyContinue)
}

function Start-SharedServer([string]$Password) {
    if (-not (Test-PortFree $Port)) {
        Write-RuntimeLog "Server port $Port is occupied; waiting without terminating the owner"
        return $null
    }
    $opencode = Resolve-Executable "opencode" @(
        "$stateDirectory\cli\node_modules\opencode-ai\bin\opencode.exe",
        "$env:APPDATA\npm\node_modules\opencode-ai\bin\opencode.exe",
        "$env:APPDATA\npm\opencode.cmd"
    )
    Remove-Item Env:OPENCODE_MOBILE_BRIDGE_TOKEN -ErrorAction SilentlyContinue
    $env:OPENCODE_SERVER_USERNAME = $Username
    $env:OPENCODE_SERVER_PASSWORD = $Password
    try {
        $process = Start-Process -FilePath $opencode -ArgumentList @(
            "serve",
            "--hostname", $Hostname,
            "--port", $Port
        ) -WindowStyle Hidden -RedirectStandardOutput $serverOut -RedirectStandardError $serverErr -PassThru
    } finally {
        Remove-Item Env:OPENCODE_SERVER_USERNAME -ErrorAction SilentlyContinue
        Remove-Item Env:OPENCODE_SERVER_PASSWORD -ErrorAction SilentlyContinue
    }

    $auth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${Username}:$Password"))
    try {
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            $process.Refresh()
            if ($process.HasExited) {
                Write-RuntimeLog "Server exited during startup with code $($process.ExitCode)"
                return $null
            }
            try {
                $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/global/health" -Headers @{
                    Authorization = "Basic $auth"
                } -TimeoutSec 2
                if ($health.healthy) {
                    Write-RuntimeLog "Server ready pid=$($process.Id) version=$($health.version) port=$Port"
                    return $process
                }
            } catch {}
            Start-Sleep -Milliseconds 500
        }
        Write-RuntimeLog "Server readiness timed out; stopping pid=$($process.Id)"
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        return $null
    } finally {
        $auth = $null
    }
}

function Start-DesktopBridge([string]$Token, [string]$DesktopVersion) {
    if ($DesktopVersion -notin @("1.18.25", "1.18.26", "1.18.27")) {
        if ($DesktopVersion -ne $script:lastDesktopVersion) {
            Write-RuntimeLog "Bridge disabled for unsupported Desktop version $DesktopVersion"
        }
        return $null
    }
    if (-not (Test-PortFree $bridgePort)) {
        Write-RuntimeLog "Bridge port $bridgePort is occupied; waiting without terminating the owner"
        return $null
    }
    $node = Resolve-Executable "node" @("C:\Program Files\nodejs\node.exe")
    $bridgeScript = Join-Path $PSScriptRoot "desktop-bridge.mjs"
    if (-not (Test-Path -LiteralPath $bridgeScript)) {
        throw "Desktop bridge script is missing: $bridgeScript"
    }
    $desktopData = Join-Path $env:APPDATA "ai.opencode.desktop"
    Remove-Item Env:OPENCODE_SERVER_USERNAME -ErrorAction SilentlyContinue
    Remove-Item Env:OPENCODE_SERVER_PASSWORD -ErrorAction SilentlyContinue
    $env:OPENCODE_MOBILE_BRIDGE_TOKEN = $Token
    try {
        $process = Start-Process -FilePath $node -ArgumentList @(
            $bridgeScript,
            "--hostname", $Hostname,
            "--port", $bridgePort,
            "--server-port", $Port,
            "--server-url", "http://127.0.0.1:$Port",
            "--user-data", $desktopData,
            "--desktop-version", $DesktopVersion
        ) -WindowStyle Hidden -RedirectStandardOutput $bridgeOut -RedirectStandardError $bridgeErr -PassThru
    } finally {
        Remove-Item Env:OPENCODE_MOBILE_BRIDGE_TOKEN -ErrorAction SilentlyContinue
    }

    try {
        for ($attempt = 0; $attempt -lt 40; $attempt++) {
            $process.Refresh()
            if ($process.HasExited) {
                Write-RuntimeLog "Bridge exited during startup with code $($process.ExitCode)"
                return $null
            }
            try {
                $health = Invoke-RestMethod -Uri "http://127.0.0.1:$bridgePort/health" -Headers @{
                    Authorization = "Bearer $Token"
                } -TimeoutSec 2
                if ($health.healthy) {
                    Write-RuntimeLog "Bridge ready pid=$($process.Id) desktopVersion=$($health.desktopVersion) port=$bridgePort"
                    return $process
                }
            } catch {}
            Start-Sleep -Milliseconds 250
        }
        Write-RuntimeLog "Bridge readiness timed out; stopping pid=$($process.Id)"
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        return $null
    } finally {
        $Token = $null
    }
}

try {
    try {
        $lock = [IO.File]::Open($runtimeLock, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    } catch [IO.IOException] {
        exit 0
    }
    [IO.File]::WriteAllText($runtimePid, [string]$PID, (New-Object Text.UTF8Encoding($false)))
    Write-RuntimeLog "Runtime worker started pid=$PID host=$Hostname port=$Port"

    Remove-Item Env:OPENCODE_SERVER_USERNAME -ErrorAction SilentlyContinue
    Remove-Item Env:OPENCODE_SERVER_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:OPENCODE_MOBILE_BRIDGE_TOKEN -ErrorAction SilentlyContinue

    while (-not (Test-Path -LiteralPath $stopRequest)) {
        if ($server) {
            $server.Refresh()
            if ($server.HasExited) {
                Write-RuntimeLog "Server exited code=$($server.ExitCode); scheduling restart"
                $server = $null
                $serverHealthFailures = 0
            }
        }
        if (-not $server) {
            if ($bridge -and -not $bridge.HasExited) {
                Stop-Process -Id $bridge.Id -Force -ErrorAction SilentlyContinue
            }
            $bridge = $null
            $password = Read-DpapiSecret $passwordPath
            try {
                $server = Start-SharedServer $password
            } finally {
                $password = $null
            }
            if (-not $server) {
                Start-Sleep -Seconds 5
                continue
            }
        }

        $desktopVersion = Get-DesktopVersion
        if ($bridge) {
            $bridge.Refresh()
            if ($bridge.HasExited -or $desktopVersion -ne $lastDesktopVersion) {
                if (-not $bridge.HasExited) {
                    Stop-Process -Id $bridge.Id -Force -ErrorAction SilentlyContinue
                }
                Write-RuntimeLog "Bridge stopped or Desktop version changed; scheduling bridge restart"
                $bridge = $null
            }
        }
        if (-not $bridge) {
            $token = Read-DpapiSecret $bridgeTokenPath
            try {
                $bridge = Start-DesktopBridge $token $desktopVersion
            } finally {
                $token = $null
            }
        }
        $lastDesktopVersion = $desktopVersion

        for ($second = 0; $second -lt 10; $second++) {
            if (Test-Path -LiteralPath $stopRequest) { break }
            Start-Sleep -Seconds 1
        }
        if (Test-Path -LiteralPath $stopRequest) { break }
        if ($server) {
            $password = Read-DpapiSecret $passwordPath
            try {
                $auth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${Username}:$password"))
                try {
                    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/global/health" -Headers @{
                        Authorization = "Basic $auth"
                    } -TimeoutSec 3
                    if ($health.healthy) { $serverHealthFailures = 0 } else { $serverHealthFailures++ }
                } catch {
                    $serverHealthFailures++
                }
            } finally {
                $auth = $null
                $password = $null
            }
            if ($serverHealthFailures -ge 3) {
                Write-RuntimeLog "Server failed three health checks; restarting pid=$($server.Id)"
                Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
                $server = $null
                $serverHealthFailures = 0
            }
        }
    }
} catch {
    Write-RuntimeLog "Runtime worker stopped by error: $($_.Exception.Message)"
    throw
} finally {
    if ($server -and -not $server.HasExited) {
        Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    }
    if ($bridge -and -not $bridge.HasExited) {
        Stop-Process -Id $bridge.Id -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $runtimePid -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $stopRequest -Force -ErrorAction SilentlyContinue
    if ($lock) { $lock.Dispose() }
}
