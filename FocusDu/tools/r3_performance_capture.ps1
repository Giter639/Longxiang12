param(
    [int]$DurationMinutes = 30,
    [string]$PackageName = "com.focusdu.app",
    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"
$adb = Get-Command adb.exe -ErrorAction SilentlyContinue
if (-not $adb) {
    throw "adb.exe not found. Add Android SDK platform-tools to PATH first."
}

& $adb.Source devices | Out-Host
if (-not (& $adb.Source get-state 2>$null)) {
    throw "No connected Android device."
}

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutputDirectory = Join-Path $PSScriptRoot "..\Evidence\r3-performance-$stamp"
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

& $adb.Source shell dumpsys battery | Set-Content -LiteralPath (Join-Path $OutputDirectory "battery-before.txt") -Encoding UTF8
& $adb.Source shell dumpsys meminfo $PackageName | Set-Content -LiteralPath (Join-Path $OutputDirectory "meminfo-before.txt") -Encoding UTF8
& $adb.Source shell getprop | Set-Content -LiteralPath (Join-Path $OutputDirectory "device-properties.txt") -Encoding UTF8
& $adb.Source logcat -c
& $adb.Source shell am start -n "$PackageName/.MainActivity" | Out-Host

Write-Host "Open P03 and start calibration now. Keep the camera preview running for the full test."
Write-Host "Capture duration: $DurationMinutes minutes"

$deadline = (Get-Date).AddMinutes($DurationMinutes)
$samples = Join-Path $OutputDirectory "battery-samples.csv"
"timestamp,level,temperatureC,voltage" | Set-Content -LiteralPath $samples -Encoding UTF8

while ((Get-Date) -lt $deadline) {
    $battery = & $adb.Source shell dumpsys battery
    $level = (($battery | Select-String "level:").Line -replace "[^0-9]", "")
    $temperature = (($battery | Select-String "temperature:").Line -replace "[^0-9-]", "")
    $voltage = (($battery | Select-String "voltage:").Line -replace "[^0-9-]", "")
    $temperatureC = if ($temperature) { [double]$temperature / 10.0 } else { "" }
    "$(Get-Date -Format o),$level,$temperatureC,$voltage" | Add-Content -LiteralPath $samples -Encoding UTF8
    Start-Sleep -Seconds 60
}

& $adb.Source logcat -d -s R3_PERF:I | Set-Content -LiteralPath (Join-Path $OutputDirectory "r3-perf.log") -Encoding UTF8
& $adb.Source shell dumpsys battery | Set-Content -LiteralPath (Join-Path $OutputDirectory "battery-after.txt") -Encoding UTF8
& $adb.Source shell dumpsys meminfo $PackageName | Set-Content -LiteralPath (Join-Path $OutputDirectory "meminfo-after.txt") -Encoding UTF8

Write-Host "Performance evidence written to: $OutputDirectory"
Write-Host "Review r3-perf.log, battery before/after and meminfo before/after."
