$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path -LiteralPath $apk)) { throw "Debug APK not found. Run .\scripts\build.cmd first." }

if (-not $env:JAVA_HOME) {
    $portableJava = Get-ChildItem -LiteralPath "$env:TEMP\opencode\jdk17" -Filter "java.exe" -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -like "*\bin\java.exe" } |
        Select-Object -First 1
    if ($portableJava) {
        $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $portableJava.FullName)
    }
}
if (-not $env:JAVA_HOME) { throw "JDK 17 was not found. Set JAVA_HOME or install Android Studio." }

$sdkRoots = @(
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT,
    "$env:LOCALAPPDATA\Android\Sdk",
    "$env:TEMP\opencode\android-sdk"
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
$signers = @(foreach ($sdk in $sdkRoots) {
    Get-ChildItem -LiteralPath (Join-Path $sdk "build-tools") -Filter "apksigner.bat" -Recurse -ErrorAction SilentlyContinue
})
$apksigner = $signers | Sort-Object FullName -Descending | Select-Object -First 1
if (-not $apksigner) { throw "apksigner was not found in the Android SDK." }

& $apksigner.FullName verify --verbose --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw "Debug APK signature verification failed." }
