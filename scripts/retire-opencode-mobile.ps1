param(
    [switch]$Apply,
    [switch]$DisableStartupOnly,
    [string]$InputBackupDirectory
)

$ErrorActionPreference = 'Stop'
# A PowerShell 7 parent can pass its module search path to powershell.exe.
# Load the matching built-in modules explicitly before reading DPAPI secrets.
if ($PSVersionTable.PSVersion.Major -eq 5) {
    foreach ($module in @('Microsoft.PowerShell.Security', 'Microsoft.PowerShell.Utility', 'Microsoft.PowerShell.Management')) {
        Import-Module (Join-Path $PSHOME "Modules\$module\$module.psd1") -ErrorAction Stop
    }
}
if ($Apply -and $DisableStartupOnly) { throw 'Choose -Apply or -DisableStartupOnly.' }
$stateDirectory = Join-Path $env:LOCALAPPDATA 'OpenCodeMobile'
$desktopData = Join-Path $env:APPDATA 'ai.opencode.desktop'
$desktopExecutable = Join-Path $env:LOCALAPPDATA 'Programs\@opencode-aidesktop\OpenCode.exe'
$installedWorker = Join-Path $stateDirectory 'runtime\runtime-worker.ps1'
$sourceWorker = Join-Path $PSScriptRoot 'runtime-worker.ps1'
$managedServer = Join-Path $stateDirectory 'cli\node_modules\opencode-ai\bin\opencode.exe'
$markerPath = Join-Path $stateDirectory 'integration-retired.json'
$runPath = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$runName = 'OpenCode Mobile Runtime'
$serverUrl = 'http://127.0.0.1:4096'
$utf8 = New-Object Text.UTF8Encoding($false)

function Write-RetirementJson([string]$Path, $Value) {
    [IO.File]::WriteAllText($Path, (ConvertTo-Json -InputObject $Value -Depth 100), $utf8)
}

function Get-MobileWorkers {
    $paths = @($installedWorker, $sourceWorker)
    return @(Get-CimInstance Win32_Process | Where-Object {
        if ($_.ProcessId -eq $PID -or -not $_.ExecutablePath -or -not $_.CommandLine) { return $false }
        if (-not $_.ExecutablePath.EndsWith('\powershell.exe', [StringComparison]::OrdinalIgnoreCase)) { return $false }
        foreach ($workerPath in $paths) {
            $escapedPath = [regex]::Escape($workerPath)
            if ($_.CommandLine -match ('(?i)-File\s+"?' + $escapedPath + '"?(?:\s|$)')) { return $true }
        }
        return $false
    })
}

function Get-DesktopProcesses {
    return @(Get-CimInstance Win32_Process | Where-Object {
        $_.ExecutablePath -and $_.ExecutablePath.Equals($desktopExecutable, [StringComparison]::OrdinalIgnoreCase)
    })
}

function Get-ManagedListener {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort 4096 -ErrorAction SilentlyContinue | Where-Object {
        $_.LocalAddress -in @('127.0.0.1', '0.0.0.0', '::1', '::')
    })
    if ($listeners.Count -eq 0) { return $null }
    $ownerIds = @($listeners.OwningProcess | Sort-Object -Unique)
    if ($ownerIds.Count -ne 1) { throw 'Multiple local server owners; refusing retirement.' }
    $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($ownerIds[0])"
    if (-not $owner -or -not $owner.ExecutablePath -or
        -not $owner.ExecutablePath.Equals($managedServer, [StringComparison]::OrdinalIgnoreCase) -or
        $owner.CommandLine -notmatch '\bserve\b' -or $owner.CommandLine -notmatch '--port\s+4096\b') {
        throw 'Port 4096 is not the managed Mobile server; refusing to stop it.'
    }
    return $owner
}

function Assert-SharedServerIdle($Owner) {
    if (-not $Owner) { return }
    $passwordPath = Join-Path $stateDirectory 'server-password.dat'
    $pointer = [IntPtr]::Zero
    try {
        $securePassword = ([IO.File]::ReadAllText($passwordPath, [Text.Encoding]::UTF8)) | ConvertTo-SecureString
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
        $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
        $basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("opencode:$password"))
        $response = Invoke-RestMethod -Uri "$serverUrl/api/session/active" -Headers @{Authorization="Basic $basic"} -TimeoutSec 15
        if (-not ($response -is [PSCustomObject]) -or
            -not $response.PSObject.Properties['data'] -or -not ($response.data -is [PSCustomObject])) {
            throw 'Unrecognized active-session response.'
        }
        if (@($response.data.PSObject.Properties).Count -gt 0) { throw 'Shared sessions are active.' }
    } catch {
        $failureType = $_.Exception.GetType().Name
        $failureId = $_.FullyQualifiedErrorId
        $httpStatus = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 'none' }
        throw "Cannot prove the shared server is idle (type=$failureType; id=$failureId; HTTP=$httpStatus); no running server has been stopped."
    } finally {
        if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
        $password = $null
        $basic = $null
    }
}

