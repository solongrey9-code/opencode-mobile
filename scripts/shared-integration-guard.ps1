function Assert-SharedIntegrationAvailable {
    $repositoryMarker = Join-Path $PSScriptRoot "shared-integration.retired"
    $hostMarker = if ($env:LOCALAPPDATA) {
        Join-Path $env:LOCALAPPDATA "OpenCodeMobile\integration-retired.json"
    } else {
        $null
    }

    if ((Test-Path -LiteralPath $repositoryMarker) -or
        ($hostMarker -and (Test-Path -LiteralPath $hostMarker))) {
        throw "The Android/Desktop shared integration is retired. This command is disabled. Use OpenCode Desktop's local server; see docs/RETIREMENT.md."
    }
}
