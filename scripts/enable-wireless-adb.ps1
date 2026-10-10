param(
    [int]$AdbPort = 5555,
    [int]$ServerPort = 4096
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "shared-integration-guard.ps1")
Assert-SharedIntegrationAvailable

$adbCandidates = @(
    "$env:ANDROID_HOME\platform-tools\adb.exe",
    "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe",
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "$env:TEMP\opencode\android-sdk\platform-tools\adb.exe",
    "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
)
$adb = $adbCandidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
if (-not $adb) {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { $adb = $command.Source }
}
if (-not $adb) { throw "adb was not found." }

$connected = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" })
$wireless = @($connected | Where-Object { $_ -match "^\d+\.\d+\.\d+\.\d+:$AdbPort\s" })
if ($wireless.Count -gt 0) {
    $serial = ($wireless[0] -split "\s+")[0]
    & $adb -s $serial reverse "tcp:$ServerPort" "tcp:$ServerPort" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to refresh port $ServerPort for $serial." }
    & $adb -s $serial reverse "tcp:$($ServerPort + 1)" "tcp:$($ServerPort + 1)" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to refresh port $($ServerPort + 1) for $serial." }
    Write-Host "Wireless ADB already connected: $serial"
    Write-Host "Ports $ServerPort and $($ServerPort + 1) were refreshed."
    exit 0
}

$usb = @($connected | Where-Object { $_ -notmatch "^\d+\.\d+\.\d+\.\d+:" })
if ($usb.Count -ne 1) {
    throw "Connect exactly one authorized USB device before enabling wireless ADB."
}

$usbSerial = ($usb[0] -split "\s+")[0]
$deviceIp = (& $adb -s $usbSerial shell ip -4 addr show wlan0 | Select-String -Pattern "inet\s+(\d+\.\d+\.\d+\.\d+)" | ForEach-Object {
    $_.Matches[0].Groups[1].Value
} | Select-Object -First 1)
if (-not $deviceIp) { throw "The phone has no Wi-Fi IPv4 address." }

& $adb -s $usbSerial tcpip $AdbPort
if ($LASTEXITCODE -ne 0) { throw "Failed to enable wireless ADB." }
Start-Sleep -Seconds 2
$connectedMessage = & $adb connect "${deviceIp}:$AdbPort"
if ($LASTEXITCODE -ne 0 -or $connectedMessage -notmatch "connected to|already connected") {
    throw "Failed to connect to wireless ADB at ${deviceIp}:$AdbPort. $connectedMessage"
}

$serial = "${deviceIp}:$AdbPort"
& $adb -s $serial reverse "tcp:$ServerPort" "tcp:$ServerPort" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Failed to reverse port $ServerPort for $serial." }
& $adb -s $serial reverse "tcp:$($ServerPort + 1)" "tcp:$($ServerPort + 1)" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Failed to reverse port $($ServerPort + 1) for $serial." }
Write-Host "Wireless ADB connected: $serial"
Write-Host "Ports $ServerPort and $($ServerPort + 1) remain forwarded after unplugging USB."
