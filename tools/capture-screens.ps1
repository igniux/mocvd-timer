param([string]$Serial = 'emulator-5554')
# Captures actual app screens through UI actions on a dedicated test emulator.
$ErrorActionPreference = 'Stop'
if (-not $Serial.StartsWith('emulator-')) { throw 'Use a dedicated test emulator.' }
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskAdb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$taskDestination = Join-Path $taskRoot 'docs\screenshots'
New-Item -ItemType Directory -Force -Path $taskDestination | Out-Null
function Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArguments)
    $taskPreviousErrors = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $taskResult = & $taskAdb -s $Serial @AdbArguments 2>&1
    $taskExit = $LASTEXITCODE
    $ErrorActionPreference = $taskPreviousErrors
    if ($taskExit -ne 0) { throw ($taskResult -join "`n") }
    return ($taskResult -join "`n")
}
function Nodes {
    Adb shell uiautomator dump /sdcard/mocvd-window.xml | Out-Null
    [xml]$taskXml = Adb shell cat /sdcard/mocvd-window.xml
    return $taskXml.SelectNodes('//node')
}
function Tap([string]$Label) {
    $taskNode = Nodes | Where-Object { $_.text -eq $Label } | Select-Object -First 1
    if (-not $taskNode) { throw "Button not found: $Label" }
    $taskBounds = [regex]::Matches($taskNode.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    Adb shell input tap ([int](($taskBounds[0] + $taskBounds[2]) / 2)) ([int](($taskBounds[1] + $taskBounds[3]) / 2)) | Out-Null
    Start-Sleep -Milliseconds 650
}
function Capture([string]$Name) {
    Adb -AdbArguments @('shell', 'screencap', '-p', '/sdcard/mocvd-capture.png') | Out-Null
    Adb pull /sdcard/mocvd-capture.png (Join-Path $taskDestination "$Name.png") | Out-Null
    Write-Output "Captured $Name"
}
Adb install -r (Join-Path $taskRoot 'app\build-v1.2.0\outputs\apk\debug\app-debug.apk') | Out-Null
Adb shell am start -n lt.ignas.mocvd/.MainActivity | Out-Null
Start-Sleep -Seconds 1
$taskCurrent = Nodes
if ($taskCurrent | Where-Object { $_.text -eq 'Accept recovered estimate' }) {
    Capture 'recovery'
    Tap 'Accept recovered estimate'
    $taskRecovered = (Adb shell run-as lt.ignas.mocvd cat files/mocvd-session.json | ConvertFrom-Json).session
    if ($taskRecovered.recoveryRequired) { throw 'Recovery review was not acknowledged.' }
    if ($taskRecovered.scheduledReminders.Count -eq 0) { throw 'Acknowledging recovery did not restore outstanding reminders.' }
    Capture 'growth'
}
$taskCurrent = Nodes
if ($taskCurrent | Where-Object { $_.text -eq 'Reset session' }) { Tap 'Reset session'; Tap 'Confirm' }
Capture 'idle'
Tap 'Start process'
Capture 'vacuum'
Tap 'Advance early'
Tap 'Confirm'
Capture 'atmosphere'
Tap 'Reset session'
Tap 'Confirm'
Write-Output 'Screens captured. Emulator app is back at the idle screen.'
