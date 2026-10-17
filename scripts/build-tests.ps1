param([string]$SdkRoot="$env:USERPROFILE\AppData\Local\Android\Sdk")
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $project
$sdkTools=Join-Path $SdkRoot 'build-tools\36.0.0'
$androidJar=Join-Path $SdkRoot 'platforms\android-36\android.jar'
$javaBin=Split-Path (Get-Command java).Source -Parent
function Run([string]$exe,[string[]]$arguments){ & $exe @arguments; if($LASTEXITCODE -ne 0){throw "Failed: $exe"} }
if(!(Test-Path build\manual\classes.jar)){throw 'Run scripts/build.ps1 first'}
New-Item -ItemType Directory -Force build\instrumentation\classes,build\instrumentation\dex | Out-Null
$manifest='<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="cn.chendian.app.test"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><application android:label="沉淀一下集成测试"/><instrumentation android:name="cn.chendian.app.AppTests" android:targetPackage="cn.chendian.app"/></manifest>'
[IO.File]::WriteAllText((Join-Path $project 'build\instrumentation\AndroidManifest.xml'),$manifest,[Text.UTF8Encoding]::new($false))
Run (Join-Path $sdkTools 'aapt2.exe') @('link','-o','build\instrumentation\unsigned.apk','--manifest','build\instrumentation\AndroidManifest.xml','-I',$androidJar,'--debug-mode')
Run (Join-Path $javaBin 'javac.exe') @('-encoding','UTF-8','--release','17','-classpath',"$androidJar;build\manual\classes.jar",'-d','build\instrumentation\classes','app\src\androidTest\java\cn\chendian\app\AppTests.java')
Run (Join-Path $javaBin 'jar.exe') @('cf','build\instrumentation\classes.jar','-C','build\instrumentation\classes','.')
Run (Join-Path $javaBin 'java.exe') @('-Xmx768m','-cp',(Join-Path $sdkTools 'lib\d8.jar'),'com.android.tools.r8.D8','--lib',$androidJar,'--classpath','build\manual\classes.jar','--min-api','26','--output','build\instrumentation\dex','build\instrumentation\classes.jar')
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::Open((Join-Path $project 'build\instrumentation\unsigned.apk'),[IO.Compression.ZipArchiveMode]::Update)
try{Get-ChildItem build\instrumentation\dex -Filter '*.dex' | ForEach-Object { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$_.FullName,$_.Name) | Out-Null }}finally{$zip.Dispose()}
Run (Join-Path $sdkTools 'zipalign.exe') @('-f','4','build\instrumentation\unsigned.apk','build\instrumentation\aligned.apk')
Run (Join-Path $javaBin 'java.exe') @('-jar',(Join-Path $sdkTools 'lib\apksigner.jar'),'sign','--ks','build\manual\debug.keystore','--ks-pass','pass:android','--key-pass','pass:android','--out','dist\chendian-tests.apk','build\instrumentation\aligned.apk')
Write-Output 'Device test APK compiled. Run: adb install -r dist/chendian-tests.apk'
Write-Output 'adb shell am instrument -w cn.chendian.app.test/cn.chendian.app.AppTests'
