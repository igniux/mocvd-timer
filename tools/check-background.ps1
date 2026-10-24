param([string]$Serial = 'emulator-5554')

# Replaces only this app's test session on a dedicated emulator. Never targets a physical phone.
$ErrorActionPreference = 'Stop'
if (-not $Serial.StartsWith('emulator-')) { throw 'This fixture test only supports a dedicated Android emulator.' }
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskAdb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$taskOutput = Join-Path $taskRoot '.tools\background-check'
New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
function Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArguments)
    $taskPreviousErrors = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $taskResult = & $taskAdb -s $Serial @AdbArguments 2>&1
    $taskExitCode = $LASTEXITCODE
    $ErrorActionPreference = $taskPreviousErrors
    if ($taskExitCode -ne 0) { throw ($taskResult -join "`n") }
    return ($taskResult -join "`n")
}
function Read-Session {
    $taskJson = Adb shell run-as lt.ignas.mocvd cat files/mocvd-session.json
    return ($taskJson | ConvertFrom-Json).session
}
function Require([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    Write-Output ('PASS: ' + $Message)
}

$taskApk = Join-Path $taskRoot 'app\build-v1.2.0\outputs\apk\debug\app-debug.apk'
Adb install -r $taskApk | Out-Null
Adb shell am start -n lt.ignas.mocvd/.MainActivity | Out-Null
Start-Sleep -Seconds 2
Adb shell am force-stop lt.ignas.mocvd | Out-Null
Adb shell pm grant lt.ignas.mocvd android.permission.POST_NOTIFICATIONS | Out-Null
Adb shell appops set lt.ignas.mocvd SCHEDULE_EXACT_ALARM allow | Out-Null
$taskElapsed = [long]([double]::Parse((Adb shell cat /proc/uptime).Split(' ')[0], [Globalization.CultureInfo]::InvariantCulture) * 1000)
$taskWall = [long](Adb shell date +%s) * 1000
$taskBoot = [int](Adb shell settings get global boot_count)
$taskSession = [ordered]@{
    id = [guid]::NewGuid().ToString(); stage = 'GROWTH_RUNNING'; phase = 'RUNNING'; cycle = 0; speed = 30
    stageStartedElapsed = $taskElapsed; stageStartedWall = $taskWall
    growthStartedElapsed = $taskElapsed; growthStartedWall = $taskWall
    deadlineElapsed = $taskElapsed + 50000; deadlineWall = $taskWall + 50000
    pausedRemaining = 0; boot = $taskBoot; generation = 6; delivered = @(); missed = @()
    earlyTransitions = 0; lastAction = 'Background verification fixture'; recoveryRequired = $false; overdueByEdit = $false
    config = @{ reactorSeconds = 600; growthSeconds = 1500; conversion = 'MANUAL'; referenceReactorSeconds = 600; referenceTotalSeconds = 4500; adjustmentFactor = 1.0 }
    scheduledReminders = @()
}
$taskFixture = @{ version = 1; session = $taskSession; preferences = @{ sound = $false; vibration = $false; keepAwake = $false; simulation = $true } }
$taskFixturePath = Join-Path $taskOutput 'fixture.json'
[IO.File]::WriteAllText($taskFixturePath, ($taskFixture | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
Adb push $taskFixturePath /data/local/tmp/mocvd-test-fixture.json | Out-Null
Adb -AdbArguments @('shell', 'run-as', 'lt.ignas.mocvd', 'mkdir', '-p', 'files') | Out-Null
Adb shell run-as lt.ignas.mocvd cp /data/local/tmp/mocvd-test-fixture.json files/mocvd-session.json | Out-Null
Adb shell am start -n lt.ignas.mocvd/.MainActivity | Out-Null
Start-Sleep -Seconds 2
Adb shell input keyevent KEYCODE_HOME | Out-Null
Start-Sleep -Milliseconds 600
Adb shell am kill lt.ignas.mocvd | Out-Null
Start-Sleep -Milliseconds 300
$taskPid = & $taskAdb -s $Serial shell pidof lt.ignas.mocvd
if (-not [string]::IsNullOrWhiteSpace(($taskPid -join ''))) {
    # On recent Android the activity may retain a temporary foreground grace period.
    # Kill as the debug app's own UID, without setting Android's force-stopped flag.
    Adb shell run-as lt.ignas.mocvd kill -9 ($taskPid -join ' ') | Out-Null
    Start-Sleep -Milliseconds 200
    $taskPid = & $taskAdb -s $Serial shell pidof lt.ignas.mocvd
}
Require ([string]::IsNullOrWhiteSpace(($taskPid -join ''))) 'The background app process was killed before the first measurement alarm.'

try {
    Adb shell dumpsys battery unplug | Out-Null
    Adb shell dumpsys deviceidle force-idle | Set-Content -LiteralPath (Join-Path $taskOutput 'idle-entry.txt')
    Adb shell input keyevent KEYCODE_SLEEP | Out-Null
    Start-Sleep -Seconds 12
    $taskFirst = Read-Session
    Require ($taskFirst.delivered -notcontains 'measurement-warning') 'No five-minute measurement pre-warning was delivered.'
    Require ($taskFirst.delivered -notcontains 'measurement-start') 'Measurement start was not delivered early.'
    Start-Sleep -Seconds 9
    $taskMeasurement = Read-Session
    Require ($taskMeasurement.delivered -contains 'measurement-start') 'The 11:30 measurement reminder was delivered.'
    Require ($taskMeasurement.stage -eq 'GROWTH_RUNNING') 'Growth remained active after the measurement reminder.'
    Start-Sleep -Seconds 30
    $taskEnd = Read-Session
    Require ($taskEnd.stage -eq 'GROWTH_FINISHED') 'The estimated growth completion reached the finished state.'
    Require ($taskEnd.delivered -contains 'growth-warning') 'The five-minute completion warning was delivered.'
    Require ($taskEnd.delivered -contains 'growth-complete') 'The estimated completion notification was delivered.'
    Require ($taskEnd.delivered.Count -eq 3) 'Exactly three growth reminder IDs were handled without duplicates.'
    Require ($taskEnd.growthStartedElapsed -eq $taskElapsed) 'Original monotonic growth start remained unchanged.'
    Adb shell run-as lt.ignas.mocvd cat files/mocvd-session.json | Set-Content -LiteralPath (Join-Path $taskOutput 'final-state.json')
    Adb shell dumpsys notification --noredact | Set-Content -LiteralPath (Join-Path $taskOutput 'notifications.txt')
    Adb shell dumpsys alarm | Set-Content -LiteralPath (Join-Path $taskOutput 'alarms.txt')
    Adb shell dumpsys deviceidle | Set-Content -LiteralPath (Join-Path $taskOutput 'idle-final.txt')
} finally {
    Adb shell dumpsys deviceidle unforce | Out-Null
    Adb shell dumpsys battery reset | Out-Null
    Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    Adb shell wm dismiss-keyguard | Out-Null
}
Write-Output 'Background verification complete. Evidence is in .tools/background-check.'
