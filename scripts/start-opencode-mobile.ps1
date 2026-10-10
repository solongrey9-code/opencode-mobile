param(
    [int]$Port = 4096,
    [switch]$UsbOnly,
    [switch]$ServeOnly,
    [string]$Username = "opencode",
    [string]$Password
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "shared-integration-guard.ps1")
Assert-SharedIntegrationAvailable

function Resolve-Executable([string]$Name, [string[]]$Fallbacks) {
    foreach ($candidate in $Fallbacks) {
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) {
        if ($command.Source -like "*.ps1") {
            $cmd = [IO.Path]::ChangeExtension($command.Source, ".cmd")
            if (Test-Path -LiteralPath $cmd) { return $cmd }
        }
        return $command.Source
    }
    throw "Cannot find $Name. Install it first or add it to PATH."
}

function Convert-SecureStringToPlainText([Security.SecureString]$Secure) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Get-OrCreateBridgeToken {
    $directory = Join-Path $env:LOCALAPPDATA "OpenCodeMobile"
    $path = Join-Path $directory "bridge-token.dat"
    if (Test-Path -LiteralPath $path) {
        $encrypted = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8)
        return Convert-SecureStringToPlainText ($encrypted | ConvertTo-SecureString)
    }
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $token = ([BitConverter]::ToString($bytes)).Replace("-", "").ToLowerInvariant()
    $secure = ConvertTo-SecureString $token -AsPlainText -Force
    [IO.File]::WriteAllText($path, ($secure | ConvertFrom-SecureString), (New-Object Text.UTF8Encoding($false)))
    return $token
}

if (-not $Password) {
    $secure = Read-Host "OpenCode server password" -AsSecureString
    $Password = Convert-SecureStringToPlainText $secure
}
if ([string]::IsNullOrWhiteSpace($Password)) {
    throw "A non-empty password is required."
}

$opencode = Resolve-Executable "opencode" @(
    "$env:LOCALAPPDATA\OpenCodeMobile\cli\node_modules\opencode-ai\bin\opencode.exe",
    "$env:APPDATA\npm\node_modules\opencode-ai\bin\opencode.exe",
    "$env:APPDATA\npm\opencode.cmd"
)
$hostname = if ($UsbOnly) { "127.0.0.1" } else { "0.0.0.0" }
$bridgePort = $Port + 1
$bridge = $null
$server = $null
$bridgeToken = Get-OrCreateBridgeToken

$previousUsername = [Environment]::GetEnvironmentVariable("OPENCODE_SERVER_USERNAME", "Process")
$previousPassword = [Environment]::GetEnvironmentVariable("OPENCODE_SERVER_PASSWORD", "Process")
$previousBridgeToken = [Environment]::GetEnvironmentVariable("OPENCODE_MOBILE_BRIDGE_TOKEN", "Process")

