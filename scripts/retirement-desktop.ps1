# Pure planning helpers. Dot-source this file; it never writes Desktop state.

function Normalize-DesktopRetirementUrl([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    $uri = $null
    if (-not [Uri]::TryCreate($Value.Trim(), [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -notin @('http', 'https') -or $uri.UserInfo -or $uri.Query -or $uri.Fragment) {
        return $null
    }
    return $uri.AbsoluteUri.TrimEnd('/')
}

function Get-DesktopRetirementHash([byte[]]$Bytes) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '') }
    finally { $sha.Dispose() }
}

function Read-DesktopRetirementObject([string]$Path) {
    try {
        $bytes = [IO.File]::ReadAllBytes($Path)
        $strictUtf8 = New-Object Text.UTF8Encoding($false, $true)
        $text = $strictUtf8.GetString($bytes).TrimStart([char]0xFEFF)
        if (-not $text.TrimStart().StartsWith('{')) { throw 'Not an object.' }
        $value = ConvertFrom-Json -InputObject $text -ErrorAction Stop
        if (-not ($value -is [PSCustomObject])) { throw 'Not an object.' }
    } catch { throw "Unsupported or invalid Desktop JSON: $Path" }
    return [PSCustomObject]@{ Path=$Path; Value=$value; OriginalHash=(Get-DesktopRetirementHash $bytes) }
}

function Read-DesktopRetirementArray($Value, [string]$Label) {
    if (-not ($Value -is [string]) -or -not $Value.TrimStart().StartsWith('[')) {
        throw "Unsupported Desktop array storage: $Label"
    }
    try {
        # Windows PowerShell 5 emits a JSON array as one pipeline object.
        # Enumerate it explicitly so callers' @() has the same 0/1/many shape
        # in Windows PowerShell 5 and PowerShell 7.
        $items = ConvertFrom-Json -InputObject $Value -ErrorAction Stop
        foreach ($item in $items) { $item }
    }
    catch { throw "Invalid Desktop array JSON: $Label" }
}

function New-DesktopRetirementFile($Source) {
    return [PSCustomObject]@{
        Path = $Source.Path
        Content = ConvertTo-Json -InputObject $Source.Value -Depth 100
        OriginalHash = $Source.OriginalHash
    }
}

