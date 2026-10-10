param(
    [Parameter(Mandatory = $true)]
    [string]$PasswordFile,
    [int]$DelaySeconds = 25
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "shared-integration-guard.ps1")
Assert-SharedIntegrationAvailable

$stateDirectory = Join-Path $env:LOCALAPPDATA "OpenCodeMobile"
$logPath = Join-Path $stateDirectory "desktop-migration.log"
$desktopPath = Join-Path $env:LOCALAPPDATA "Programs\@opencode-aidesktop\OpenCode.exe"
$pointer = [IntPtr]::Zero

New-Item -ItemType Directory -Path $stateDirectory -Force | Out-Null

function Write-Log([string]$Message) {
    $line = "$(Get-Date -Format o) $Message`r`n"
    [IO.File]::AppendAllText($logPath, $line, [Text.Encoding]::UTF8)
}

try {
    $securePassword = ([IO.File]::ReadAllText($PasswordFile, [Text.Encoding]::UTF8)) | ConvertTo-SecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $Password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    Write-Log "Scheduled migration waiting $DelaySeconds seconds"
    Start-Sleep -Seconds $DelaySeconds

    $desktopProcesses = Get-CimInstance Win32_Process | Where-Object {
        $_.ExecutablePath -and $_.ExecutablePath.Equals($desktopPath, [StringComparison]::OrdinalIgnoreCase)
    }
    foreach ($process in $desktopProcesses) {
        Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
    }

    for ($index = 0; $index -lt 20; $index++) {
        $remaining = Get-CimInstance Win32_Process | Where-Object {
            $_.ExecutablePath -and $_.ExecutablePath.Equals($desktopPath, [StringComparison]::OrdinalIgnoreCase)
        }
        if (-not $remaining) { break }
        Start-Sleep -Milliseconds 500
    }

    Write-Log "Desktop stopped; applying shared-server migration"
    $migration = @{
        Password = $Password
        ConfirmHome = $true
    }
    & (Join-Path $PSScriptRoot "configure-desktop-shared.ps1") @migration

    Write-Log "Migration completed; restarting Desktop"
    Start-Process -FilePath $desktopPath
    Write-Log "Desktop restart requested"
} catch {
    Write-Log "Migration failed: $($_.Exception.Message)"
    try {
        if (-not (Get-CimInstance Win32_Process | Where-Object {
            $_.ExecutablePath -and $_.ExecutablePath.Equals($desktopPath, [StringComparison]::OrdinalIgnoreCase)
        })) {
            Start-Process -FilePath $desktopPath
        }
    } catch {}
    exit 1
} finally {
    if ($pointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
    Remove-Item -LiteralPath $PasswordFile -Force -ErrorAction SilentlyContinue
    $Password = $null
}
