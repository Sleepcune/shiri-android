param([switch]$Release)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools\gradle-home'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools\android-user-home'
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2' }
if (Test-Path -LiteralPath (Join-Path $projectRoot '.tools\android-sdk')) { $env:ANDROID_HOME = Join-Path $projectRoot '.tools\android-sdk' }
$gradle = Join-Path $projectRoot '.tools\gradle-8.11.1\bin\gradle.bat'
if (-not (Test-Path -LiteralPath $gradle)) { $gradle = Join-Path $projectRoot 'gradlew.bat' }
if (-not (Test-Path -LiteralPath $gradle)) { throw '请使用 Android Studio 安装所需 SDK，再运行 Gradle wrapper。' }
if ($Release -and -not (Test-Path -LiteralPath (Join-Path $projectRoot 'signing.properties'))) { throw '发布构建需要 signing.properties；请先运行 scripts/create-signing.ps1，并保留密钥用于覆盖升级。' }
if ($Release) { & $gradle :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --no-daemon --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process' --console=plain } else { & $gradle :core:test :app:testDebugUnitTest :app:assembleDebug --no-daemon --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process' --console=plain }
exit $LASTEXITCODE