function Get-TailnetPlan {
    $executable = 'C:\Program Files\Tailscale\tailscale.exe'
    if (-not (Test-Path -LiteralPath $executable)) { return [PSCustomObject]@{Executable=$null; Json=$null; Ports=@()} }
    $json = (& $executable serve status --json | Out-String)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Tailscale Serve; refusing to reset unknown networking.' }
    $configuration = $json | ConvertFrom-Json
    $ports = @()
    foreach ($port in @('4096', '4097')) {
        $handler = if ($configuration.TCP) { $configuration.TCP.PSObject.Properties[$port] } else { $null }
        if (-not $handler) { continue }
        if (@($handler.Value.PSObject.Properties).Count -ne 1 -or $handler.Value.TCPForward -ne "127.0.0.1:$port") {
            throw "Tailscale port $port has an unrelated handler; refusing to modify it."
        }
        $ports += $port
    }
    return [PSCustomObject]@{Executable=$executable; Json=$json; Ports=$ports}
}

$runValue = (Get-ItemProperty -LiteralPath $runPath -Name $runName -ErrorAction SilentlyContinue).$runName
if ($runValue -and $runValue -notlike "*$installedWorker*" -and $runValue -notlike "*$sourceWorker*") {
    throw 'The Mobile startup value refers to an unrelated command; refusing to remove it.'
}
$workers = @(Get-MobileWorkers)
$desktopProcesses = @(Get-DesktopProcesses)
Write-Output "Desktop running: $([bool]$desktopProcesses)"
Write-Output "Mobile login startup: $([bool]$runValue)"
Write-Output "Mobile workers: $($workers.Count)"

if (-not $Apply -and -not $DisableStartupOnly) {
    . (Join-Path $PSScriptRoot 'retirement-desktop.ps1')
    $desktopPlan = Get-DesktopRetirementPlan -UserData $desktopData -Url $serverUrl
    $owner = Get-ManagedListener
    Assert-SharedServerIdle $owner
    $tailnetPlan = Get-TailnetPlan
    Write-Output "Desktop files requiring a targeted change: $(@($desktopPlan.Files).Count)"
    Write-Output "Open tabs preserved: $($desktopPlan.OpenTabCount)"
    Write-Output "Closed session tabs to return to sidecar: $($desktopPlan.ReboundClosedSessionCount)"
    Write-Output "Mobile Tailnet handlers: $($tailnetPlan.Ports.Count)"
    Write-Output 'Preview only. -DisableStartupOnly leaves every live process/window/input intact.'
    Write-Output '-Apply requires Desktop already closed after preserving and verifying every unsent input.'
    exit 0
}

if ($Apply) {
    if ($desktopProcesses.Count -gt 0) {
        throw 'Desktop is still open. Preserve and verify unsent inputs, finish private tasks, then close it normally. This script never closes Desktop.'
    }
    if (-not $InputBackupDirectory) { throw '-Apply requires -InputBackupDirectory with verified input/database/attachment recovery copies.' }
    $inputVerificationPath = Join-Path $InputBackupDirectory 'input-backup-verification.json'
    $inputVerification = [IO.File]::ReadAllText($inputVerificationPath, [Text.Encoding]::UTF8) | ConvertFrom-Json
    $savedDraftDatabase = Join-Path $InputBackupDirectory 'drafts.sqlite'
    if ($inputVerification.visible_input_verified -ne $true -or
        -not (Test-Path -LiteralPath (Join-Path $InputBackupDirectory 'draft-documents.json')) -or
        (Get-FileHash -LiteralPath $savedDraftDatabase -Algorithm SHA256).Hash -ne $inputVerification.sha256) {
        throw 'Input recovery copies are missing or unverified; Desktop bindings will not change.'
    }
    . (Join-Path $PSScriptRoot 'retirement-desktop.ps1')
    $desktopPlan = Get-DesktopRetirementPlan -UserData $desktopData -Url $serverUrl
    $owner = Get-ManagedListener
    if ($owner -and $owner.ParentProcessId -notin @($workers.ProcessId)) {
        throw 'Managed server is not owned by a verified Mobile worker; refusing to stop it.'
    }
    Assert-SharedServerIdle $owner
    $tailnetPlan = Get-TailnetPlan
}

