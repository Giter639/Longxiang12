$ErrorActionPreference = "Stop"

$repoRoot = Split-Path $PSScriptRoot -Parent | Split-Path -Parent
$projectRoot = Join-Path $repoRoot "FocusDu"
$localProperties = Join-Path $projectRoot "local.properties"
$gradlew = Join-Path $projectRoot "gradlew.bat"
$apk = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
$avdName = "PixelTest"
$packageName = "com.focusdu.app"
$activity = "$packageName/.MainActivity"

function Resolve-SdkRoot {
    if (Test-Path -LiteralPath $localProperties) {
        $line = Get-Content -LiteralPath $localProperties -Encoding UTF8 |
            Where-Object { $_ -match "^sdk\.dir=" } |
            Select-Object -First 1
        if ($line) {
            $value = $line.Substring("sdk.dir=".Length)
            $value = $value.Replace("\:", ":").Replace("\\", "\")
            if (Test-Path -LiteralPath $value) { return $value }
        }
    }
    if ($env:ANDROID_SDK_ROOT -and (Test-Path -LiteralPath $env:ANDROID_SDK_ROOT)) {
        return $env:ANDROID_SDK_ROOT
    }
    if ($env:ANDROID_HOME -and (Test-Path -LiteralPath $env:ANDROID_HOME)) {
        return $env:ANDROID_HOME
    }
    $default = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path -LiteralPath $default) { return $default }
    throw "Android SDK not found. Set sdk.dir in FocusDu/local.properties."
}

function Resolve-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return $env:JAVA_HOME
    }
    $toolchain = Join-Path (Split-Path $repoRoot -Parent) ".toolchain\jdk-17"
    if (Test-Path -LiteralPath $toolchain) {
        $jdk = Get-ChildItem -LiteralPath $toolchain -Directory |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "bin\java.exe") } |
            Select-Object -First 1
        if ($jdk) { return $jdk.FullName }
    }
    throw "JDK 17 not found. Set JAVA_HOME or install JDK 17."
}

function Get-ConnectedDevice([string]$adb) {
    $lines = & $adb devices
    $devices = @()
    foreach ($line in $lines) {
        if ($line -match '^(\S+)\s+device(?:\s|$)') {
            $devices += $Matches[1]
        }
    }
    $emulator = $devices | Where-Object { $_ -like "emulator-*" } | Select-Object -First 1
    if ($emulator) { return $emulator }
    return $devices | Select-Object -First 1
}

$sdkRoot = Resolve-SdkRoot
$javaHome = Resolve-JavaHome
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
$emulator = Join-Path $sdkRoot "emulator\emulator.exe"
if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found: $adb" }
if (-not (Test-Path -LiteralPath $gradlew)) { throw "Gradle wrapper not found: $gradlew" }

$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:Path = "$javaHome\bin;$sdkRoot\platform-tools;$sdkRoot\emulator;$env:Path"

Write-Host "[1/4] Building debug APK..."
Push-Location $projectRoot
try {
    & $gradlew ":app:assembleDebug"
    $buildExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($buildExitCode -ne 0 -or -not (Test-Path -LiteralPath $apk)) {
    throw "Debug APK build failed."
}

$serial = Get-ConnectedDevice $adb
if (-not $serial) {
    if (-not (Test-Path -LiteralPath $emulator)) {
        throw "No device connected and Android Emulator is not installed."
    }
    $avds = & $emulator -list-avds
    if ($avds -notcontains $avdName) {
        throw "AVD '$avdName' does not exist."
    }
    Write-Host "[2/4] Starting emulator $avdName..."
    Start-Process -FilePath $emulator -ArgumentList @(
        "-avd", $avdName, "-no-snapshot-load", "-no-boot-anim", "-gpu", "auto"
    ) | Out-Null

    $deadline = (Get-Date).AddMinutes(3)
    do {
        Start-Sleep -Seconds 2
        $serial = Get-ConnectedDevice $adb
        if ($serial -and $serial -like "emulator-*") {
            $boot = (& $adb -s $serial shell getprop sys.boot_completed 2>$null).Trim()
            if ($boot -eq "1") { break }
        }
    } while ((Get-Date) -lt $deadline)
    if (-not $serial) { throw "Emulator did not connect within 3 minutes." }
}
Write-Host "[2/4] Device: $serial"

& $adb -s $serial shell input keyevent KEYCODE_WAKEUP | Out-Null
& $adb -s $serial shell input keyevent 82 | Out-Null

Write-Host "[3/4] Installing latest APK..."
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw "APK installation failed." }

Write-Host "[4/4] Starting FocusDu..."
& $adb -s $serial shell am start -W -n $activity
if ($LASTEXITCODE -ne 0) { throw "App launch failed." }

Write-Host "FocusDu is ready."
