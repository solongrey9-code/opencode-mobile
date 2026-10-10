$ErrorActionPreference = "Stop"
$stateDirectory = Join-Path $env:LOCALAPPDATA "OpenCodeMobile"
$stopRequest = Join-Path $stateDirectory "runtime.stop"

if (-not (Test-Path -LiteralPath $stateDirectory)) {
    Write-Host "OpenCode Mobile runtime is not installed."
    exit 0
}

$workers = @(Get-CimInstance Win32_Process | Where-Object {
    $_.ExecutablePath -and
    $_.ExecutablePath -like "*\powershell.exe" -and
    $_.CommandLine -and
    $_.CommandLine -match '(?i)-File\s+"?[^\r\n"]*runtime-worker\.ps1' -and
    $_.ProcessId -ne $PID
})
if ($workers.Count -eq 0) {
    Write-Host "OpenCode Mobile runtime is already stopped. Login startup configuration is unchanged."
    exit 0
}

[IO.File]::WriteAllText($stopRequest, (Get-Date -Format o), (New-Object Text.UTF8Encoding($false)))
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    $remaining = @(Get-CimInstance Win32_Process | Where-Object {
        $_.ProcessId -in $workers.ProcessId
    })
    if ($remaining.Count -eq 0) { break }
    Start-Sleep -Seconds 1
}
$remaining = @(Get-CimInstance Win32_Process | Where-Object {
    $_.ProcessId -in $workers.ProcessId
})
if ($remaining.Count -gt 0) {
    throw "Runtime did not stop cleanly within 20 seconds."
}
Remove-Item -LiteralPath $stopRequest -Force -ErrorAction SilentlyContinue
Write-Host "OpenCode Mobile runtime stopped. Login startup configuration is unchanged."