New-Item -ItemType Directory -Path $stateDirectory -Force | Out-Null
$backupDirectory = Join-Path $stateDirectory ('retirement-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path $backupDirectory | Out-Null
Write-RetirementJson (Join-Path $backupDirectory 'startup.json') ([PSCustomObject]@{Name=$runName; Value=$runValue})
if (Test-Path -LiteralPath $installedWorker) { Copy-Item -LiteralPath $installedWorker -Destination (Join-Path $backupDirectory 'installed-runtime-worker.ps1') }
if (Test-Path -LiteralPath $markerPath) { Copy-Item -LiteralPath $markerPath -Destination (Join-Path $backupDirectory 'previous-retirement.json') }
$previousRetirement = if (Test-Path -LiteralPath $markerPath) {
    [IO.File]::ReadAllText($markerPath, [Text.Encoding]::UTF8) | ConvertFrom-Json
} else { $null }

if ($Apply) {
    $desktopBackup = Join-Path $backupDirectory 'desktop'
    New-Item -ItemType Directory -Path $desktopBackup | Out-Null
    $python = Get-Command python.exe -ErrorAction SilentlyContinue
    if (-not $python) { throw 'Python 3.10+ is required for a consistent final input backup before retiring Desktop bindings.' }
    $finalInputBackup = Join-Path $backupDirectory 'input-final'
    $finalInputOutput = (& $python.Source (Join-Path $PSScriptRoot 'backup-desktop-inputs.py') --output $finalInputBackup | Out-String)
    if ($LASTEXITCODE -ne 0) { throw 'The final input snapshot failed; no Desktop bindings or live processes have been changed.' }
    $finalInputVerification = $finalInputOutput | ConvertFrom-Json
    $finalDraftDocuments = [IO.File]::ReadAllText((Join-Path $finalInputBackup 'draft-documents.json'), [Text.Encoding]::UTF8) | ConvertFrom-Json
    foreach ($closedSessionId in $desktopPlan.ReboundClosedSessionIds) {
        foreach ($document in $finalDraftDocuments) {
            # A rebind changes the lookup namespace. Do not hide any unsent HTTP
            # input/context by silently returning its closed tab to local mode.
            if ($document.key.StartsWith('opencode.workspace.http---', [StringComparison]::Ordinal) -and
                $document.key.EndsWith(":session:${closedSessionId}:prompt", [StringComparison]::Ordinal)) {
                $nonemptyParts = @($document.value.prompt | Where-Object { $_.type -ne 'text' -or -not [string]::IsNullOrEmpty($_.content) })
                if ($nonemptyParts.Count -gt 0 -or @($document.value.context.items).Count -gt 0) {
                    throw 'A closed shared session has unsent input or context. Preserve its lookup namespace before any server rebind.'
                }
            }
        }
    }
    Write-Output "Final input documents preserved: $($finalInputVerification.documents); attachment blobs: $($finalInputVerification.attachment_blobs)"
    # Desktop is closed, so no WAL writer can race this final draft snapshot.
    foreach ($draftFile in @(Get-ChildItem -LiteralPath $desktopData -Filter 'drafts.sqlite*' -File)) {
        $draftHash = (Get-FileHash -LiteralPath $draftFile.FullName -Algorithm SHA256).Hash
        $draftSaved = Join-Path $desktopBackup $draftFile.Name
        Copy-Item -LiteralPath $draftFile.FullName -Destination $draftSaved
        if ((Get-FileHash -LiteralPath $draftSaved -Algorithm SHA256).Hash -ne $draftHash) { throw 'Final draft backup verification failed.' }
    }
    foreach ($file in $desktopPlan.ReadFiles) {
        if ((Get-FileHash -LiteralPath $file.Path -Algorithm SHA256).Hash -ne $file.OriginalHash) { throw 'Desktop state changed during preparation; retry after checking drafts.' }
        $backupFile = Join-Path $desktopBackup ([IO.Path]::GetFileName($file.Path))
        Copy-Item -LiteralPath $file.Path -Destination $backupFile
        if ((Get-FileHash -LiteralPath $backupFile -Algorithm SHA256).Hash -ne $file.OriginalHash) { throw 'Desktop backup verification failed.' }
    }
    if ($tailnetPlan.Json) { [IO.File]::WriteAllText((Join-Path $backupDirectory 'tailscale-serve.json'), $tailnetPlan.Json, $utf8) }
    if (@(Get-DesktopProcesses).Count -gt 0) { throw 'Desktop reopened during preparation; no live bindings have been changed.' }
    Assert-SharedServerIdle (Get-ManagedListener)
}

if (-not ($DisableStartupOnly -and $previousRetirement.Phase -eq 'complete')) {
    Write-RetirementJson $markerPath ([PSCustomObject]@{Retired=$true; Phase='startup-disabled'; At=(Get-Date -Format o); Backup=$backupDirectory})
}
if ($runValue) { Remove-ItemProperty -LiteralPath $runPath -Name $runName }
# This replaces the future entry point only. The existing PowerShell worker has
# already loaded its script; its live server and every Desktop editor stay intact.
if (Test-Path -LiteralPath $installedWorker) { Copy-Item -LiteralPath $sourceWorker -Destination $installedWorker -Force }
if ($DisableStartupOnly) {
    Write-Output "Login startup retired; live Desktop, inputs and server are untouched. Backup: $backupDirectory"
    exit 0
}

if ($workers.Count -gt 0) {
    $latestWorkers = @(Get-MobileWorkers)
    $latestOwner = Get-ManagedListener
    if ($latestOwner -and ($latestOwner.ParentProcessId -notin @($latestWorkers.ProcessId) -or
        -not $owner -or $latestOwner.ProcessId -ne $owner.ProcessId -or $latestOwner.CreationDate -ne $owner.CreationDate)) {
        throw 'Server ownership changed before stop; no stop request was sent.'
    }
    Assert-SharedServerIdle $latestOwner
    [IO.File]::WriteAllText((Join-Path $stateDirectory 'runtime.stop'), (Get-Date -Format o), $utf8)
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if (@(Get-MobileWorkers).Count -eq 0) { break }
        Start-Sleep -Seconds 1
    }
    if (@(Get-MobileWorkers).Count -gt 0) { throw 'Mobile worker did not stop cleanly. Startup remains retired; no broad process kill was used.' }
}
if (Get-ManagedListener) { throw 'Managed server is still listening; refusing to archive its files.' }

