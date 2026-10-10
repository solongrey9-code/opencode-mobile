$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'retirement-desktop.ps1')
$fixtureRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('ocandroid-retirement-tests-' + [Guid]::NewGuid().ToString('N'))))
$utf8 = New-Object Text.UTF8Encoding($false)
$passed = 0

function Assert-Retirement($Condition, [string]$Message) {
    if (-not $Condition) { throw "Retirement test failed: $Message" }
}

function Write-FixtureJson([string]$Path, $Value) {
    [IO.File]::WriteAllText($Path, (ConvertTo-Json -InputObject $Value -Depth 100 -Compress), $utf8)
}

function New-RetirementFixture([string]$Name) {
    $directory = Join-Path $fixtureRoot $Name
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $target = 'http://127.0.0.1:4096'
    $other = 'https://example.invalid:8443'
    $settings = [PSCustomObject]@{windowIds=@('window-one'); defaultServerUrl=$target; future=[PSCustomObject]@{keep='settings'}}
    $server = [PSCustomObject]@{
        list=@(
            [PSCustomObject]@{type='http'; displayName='Mobile'; http=[PSCustomObject]@{url=$target; username='fixture'; password='fixture-only'}},
            [PSCustomObject]@{type='http'; future='keep'; http=[PSCustomObject]@{url=$other; password='other-fixture-only'}},
            [PSCustomObject]@{type='ssh'; host='fixture-host'; future='keep-ssh'}
        )
        projects=[PSCustomObject]@{local=@('fixture-project')}
        future=[PSCustomObject]@{keep='server'}
    }
    $global = [PSCustomObject]@{server=(ConvertTo-Json -InputObject $server -Depth 100 -Compress); future='global'; model='{"keep":"verbatim"}'}
    $tabs = @(
        [PSCustomObject]@{type='session'; server='sidecar'; sessionId='ses_fixture_open'; future='keep-open'},
        [PSCustomObject]@{type='draft'; server='sidecar'; draftID='fixture-draft'; directory='C:\fixture'; future='keep-draft'},
        [PSCustomObject]@{type='session'; server=$other; sessionId='ses_fixture_other'}
    )
    $closed = @(
        [PSCustomObject]@{index=2; tab=[PSCustomObject]@{type='session'; server=$target; sessionId='ses_fixture_closed'; future='keep-closed-tab'}; future=[PSCustomObject]@{keep='closed'}},
        [PSCustomObject]@{index=1; tab=[PSCustomObject]@{type='session'; server=$other; sessionId='ses_other_closed'}}
    )
    $window = [PSCustomObject]@{
        tabs=(ConvertTo-Json -InputObject $tabs -Depth 100 -Compress)
        'tabs.closed'=(ConvertTo-Json -InputObject $closed -Depth 100 -Compress)
        'tabs.recent'='{"key":"draft:fixture-draft","future":"verbatim"}'
        'tabs.info'='{"sidecar\n/server/c2lkZWNhcg/session/ses_fixture_open":{"title":"fixture","future":"verbatim"}}'
        future='window'
    }
    Write-FixtureJson (Join-Path $directory 'opencode.settings') $settings
    Write-FixtureJson (Join-Path $directory 'opencode.global.dat') $global
    Write-FixtureJson (Join-Path $directory 'opencode.window.window-one.dat') $window
    return [PSCustomObject]@{Directory=$directory; Settings=$settings; Global=$global; Window=$window; Target=$target; Other=$other}
}

function Assert-PlanThrows([string]$Directory, [string]$Message) {
    $thrown = $false
    try { $null = Get-DesktopRetirementPlan -UserData $Directory -Url 'http://127.0.0.1:4096' }
    catch { $thrown = $true }
    Assert-Retirement $thrown $Message
}

