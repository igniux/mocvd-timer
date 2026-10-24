# Run tools/build.ps1 first. Packages source without SDK paths, caches, IDE state or private session data.
param([string]$Version = '1.2.0')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskDestination = Join-Path $taskRoot 'deliverables'
$taskStage = Join-Path $taskRoot ('.tools\project-package-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $taskDestination, $taskStage | Out-Null
foreach ($taskFile in @('.gitattributes', '.gitignore', 'README.md', 'instructions.md', 'settings.gradle.kts', 'build.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat')) {
    Copy-Item -LiteralPath (Join-Path $taskRoot $taskFile) -Destination $taskStage
}
foreach ($taskFolder in @('gradle', 'tools', 'docs')) {
    Copy-Item -LiteralPath (Join-Path $taskRoot $taskFolder) -Destination $taskStage -Recurse
}
$taskAppDestination = Join-Path $taskStage 'app'
New-Item -ItemType Directory -Force -Path $taskAppDestination | Out-Null
Copy-Item -LiteralPath (Join-Path $taskRoot 'app\build.gradle.kts') -Destination $taskAppDestination
Copy-Item -LiteralPath (Join-Path $taskRoot 'app\src') -Destination $taskAppDestination -Recurse
Copy-Item -LiteralPath (Join-Path $taskRoot "app\build-v$Version\outputs\apk\debug\app-debug.apk") -Destination (Join-Path $taskDestination "MOCVD-Timer-v$Version-debug.apk")
Compress-Archive -Path (Join-Path $taskStage '*') -DestinationPath (Join-Path $taskDestination "MOCVD-Timer-v$Version-project.zip") -Force
$taskLines = foreach ($taskName in @("MOCVD-Timer-v$Version-debug.apk", "MOCVD-Timer-v$Version-project.zip")) {
    $taskHash = (Get-FileHash -LiteralPath (Join-Path $taskDestination $taskName) -Algorithm SHA256).Hash.ToLowerInvariant()
    "$taskHash  $taskName"
}
$taskLines | Set-Content -LiteralPath (Join-Path $taskDestination "SHA256SUMS-v$Version.txt") -Encoding ASCII
Get-ChildItem -LiteralPath $taskDestination | Select-Object Name, Length