foreach ($file in $desktopPlan.ReadFiles) {
    if ((Get-FileHash -LiteralPath $file.Path -Algorithm SHA256).Hash -ne $file.OriginalHash) { throw 'Desktop state changed before apply; its files were not overwritten.' }
}
if (@(Get-DesktopProcesses).Count -gt 0) { throw 'Desktop reopened before apply; its files were not overwritten.' }
try {
    foreach ($file in $desktopPlan.Files) {
        $temporaryFile = $file.Path + '.mobile-retirement-tmp'
        [IO.File]::WriteAllText($temporaryFile, $file.Content, $utf8)
        Move-Item -LiteralPath $temporaryFile -Destination $file.Path -Force
    }
} catch {
    foreach ($file in $desktopPlan.Files) {
        Copy-Item -LiteralPath (Join-Path $desktopBackup ([IO.Path]::GetFileName($file.Path))) -Destination $file.Path -Force
    }
    throw 'Desktop binding write failed; original Desktop files were restored from this retirement backup.'
}

# Remove only the two inspected TCP forwards. Other VPN routes and Serve sites stay.
$freshTailnet = Get-TailnetPlan
foreach ($port in $freshTailnet.Ports) {
    & $freshTailnet.Executable serve "--tcp=$port" off | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not remove Mobile Tailnet handler $port." }
}
if ((Get-TailnetPlan).Ports.Count -gt 0) { throw 'Mobile Tailnet handlers remain.' }

# Preserve offline credentials and managed binaries, never touch OpenCode's DB.
foreach ($name in @('runtime', 'cli', 'server-password.dat', 'bridge-token.dat')) {
    $sourcePath = [IO.Path]::GetFullPath((Join-Path $stateDirectory $name))
    $targetPath = [IO.Path]::GetFullPath((Join-Path $backupDirectory $name))
    if (-not $sourcePath.StartsWith(([IO.Path]::GetFullPath($stateDirectory) + '\'), [StringComparison]::OrdinalIgnoreCase) -or
        -not $targetPath.StartsWith(([IO.Path]::GetFullPath($backupDirectory) + '\'), [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Archive path escaped the Mobile state directory.'
    }
    if (Test-Path -LiteralPath $sourcePath) { Move-Item -LiteralPath $sourcePath -Destination $targetPath }
}
Write-RetirementJson $markerPath ([PSCustomObject]@{Retired=$true; Phase='complete'; At=(Get-Date -Format o); Backup=$backupDirectory; InputBackup=$InputBackupDirectory; FinalInputBackup=$finalInputBackup; PreservedOpenTabs=$desktopPlan.OpenTabCount; ReboundClosedSessions=$desktopPlan.ReboundClosedSessionCount})
Write-Output "Mobile shared runtime and Desktop binding retired. Backup: $backupDirectory"
Write-Output 'Start Desktop normally; verify all unsent inputs before resuming work.'
