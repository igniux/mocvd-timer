param(
    [string[]]$Tasks = @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug')
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskBundledJdk = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
if (Test-Path -LiteralPath (Join-Path $taskBundledJdk 'bin\java.exe')) { $env:JAVA_HOME = $taskBundledJdk }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to the JDK configured in Android Studio.' }
if (-not (Test-Path -LiteralPath (Join-Path $taskRoot 'local.properties'))) {
    $taskSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    if (-not (Test-Path -LiteralPath $taskSdk)) { throw 'Install the Android SDK through Android Studio first.' }
    Set-Content -LiteralPath (Join-Path $taskRoot 'local.properties') -Encoding ASCII -Value ('sdk.dir=' + $taskSdk.Replace('\', '/').Replace(':', '\:'))
}
$env:DEBUG = $null
Push-Location $taskRoot
try {
    & '.\gradlew.bat' --gradle-user-home '.gradle-cache' @Tasks --console plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
