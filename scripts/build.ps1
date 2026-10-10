param(
    [switch]$Release
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$tempTools = Join-Path $env:TEMP "opencode"

$scriptFailed = $false
Get-ChildItem -LiteralPath $PSScriptRoot -Filter "*.ps1" | ForEach-Object {
    $tokens = $null
    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile(
        $_.FullName,
        [ref]$tokens,
        [ref]$errors
    ) | Out-Null
    if ($errors.Count) {
        $errors | ForEach-Object {
            Write-Error "$($_.Extent.File):$($_.Extent.StartLineNumber) $($_.Message)"
        }
        $scriptFailed = $true
    }
}
if ($scriptFailed) { throw "PowerShell script syntax checks failed." }

$nodeCandidates = @(
    "C:\Program Files\nodejs\node.exe",
    (Get-Command node -ErrorAction SilentlyContinue).Source
)
$node = $nodeCandidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
if (-not $node) { throw "Node.js was not found. It is required to validate the Desktop bridge." }
foreach ($script in @("desktop-bridge.mjs", "check-docs.mjs")) {
    & $node --check (Join-Path $PSScriptRoot $script)
    if ($LASTEXITCODE -ne 0) { throw "$script syntax check failed." }
}
& $node (Join-Path $PSScriptRoot "check-docs.mjs")
if ($LASTEXITCODE -ne 0) { throw "Documentation checks failed." }

if (-not $env:JAVA_HOME) {
    $portableJava = Get-ChildItem -Path (Join-Path $tempTools "jdk17") -Filter java.exe -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -like "*\bin\java.exe" } |
        Select-Object -First 1
    if ($portableJava) {
        $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $portableJava.FullName)
    }
}
if (-not $env:JAVA_HOME) {
    $systemJava = @(
        @(Get-ChildItem -Path "C:\Program Files\Eclipse Adoptium" -Filter java.exe -Recurse -ErrorAction SilentlyContinue)
        @(Get-ChildItem -Path "C:\Program Files\Microsoft" -Filter java.exe -Recurse -ErrorAction SilentlyContinue)
    ) | Where-Object { $_.FullName -like "*\bin\java.exe" } | Select-Object -First 1
    if ($systemJava) {
        $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $systemJava.FullName)
    }
}
if (-not $env:JAVA_HOME) {
    $registryJavaHome = (Get-ItemProperty -LiteralPath "HKLM:\SOFTWARE\JavaSoft\JDK" -ErrorAction SilentlyContinue).JavaHome
    if ($registryJavaHome -and (Test-Path -LiteralPath (Join-Path $registryJavaHome "bin\java.exe"))) {
        $env:JAVA_HOME = $registryJavaHome
    }
}
if (-not $env:JAVA_HOME) {
    throw "JDK 17 was not found. Set JAVA_HOME or install Android Studio."
}

if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    $portableSdk = Join-Path $tempTools "android-sdk"
    if (Test-Path -LiteralPath (Join-Path $portableSdk "platforms\android-35")) {
        $env:ANDROID_HOME = $portableSdk
        $env:ANDROID_SDK_ROOT = $portableSdk
    }
}

$tasks = if ($Release) {
    @("testDebugUnitTest", "lintDebug", "assembleRelease")
} else {
    @("testDebugUnitTest", "lintDebug", "assembleDebug")
}

$portableGradle = Get-ChildItem -Path (Join-Path $tempTools "gradle-8.11.1") -Filter gradle.bat -Recurse -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($portableGradle) {
    & $portableGradle.FullName --no-daemon @tasks
} else {
    & (Join-Path $root "gradlew.bat") --no-daemon @tasks
}
$gradleExitCode = $LASTEXITCODE
if ($gradleExitCode -ne 0) {
    throw "Gradle quality gate failed with exit code $gradleExitCode."
}