try {
    foreach ($tabCount in @(0,1,4)) {
        $fixture = New-RetirementFixture ('tab-count-' + $tabCount)
        $countTabs = @(for ($index = 0; $index -lt $tabCount; $index++) {
            [PSCustomObject]@{type='session';server='sidecar';sessionId=('ses_count_' + $index)}
        })
        $fixture.Window.tabs = ConvertTo-Json -InputObject $countTabs -Depth 10 -Compress
        $fixture.Window.'tabs.closed' = '[]'
        Write-FixtureJson (Join-Path $fixture.Directory 'opencode.window.window-one.dat') $fixture.Window
        $countPlan = Get-DesktopRetirementPlan -UserData $fixture.Directory -Url $fixture.Target
        Assert-Retirement ($countPlan.OpenTabCount -eq $tabCount) 'zero/one/multiple JSON tabs have the same shape across PowerShell versions'
        $passed++
    }

    $fixture = New-RetirementFixture 'preservation'
    $before = @(Get-ChildItem -LiteralPath $fixture.Directory -File | ForEach-Object {Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256})
    $plan = Get-DesktopRetirementPlan -UserData $fixture.Directory -Url ($fixture.Target + '/')
    Assert-Retirement ($plan.Files.Count -eq 3 -and $plan.ReadFiles.Count -eq 3) 'plan includes only affected files and all read hashes'
    Assert-Retirement ($plan.OpenTabCount -eq 3 -and $plan.ReboundClosedSessionCount -eq 1) 'counts include unsent draft'
    Assert-Retirement ($plan.ReboundClosedSessionIds.Count -eq 1 -and $plan.ReboundClosedSessionIds[0] -eq 'ses_fixture_closed') 'closed session IDs are exposed for input namespace safety checks'
    $nextWindow = ($plan.Files | Where-Object {$_.Path.EndsWith('opencode.window.window-one.dat')}).Content | ConvertFrom-Json
    Assert-Retirement ($nextWindow.tabs -ceq $fixture.Window.tabs) 'current session and draft tabs remain byte-for-byte stored JSON'
    Assert-Retirement ($nextWindow.'tabs.recent' -ceq $fixture.Window.'tabs.recent') 'recent key remains verbatim'
    Assert-Retirement ($nextWindow.'tabs.info' -ceq $fixture.Window.'tabs.info') 'info keys remain verbatim'
    $nextClosed = @(Read-DesktopRetirementArray $nextWindow.'tabs.closed' 'fixture.closed')
    Assert-Retirement ($nextClosed.Count -eq 2 -and $nextClosed[0].tab.server -eq 'sidecar') 'closed shared session returns to sidecar without dropping history'
    Assert-Retirement ($nextClosed[0].index -eq 2 -and $nextClosed[0].tab.sessionId -eq 'ses_fixture_closed') 'closed order, index and session ID remain intact'
    Assert-Retirement ($nextClosed[0].future.keep -eq 'closed' -and $nextClosed[0].tab.future -eq 'keep-closed-tab') 'unknown closed-entry fields remain intact'
    Assert-Retirement ($nextClosed[1].tab.server -eq $fixture.Other -and $nextWindow.future -eq 'window') 'unrelated closed tabs and window fields remain intact'
    $nextGlobal = ($plan.Files | Where-Object {$_.Path.EndsWith('opencode.global.dat')}).Content | ConvertFrom-Json
    $nextServer = $nextGlobal.server | ConvertFrom-Json
    Assert-Retirement ($nextServer.list.Count -eq 2 -and $nextServer.list[0].http.url -eq $fixture.Other -and $nextServer.list[1].type -eq 'ssh') 'unrelated HTTP and SSH entries remain intact'
    Assert-Retirement ($nextServer.list[0].http.password -eq 'other-fixture-only' -and $nextServer.future.keep -eq 'server') 'unrelated credentials and unknown server fields remain intact'
    Assert-Retirement ($nextGlobal.future -eq 'global' -and $nextGlobal.model -ceq $fixture.Global.model) 'unknown global and untouched nested JSON remain intact'
    $nextSettings = ($plan.Files | Where-Object {$_.Path.EndsWith('opencode.settings')}).Content | ConvertFrom-Json
    Assert-Retirement (-not $nextSettings.PSObject.Properties['defaultServerUrl']) 'target default binding is removed'
    Assert-Retirement ($nextSettings.windowIds.Count -eq 1 -and $nextSettings.windowIds[0] -eq 'window-one' -and $nextSettings.future.keep -eq 'settings') 'window identity and unknown settings remain intact'
    foreach ($original in $before) {
        Assert-Retirement ((Get-FileHash -LiteralPath $original.Path -Algorithm SHA256).Hash -eq $original.Hash) 'planning never writes input files'
        Assert-Retirement (($plan.ReadFiles | Where-Object Path -eq $original.Path).OriginalHash -eq $original.Hash) 'original hashes match exact file bytes'
    }
    $passed++

    $fixture = New-RetirementFixture 'unrelated-default'
    $fixture.Settings.defaultServerUrl = $fixture.Other
    Write-FixtureJson (Join-Path $fixture.Directory 'opencode.settings') $fixture.Settings
    $plan = Get-DesktopRetirementPlan -UserData $fixture.Directory -Url $fixture.Target
    Assert-Retirement (-not $plan.DefaultBindingRemoved -and @($plan.Files | Where-Object {$_.Path.EndsWith('opencode.settings')}).Count -eq 0) 'unrelated default is not rewritten'
    $passed++

    foreach ($type in @('session','draft')) {
        $fixture = New-RetirementFixture ('shared-open-' + $type)
        $fixture.Window.tabs = ConvertTo-Json -InputObject @([PSCustomObject]@{type=$type;server=$fixture.Target;sessionId='ses_shared';draftID='keep-input'}) -Compress
        Write-FixtureJson (Join-Path $fixture.Directory 'opencode.window.window-one.dat') $fixture.Window
        Assert-PlanThrows $fixture.Directory "shared open $type must block planning"
        $passed++
    }

    $fixture = New-RetirementFixture 'shared-closed-draft'
    $fixture.Window.'tabs.closed' = ConvertTo-Json -InputObject @([PSCustomObject]@{index=0;tab=[PSCustomObject]@{type='draft';server=$fixture.Target;draftID='keep-input'}}) -Depth 10 -Compress
    Write-FixtureJson (Join-Path $fixture.Directory 'opencode.window.window-one.dat') $fixture.Window
    Assert-PlanThrows $fixture.Directory 'shared closed drafts must not be migrated blindly'
    $passed++

    foreach ($badShape in @('not-json','{}','null','[1]')) {
        $fixture = New-RetirementFixture ('bad-closed-' + [Guid]::NewGuid().ToString('N'))
        $fixture.Window.'tabs.closed' = $badShape
        Write-FixtureJson (Join-Path $fixture.Directory 'opencode.window.window-one.dat') $fixture.Window
        Assert-PlanThrows $fixture.Directory 'invalid or unsupported closed JSON must fail closed'
        $passed++
    }
    $fixture = New-RetirementFixture 'bad-settings'
    [IO.File]::WriteAllText((Join-Path $fixture.Directory 'opencode.settings'), '{broken', $utf8)
    Assert-PlanThrows $fixture.Directory 'bad outer JSON must fail closed'
    $passed++

    $fixture = New-RetirementFixture 'legacy-entries'
    $server = $fixture.Global.server | ConvertFrom-Json
    $server.list = @($fixture.Target, [PSCustomObject]@{url=$fixture.Target;password='fixture-only'}, $fixture.Other)
    $fixture.Global.server = ConvertTo-Json -InputObject $server -Depth 100 -Compress
    Write-FixtureJson (Join-Path $fixture.Directory 'opencode.global.dat') $fixture.Global
    $plan = Get-DesktopRetirementPlan -UserData $fixture.Directory -Url $fixture.Target
    $nextServer = (($plan.Files | Where-Object {$_.Path.EndsWith('opencode.global.dat')}).Content | ConvertFrom-Json).server | ConvertFrom-Json
    Assert-Retirement ($plan.RemovedServerEntryCount -eq 2 -and $nextServer.list.Count -eq 1 -and $nextServer.list[0] -eq $fixture.Other) 'legacy entries remain endpoint-specific'
    $passed++

    # A second plan over the first plan's result is a no-op.
    $fixture = New-RetirementFixture 'idempotent'
    $plan = Get-DesktopRetirementPlan -UserData $fixture.Directory -Url $fixture.Target
    foreach ($file in $plan.Files) { [IO.File]::WriteAllText($file.Path, $file.Content, $utf8) }
    $again = Get-DesktopRetirementPlan -UserData $fixture.Directory -Url $fixture.Target
    Assert-Retirement ($again.Files.Count -eq 0 -and $again.ReboundClosedSessionCount -eq 0) 'retirement planning is idempotent'
    $passed++

    Write-Output "Desktop retirement fixture tests passed: $passed"
} finally {
    # Only this uniquely-created fixture directory may be recursively removed.
    $resolvedFixture = [IO.Path]::GetFullPath($fixtureRoot)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedFixture.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
        -not ([IO.Path]::GetFileName($resolvedFixture)).StartsWith('ocandroid-retirement-tests-')) {
        throw 'Fixture cleanup escaped the designated temporary directory.'
    }
    if (Test-Path -LiteralPath $resolvedFixture) { Remove-Item -LiteralPath $resolvedFixture -Recurse -Force }
}