try {
    $occupied = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {
        $_.LocalPort -in @($Port, $bridgePort)
    })
    if ($occupied.Count -gt 0) {
        $ports = ($occupied.LocalPort | Sort-Object -Unique) -join ", "
        throw "Ports $ports are already in use. Run .\scripts\status.cmd and stop only the verified stale stack."
    }

    $adb = Resolve-Executable "adb" @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:TEMP\opencode\android-sdk\platform-tools\adb.exe",
        "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
    )
    & $adb start-server | Out-Null
    $devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" })
    if ($devices.Count -eq 0) {
        Write-Warning "No authorized Android device is connected. USB reverse was not configured."
    } else {
        foreach ($device in $devices) {
            $serial = ($device -split "\s+")[0]
            & $adb -s $serial reverse "tcp:$Port" "tcp:$Port" | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Failed to reverse port $Port for $serial" }
            & $adb -s $serial reverse "tcp:$bridgePort" "tcp:$bridgePort" | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Failed to reverse port $bridgePort for $serial" }
            $installed = & $adb -s $serial shell pm path ai.opencode.mobile
            if ($installed -match "package:") {
                & $adb -s $serial shell am broadcast `
                    -a ai.opencode.mobile.CONFIGURE_BRIDGE `
                    -n ai.opencode.mobile/.BridgeProvisionReceiver `
                    --es opencode_server_url "http://127.0.0.1:$Port" `
                    --es opencode_bridge_url "http://127.0.0.1:$bridgePort" `
                    --es opencode_bridge_token $bridgeToken | Out-Null
                if ($LASTEXITCODE -ne 0) { throw "Failed to provision the bridge for $serial" }
            }
            Write-Host "ADB reverse configured for $serial on ports $Port and $bridgePort"
        }
    }

    $node = Resolve-Executable "node" @("C:\Program Files\nodejs\node.exe")
    $bridgeScript = Join-Path $PSScriptRoot "desktop-bridge.mjs"
    $desktopData = Join-Path $env:APPDATA "ai.opencode.desktop"
    $desktopExecutable = Join-Path $env:LOCALAPPDATA "Programs\@opencode-aidesktop\OpenCode.exe"
    $desktopVersion = if (Test-Path -LiteralPath $desktopExecutable) {
        ([Version](Get-Item -LiteralPath $desktopExecutable).VersionInfo.ProductVersion).ToString(3)
    } else {
        "missing"
    }

    # The bridge receives only its Bearer token, never the OpenCode Basic credentials.
    Remove-Item Env:OPENCODE_SERVER_USERNAME -ErrorAction SilentlyContinue
    Remove-Item Env:OPENCODE_SERVER_PASSWORD -ErrorAction SilentlyContinue
    $env:OPENCODE_MOBILE_BRIDGE_TOKEN = $bridgeToken
    $bridge = Start-Process -FilePath $node -ArgumentList @(
        $bridgeScript,
        "--hostname", $hostname,
        "--port", $bridgePort,
        "--server-port", $Port,
        "--server-url", "http://127.0.0.1:$Port",
        "--user-data", $desktopData,
        "--desktop-version", $desktopVersion
    ) -PassThru -WindowStyle Hidden
    Remove-Item Env:OPENCODE_MOBILE_BRIDGE_TOKEN -ErrorAction SilentlyContinue

    $bridgeReady = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        $bridge.Refresh()
        if ($bridge.HasExited) {
            throw "Desktop bridge exited during startup with code $($bridge.ExitCode)."
        }
        try {
            $bridgeHealth = Invoke-RestMethod -Uri "http://127.0.0.1:$bridgePort/health" -Headers @{
                Authorization = "Bearer $bridgeToken"
            } -TimeoutSec 2
            if ($bridgeHealth.healthy) {
                $bridgeReady = $true
                break
            }
        } catch {}
        Start-Sleep -Milliseconds 250
    }
    if (-not $bridgeReady) { throw "Desktop bridge did not become ready on port $bridgePort." }

    if (-not $UsbOnly) {
        $ruleName = "OpenCode Mobile LAN"
        $existingRule = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
        if (-not $existingRule) {
            try {
                $firewall = @{
                    DisplayName = $ruleName
                    Direction = "Inbound"
                    Action = "Allow"
                    Protocol = "TCP"
                    LocalPort = @($Port, $bridgePort)
                    Profile = "Any"
                    RemoteAddress = "LocalSubnet"
                }
                New-NetFirewallRule @firewall | Out-Null
                Write-Host "Created private-network firewall rule: $ruleName"
            } catch {
                Write-Warning "Could not create the LAN firewall rule. Run this script as Administrator or allow ports $Port and $bridgePort manually."
            }
        }
        $addresses = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object {
                $_.IPAddress -notlike "127.*" -and
                $_.IPAddress -notlike "169.254.*" -and
                $_.PrefixOrigin -ne "WellKnown"
            } |
            Select-Object -ExpandProperty IPAddress -Unique
        foreach ($address in $addresses) {
            Write-Host "  URL: http://${address}:$Port"
        }
        Write-Warning "LAN access uses cleartext HTTP. Use only on a trusted private network."
    }

    $mode = if ($ServeOnly) { "serve" } else { "web" }
    # The OpenCode process receives only Basic credentials, never the bridge token.
    $env:OPENCODE_SERVER_USERNAME = $Username
    $env:OPENCODE_SERVER_PASSWORD = $Password
    Remove-Item Env:OPENCODE_MOBILE_BRIDGE_TOKEN -ErrorAction SilentlyContinue
    $server = Start-Process -FilePath $opencode -ArgumentList @(
        $mode,
        "--hostname", $hostname,
        "--port", $Port
    ) -PassThru -NoNewWindow
    Remove-Item Env:OPENCODE_SERVER_USERNAME -ErrorAction SilentlyContinue
    Remove-Item Env:OPENCODE_SERVER_PASSWORD -ErrorAction SilentlyContinue

    $serverReady = $false
    $auth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${Username}:$Password"))
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        $server.Refresh()
        if ($server.HasExited) {
            throw "OpenCode server exited during startup with code $($server.ExitCode)."
        }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/global/health" -Headers @{
                Authorization = "Basic $auth"
            } -TimeoutSec 2
            if ($health.healthy) {
                $serverReady = $true
                break
            }
        } catch {}
        Start-Sleep -Milliseconds 500
    }
    $auth = $null
    if (-not $serverReady) { throw "OpenCode server did not become ready on port $Port." }

    Write-Host ""
    Write-Host "OpenCode Mobile connection"
    Write-Host "  Username: $Username"
    if ($UsbOnly) {
        Write-Host "  Android URL: http://127.0.0.1:$Port"
        Write-Host "  Desktop URL: http://127.0.0.1:$Port"
    }
    Write-Host "  Directory: leave blank for all projects"
    Write-Host "  Desktop bridge: http://127.0.0.1:$bridgePort"
    Write-Host "  Bridge token: provisioned to connected debug devices"
    Write-Host ""
    Write-Host "Keep this window open. Press Ctrl+C to stop the shared server."

    while (-not $server.HasExited) {
        Start-Sleep -Seconds 1
        $server.Refresh()
        $bridge.Refresh()
        if ($bridge.HasExited) {
            throw "Desktop bridge exited with code $($bridge.ExitCode)."
        }
    }
    if ($server.ExitCode -ne 0) {
        throw "OpenCode server exited with code $($server.ExitCode)."
    }
} finally {
    if ($server -and -not $server.HasExited) {
        Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    }
    if ($bridge -and -not $bridge.HasExited) {
        Stop-Process -Id $bridge.Id -Force -ErrorAction SilentlyContinue
    }
    if ($null -eq $previousUsername) {
        Remove-Item Env:OPENCODE_SERVER_USERNAME -ErrorAction SilentlyContinue
    } else {
        $env:OPENCODE_SERVER_USERNAME = $previousUsername
    }
    if ($null -eq $previousPassword) {
        Remove-Item Env:OPENCODE_SERVER_PASSWORD -ErrorAction SilentlyContinue
    } else {
        $env:OPENCODE_SERVER_PASSWORD = $previousPassword
    }
    if ($null -eq $previousBridgeToken) {
        Remove-Item Env:OPENCODE_MOBILE_BRIDGE_TOKEN -ErrorAction SilentlyContinue
    } else {
        $env:OPENCODE_MOBILE_BRIDGE_TOKEN = $previousBridgeToken
    }
    $Password = $null
    $bridgeToken = $null
    $auth = $null
}