function Get-DesktopRetirementPlan {
    param(
        [Parameter(Mandatory=$true)][string]$UserData,
        [Parameter(Mandatory=$true)][string]$Url
    )

    $targetUrl = Normalize-DesktopRetirementUrl $Url
    if (-not $targetUrl) { throw 'Retirement URL must be an absolute HTTP(S) URL without credentials, query or fragment.' }
    $directory = [IO.Path]::GetFullPath($UserData).TrimEnd('\', '/')
    $settingsSource = Read-DesktopRetirementObject (Join-Path $directory 'opencode.settings')
    $globalSource = Read-DesktopRetirementObject (Join-Path $directory 'opencode.global.dat')
    $settings = $settingsSource.Value
    $global = $globalSource.Value
    if (-not ($settings.windowIds -is [Array])) { throw 'Unsupported Desktop windowIds storage.' }
    if (-not ($global.server -is [string]) -or -not $global.server.TrimStart().StartsWith('{')) {
        throw 'Unsupported Desktop server storage.'
    }
    try { $server = ConvertFrom-Json -InputObject $global.server -ErrorAction Stop }
    catch { throw 'Invalid Desktop server JSON.' }
    if (-not ($server -is [PSCustomObject]) -or -not ($server.list -is [Array])) {
        throw 'Unsupported Desktop server list storage.'
    }

    $plannedFiles = New-Object 'Collections.Generic.List[object]'
    $readFiles = New-Object 'Collections.Generic.List[object]'
    $readFiles.Add([PSCustomObject]@{Path=$settingsSource.Path; OriginalHash=$settingsSource.OriginalHash})
    $readFiles.Add([PSCustomObject]@{Path=$globalSource.Path; OriginalHash=$globalSource.OriginalHash})
    $seenWindows = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    $openTabCount = 0
    $reboundClosedSessionCount = 0
    $reboundClosedSessionIds = New-Object 'Collections.Generic.List[string]'

    foreach ($windowId in $settings.windowIds) {
        if (-not ($windowId -is [string]) -or [string]::IsNullOrWhiteSpace($windowId) -or
            $windowId.IndexOfAny([IO.Path]::GetInvalidFileNameChars()) -ge 0 -or
            -not $seenWindows.Add($windowId)) {
            throw 'Unsupported or duplicate Desktop window identity.'
        }
        $windowSource = Read-DesktopRetirementObject (Join-Path $directory "opencode.window.$windowId.dat")
        $readFiles.Add([PSCustomObject]@{Path=$windowSource.Path; OriginalHash=$windowSource.OriginalHash})
        $window = $windowSource.Value
        $tabs = @(Read-DesktopRetirementArray $window.tabs 'tabs')
        foreach ($tab in $tabs) {
            if (-not ($tab -is [PSCustomObject]) -or -not ($tab.server -is [string]) -or
                -not ($tab.type -is [string])) { throw 'Unsupported Desktop open tab identity.' }
            if ((Normalize-DesktopRetirementUrl $tab.server) -ceq $targetUrl) {
                throw 'A shared-server tab or draft is still open. No Desktop state was changed.'
            }
        }
        $openTabCount += $tabs.Count

        $closed = @(Read-DesktopRetirementArray $window.'tabs.closed' 'tabs.closed')
        $windowChanged = $false
        foreach ($entry in $closed) {
            if (-not ($entry -is [PSCustomObject]) -or -not ($entry.tab -is [PSCustomObject]) -or
                -not ($entry.tab.server -is [string]) -or -not ($entry.tab.type -is [string]) -or
                -not ($entry.index -is [int] -or $entry.index -is [long])) {
                throw 'Unsupported Desktop closed-tab entry.'
            }
            if ((Normalize-DesktopRetirementUrl $entry.tab.server) -cne $targetUrl) { continue }
            if ($entry.tab.type -ne 'session' -or -not ($entry.tab.sessionId -is [string]) -or
                [string]::IsNullOrWhiteSpace($entry.tab.sessionId)) {
                throw 'Only closed shared session tabs can return to sidecar; shared drafts require separate input recovery.'
            }
            # Keep session ID, index, order and every unknown field. Current tabs,
            # draft IDs, recent/info strings and window IDs never change.
            $entry.tab.server = 'sidecar'
            $reboundClosedSessionCount++
            $reboundClosedSessionIds.Add($entry.tab.sessionId)
            $windowChanged = $true
        }
        if ($windowChanged) {
            $window.'tabs.closed' = ConvertTo-Json -InputObject @($closed) -Depth 100 -Compress
            $plannedFiles.Add((New-DesktopRetirementFile $windowSource))
        }
    }

    $remainingEntries = New-Object 'Collections.Generic.List[object]'
    $removedServerEntryCount = 0
    foreach ($entry in $server.list) {
        $entryUrl = $null
        if ($entry -is [string]) {
            $entryUrl = Normalize-DesktopRetirementUrl $entry
        } elseif ($entry -is [PSCustomObject]) {
            if ($entry.PSObject.Properties['http']) {
                if (-not ($entry.http -is [PSCustomObject]) -or -not ($entry.http.url -is [string])) {
                    throw 'Unsupported Desktop HTTP server entry.'
                }
                $entryUrl = Normalize-DesktopRetirementUrl $entry.http.url
                if ($entryUrl -ceq $targetUrl -and $entry.type -ne 'http') {
                    throw 'Unsupported target Desktop server type.'
                }
            } elseif ($entry.PSObject.Properties['url'] -and -not $entry.PSObject.Properties['type']) {
                if (-not ($entry.url -is [string])) { throw 'Unsupported legacy Desktop server entry.' }
                $entryUrl = Normalize-DesktopRetirementUrl $entry.url
            }
        } else { throw 'Unsupported Desktop server entry.' }
        if ($entryUrl -ceq $targetUrl) { $removedServerEntryCount++ }
        else { $remainingEntries.Add($entry) }
    }
    if ($removedServerEntryCount -gt 0) {
        $server.list = @($remainingEntries.ToArray())
        $global.server = ConvertTo-Json -InputObject $server -Depth 100 -Compress
        $plannedFiles.Add((New-DesktopRetirementFile $globalSource))
    }

    $defaultBindingRemoved = $false
    if ((Normalize-DesktopRetirementUrl $settings.defaultServerUrl) -ceq $targetUrl) {
        $settings.PSObject.Properties.Remove('defaultServerUrl')
        $defaultBindingRemoved = $true
        $plannedFiles.Add((New-DesktopRetirementFile $settingsSource))
    }

    return [PSCustomObject]@{
        UserData=$directory
        Url=$targetUrl
        Files=@($plannedFiles.ToArray())
        ReadFiles=@($readFiles.ToArray())
        DefaultBindingRemoved=$defaultBindingRemoved
        RemovedServerEntryCount=$removedServerEntryCount
        SharedOpenTabCount=0
        ReboundClosedSessionCount=$reboundClosedSessionCount
        ReboundClosedSessionIds=@($reboundClosedSessionIds.ToArray())
        WindowCount=$settings.windowIds.Count
        OpenTabCount=$openTabCount
    }
}
