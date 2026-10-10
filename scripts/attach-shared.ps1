param(
    [string]$Url = "http://127.0.0.1:4096",
    [string]$Username = "opencode"
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "shared-integration-guard.ps1")
Assert-SharedIntegrationAvailable

$opencode = @(
    "$env:LOCALAPPDATA\OpenCodeMobile\cli\node_modules\opencode-ai\bin\opencode.exe",
    "$env:APPDATA\npm\opencode.cmd",
    "$env:APPDATA\npm\node_modules\opencode-ai\bin\opencode.exe"
) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $opencode) { throw "OpenCode CLI was not found." }

$secure = Read-Host "OpenCode server password" -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
$previousUsername = [Environment]::GetEnvironmentVariable("OPENCODE_SERVER_USERNAME", "Process")
$previousPassword = [Environment]::GetEnvironmentVariable("OPENCODE_SERVER_PASSWORD", "Process")
try {
    $env:OPENCODE_SERVER_USERNAME = $Username
    $env:OPENCODE_SERVER_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    & $opencode attach $Url
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
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
}
