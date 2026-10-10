param(
    [int]$Port = 4096,
    [string]$Serial
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "shared-integration-guard.ps1")
Assert-SharedIntegrationAvailable

$stateDirectory = Join-Path $env:LOCALAPPDATA "OpenCodeMobile"
$tokenPath = Join-Path $stateDirectory "bridge-token.dat"
$bridgePort = $Port + 1

function Resolve-Executable([string]$Name, [string[]]$Candidates) {
    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
    }
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "$Name was not found."
}

function Read-DpapiSecret([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { throw "Bridge token is not available. Start the runtime first." }
    $secure = ([IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8)) | ConvertTo-SecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

$tailscale = Resolve-Executable "tailscale" @("C:\Program Files\Tailscale\tailscale.exe")
$adb = Resolve-Executable "adb" @(
    "$env:ANDROID_HOME\platform-tools\adb.exe",
    "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe",
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "$env:TEMP\opencode\android-sdk\platform-tools\adb.exe",
    "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
)

$tailnetIp = [string](& $tailscale ip -4)
if ([string]::IsNullOrWhiteSpace($tailnetIp)) { throw "Tailscale is not connected on this computer." }

& $tailscale serve --bg "--tcp=$Port" "tcp://127.0.0.1:$Port"
if ($LASTEXITCODE -ne 0) { throw "Failed to expose the shared server through Tailscale Serve." }
& $tailscale serve --bg "--tcp=$bridgePort" "tcp://127.0.0.1:$bridgePort"
if ($LASTEXITCODE -ne 0) { throw "Failed to expose the Desktop bridge through Tailscale Serve." }

& $adb start-server | Out-Null
$devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" })
if ($Serial) {
    $devices = @($devices | Where-Object { ($_ -split "\s+")[0] -eq $Serial })
}
if ($devices.Count -ne 1) {
    throw "Connect exactly one authorized Android device, or pass -Serial."
}
$deviceSerial = ($devices[0] -split "\s+")[0]
$target = @("-s", $deviceSerial)

$serverUrl = "http://${tailnetIp}:$Port"
$bridgeUrl = "http://${tailnetIp}:$bridgePort"
& $adb @target shell "toybox nc -z -w 8 $tailnetIp $Port"
if ($LASTEXITCODE -ne 0) { throw "The phone cannot reach the Tailnet server endpoint." }
& $adb @target shell "toybox nc -z -w 8 $tailnetIp $bridgePort"
if ($LASTEXITCODE -ne 0) { throw "The phone cannot reach the Tailnet bridge endpoint." }

$token = Read-DpapiSecret $tokenPath
try {
    & $adb @target shell am broadcast `
        -a ai.opencode.mobile.CONFIGURE_BRIDGE `
        -n ai.opencode.mobile/.BridgeProvisionReceiver `
        --es opencode_server_url $serverUrl `
        --es opencode_bridge_url $bridgeUrl `
        --es opencode_bridge_token $token | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Tailnet profile provisioning failed." }
} finally {
    $token = $null
}

& $adb @target shell am force-stop ai.opencode.mobile | Out-Null
& $adb @target shell monkey -p ai.opencode.mobile -c android.intent.category.LAUNCHER 1 | Out-Null
Write-Host "OpenCode Mobile now uses the Tailnet endpoints on $deviceSerial."
Write-Host "Server: $serverUrl"
Write-Host "Bridge: $bridgeUrl"
