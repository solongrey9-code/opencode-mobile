param(
    [int]$Port = 4096,
    [switch]$SkipReverse,
    [string]$ServerUrl,
    [string]$BridgeUrl
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "shared-integration-guard.ps1")
Assert-SharedIntegrationAvailable

$root = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path -LiteralPath $apk)) {
    throw "Debug APK not found. Run .\gradlew.bat assembleDebug first."
}

$adbCandidates = @(
    "$env:ANDROID_HOME\platform-tools\adb.exe",
    "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe",
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "$env:TEMP\opencode\android-sdk\platform-tools\adb.exe",
    "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
)
$adb = $adbCandidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
if (-not $adb) {
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) { $adb = $adbCommand.Source }
}
if (-not $adb) {
    throw "Cannot find adb. Install Android platform-tools or set ANDROID_HOME."
}

& $adb start-server | Out-Null
$devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" })
if ($devices.Count -eq 0) {
    throw "No authorized Android device found. Enable USB debugging and accept the computer authorization prompt."
}
$serials = @($devices | ForEach-Object { ($_ -split "\s+")[0] })
$serial = @($serials | Where-Object { $_ -notmatch ":" } | Select-Object -First 1)
if (-not $serial) { $serial = @($serials | Select-Object -First 1) }
$target = @("-s", $serial[0])
$serverUrl = if ($ServerUrl) { $ServerUrl.Trim().TrimEnd("/") } else { "http://127.0.0.1:$Port" }
$bridgeUrl = if ($BridgeUrl) { $BridgeUrl.Trim().TrimEnd("/") } else { "http://127.0.0.1:$($Port + 1)" }

& $adb @target install --no-streaming -r $apk
if ($LASTEXITCODE -ne 0) {
    throw "ADB failed to install the APK (exit code $LASTEXITCODE)."
}
if (-not $SkipReverse) {
    & $adb @target reverse "tcp:$Port" "tcp:$Port" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "ADB failed to configure tcp:$Port reverse forwarding."
    }
    & $adb @target reverse "tcp:$($Port + 1)" "tcp:$($Port + 1)" | Out-Null
}
$tokenPath = Join-Path $env:LOCALAPPDATA "OpenCodeMobile\bridge-token.dat"
if (-not $SkipReverse -and (Test-Path -LiteralPath $tokenPath)) {
    $secureToken = ([IO.File]::ReadAllText($tokenPath, [Text.Encoding]::UTF8)) | ConvertTo-SecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
    try {
        $bridgeToken = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
        & $adb @target shell am broadcast `
            -a ai.opencode.mobile.CONFIGURE_BRIDGE `
            -n ai.opencode.mobile/.BridgeProvisionReceiver `
            --es opencode_server_url $serverUrl `
            --es opencode_bridge_url $bridgeUrl `
            --es opencode_bridge_token $bridgeToken | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Failed to provision the Desktop bridge." }
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        $bridgeToken = $null
    }
}
& $adb @target shell am force-stop ai.opencode.mobile | Out-Null
& $adb @target shell monkey -p ai.opencode.mobile -c android.intent.category.LAUNCHER 1 | Out-Null
Write-Host "OpenCode Mobile installed and launched."
