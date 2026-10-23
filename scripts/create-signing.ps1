$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if (Test-Path -LiteralPath 'signing.properties') { Write-Output '已有签名配置，保留原密钥。'; exit 0 }
New-Item -ItemType Directory -Force -Path '.private' | Out-Null
$random = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($random)
$signingPassword = [Convert]::ToBase64String($random)
$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\keytool.exe' } else { 'C:\Program Files\Java\jdk-17.0.2\bin\keytool.exe' }
& $keytool -genkeypair -keystore '.private\hubra-release.jks' -storepass $signingPassword -keypass $signingPassword -alias hubra -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Hubra Personal, OU=Personal, O=Hubra, C=CN'
if ($LASTEXITCODE -ne 0) { throw '创建签名失败' }
@("storeFile=.private/hubra-release.jks", "storePassword=$signingPassword", 'keyAlias=hubra', "keyPassword=$signingPassword") | Set-Content -LiteralPath 'signing.properties' -Encoding ASCII
Write-Output '发布签名已创建，密钥与密码仅保存在项目私有文件中。请离线备份 .private 与 signing.properties。'
