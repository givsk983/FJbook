param([string]$SdkRoot = "$env:USERPROFILE\AppData\Local\Android\Sdk")
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $project
if (!(Test-Path -LiteralPath $SdkRoot)) { throw "Android SDK not found: $SdkRoot" }
$env:ANDROID_HOME = $SdkRoot
& .\gradlew.bat --gradle-user-home .gradle-home :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
New-Item -ItemType Directory -Force dist | Out-Null
Copy-Item -LiteralPath app\build\outputs\apk\debug\app-debug.apk -Destination dist\chendian-0.2.5.apk
Get-FileHash -LiteralPath dist\chendian-0.2.5.apk -Algorithm SHA256
