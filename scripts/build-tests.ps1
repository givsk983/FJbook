param([string]$SdkRoot = "$env:USERPROFILE\AppData\Local\Android\Sdk")
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $project
if (!(Test-Path -LiteralPath $SdkRoot)) { throw "Android SDK not found: $SdkRoot" }
$env:ANDROID_HOME = $SdkRoot
& .\gradlew.bat --gradle-user-home .gradle-home :app:assembleDebug :app:assembleAndroidTest
if ($LASTEXITCODE -ne 0) { throw 'Gradle test APK build failed' }
New-Item -ItemType Directory -Force dist | Out-Null
Copy-Item -LiteralPath app\build\outputs\apk\debug\app-debug.apk -Destination dist\chendian-0.2.5.apk
Copy-Item -LiteralPath app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk -Destination dist\chendian-tests.apk
Write-Output 'Device test APK compiled. Run: adb install -r dist/chendian-tests.apk'
Write-Output 'adb shell am instrument -w cn.chendian.app.test/cn.chendian.app.AppTests'
