param(
    [string]$Url = "http://127.0.0.1:4096",
    [string]$Username = "opencode",
    [string]$Password,
    [string]$UserData,
    [switch]$ConfirmHome,
    [switch]$SkipTabMigration
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "shared-integration-guard.ps1")
Assert-SharedIntegrationAvailable

function Convert-SecureStringToPlainText([Security.SecureString]$Secure) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

function Read-Json([string]$Path) {
    return [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8) | ConvertFrom-Json
}

function Write-Json([string]$Path, $Value) {
    $json = $Value | ConvertTo-Json -Depth 100 -Compress
    [IO.File]::WriteAllText($Path, $json, (New-Object Text.UTF8Encoding($false)))
}

function Normalize-Url([string]$Value) { return $Value.Trim().TrimEnd("/") }

$desktopPath = Join-Path $env:LOCALAPPDATA "Programs\@opencode-aidesktop\OpenCode.exe"
if (-not (Test-Path -LiteralPath $desktopPath)) {
    throw "OpenCode Desktop was not found at $desktopPath."
}
$desktopVersion = ([Version](Get-Item -LiteralPath $desktopPath).VersionInfo.ProductVersion).ToString(3)
if ($desktopVersion -notin @("1.18.25", "1.18.26", "1.18.27")) {
    throw "Desktop migration supports OpenCode Desktop 1.18.25, 1.18.26 and 1.18.27 only; found $desktopVersion."
}
$desktopRunning = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
    $_.ExecutablePath -and $_.ExecutablePath.Equals($desktopPath, [StringComparison]::OrdinalIgnoreCase)
}
$defaultUserData = Join-Path $env:APPDATA "ai.opencode.desktop"
$userData = if ($UserData) { $UserData } else { $defaultUserData }
$targetsLiveDesktop = [IO.Path]::GetFullPath($userData).TrimEnd('\').Equals(
    [IO.Path]::GetFullPath($defaultUserData).TrimEnd('\'),
    [StringComparison]::OrdinalIgnoreCase
)
if ($targetsLiveDesktop -and $desktopRunning) {
    throw "Open Desktop Home first, then close OpenCode Desktop. Finish or stop the current task before switching servers."
}
if ($targetsLiveDesktop -and -not $ConfirmHome) {
    throw "Return Desktop to Home, close it, then rerun with -ConfirmHome."
}

if (-not $Password) {
    $Password = Convert-SecureStringToPlainText (Read-Host "Shared OpenCode password" -AsSecureString)
}
if ([string]::IsNullOrWhiteSpace($Password)) { throw "A non-empty password is required." }

$Url = Normalize-Url $Url
$auth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${Username}:$Password"))
try {
    $health = Invoke-RestMethod -Uri "$Url/global/health" -Headers @{ Authorization = "Basic $auth" } -TimeoutSec 10
    if (-not $health.healthy) { throw "The shared server is not healthy." }
} catch {
    throw "Cannot authenticate to $Url. $($_.Exception.Message)"
}

$settingsPath = Join-Path $userData "opencode.settings"
$globalPath = Join-Path $userData "opencode.global.dat"
if (-not (Test-Path -LiteralPath $settingsPath) -or -not (Test-Path -LiteralPath $globalPath)) {
    throw "OpenCode Desktop storage was not found at $userData."
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backup = Join-Path $userData "mobile-shared-backup-$stamp"
New-Item -ItemType Directory -Path $backup | Out-Null
Copy-Item -LiteralPath $settingsPath -Destination $backup
Copy-Item -LiteralPath $globalPath -Destination $backup

$settings = Read-Json $settingsPath
$settings | Add-Member -NotePropertyName "defaultServerUrl" -NotePropertyValue $Url -Force

$global = Read-Json $globalPath
$server = if ($global.server -is [string]) {
    $global.server | ConvertFrom-Json
} else {
    [PSCustomObject]@{ list = @(); projects = [PSCustomObject]@{}; lastProject = [PSCustomObject]@{}; recentlyClosed = [PSCustomObject]@{} }
}
$list = @($server.list | Where-Object {
    if ($_ -is [string]) { return (Normalize-Url $_) -ne $Url }
    return -not $_.http -or (Normalize-Url $_.http.url) -ne $Url
})
$list += [PSCustomObject]@{
    type = "http"
    displayName = "OpenCode Mobile Shared"
    http = [PSCustomObject]@{ url = $Url; username = $Username; password = $Password }
}
$server.list = $list
$global.server = $server | ConvertTo-Json -Depth 100 -Compress

$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Url)).TrimEnd("=").Replace("+", "-").Replace("/", "_")
$oldPrefix = "sidecar`n/server/c2lkZWNhcg/session/"
$newPrefix = "$Url`n/server/$encoded/session/"

if (-not $SkipTabMigration) {
    $newWindowIds = @()
    foreach ($windowID in @($settings.windowIds)) {
        $windowPath = Join-Path $userData "opencode.window.$windowID.dat"
        if (-not (Test-Path -LiteralPath $windowPath)) { continue }
        Copy-Item -LiteralPath $windowPath -Destination $backup
        $window = Read-Json $windowPath
        $parsedTabs = ConvertFrom-Json -InputObject $window.tabs
        $tabs = @(foreach ($tab in $parsedTabs) {
            if ($tab.type -eq "session" -and $tab.server -eq "sidecar") {
                [PSCustomObject]@{ type = "session"; server = $Url; sessionId = $tab.sessionId }
            } else {
                $tab
            }
        })
        $window.tabs = ConvertTo-Json -InputObject @($tabs) -Depth 20 -Compress

        if ($window."tabs.recent") {
            $recent = $window."tabs.recent" | ConvertFrom-Json
            if ($recent.key -is [string] -and $recent.key.StartsWith($oldPrefix)) {
                $recent.key = $newPrefix + $recent.key.Substring($oldPrefix.Length)
                $window."tabs.recent" = $recent | ConvertTo-Json -Compress
            }
        }

        if ($window."tabs.info") {
            $info = $window."tabs.info" | ConvertFrom-Json
            $nextInfo = [ordered]@{}
            foreach ($property in $info.PSObject.Properties) {
                $key = if ($property.Name.StartsWith($oldPrefix)) {
                    $newPrefix + $property.Name.Substring($oldPrefix.Length)
                } else { $property.Name }
                $nextInfo[$key] = $property.Value
            }
            $window."tabs.info" = $nextInfo | ConvertTo-Json -Depth 20 -Compress
        }
        $newWindowID = [Guid]::NewGuid().ToString()
        $newWindowPath = Join-Path $userData "opencode.window.$newWindowID.dat"
        Write-Json $newWindowPath $window
        $newWindowIds += $newWindowID
    }
    $settings.windowIds = $newWindowIds
}

Write-Json $globalPath $global
Write-Json $settingsPath $settings
Write-Host "Desktop now defaults to $Url"
Write-Host "Backup: $backup"
Write-Host "Open tabs migrated: $(-not $SkipTabMigration)"

Write-Host "Start Desktop manually. New window IDs force startup on Home before restoring migrated tabs."

$Password = $null
