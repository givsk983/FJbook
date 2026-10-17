param([string]$SdkRoot = "$env:USERPROFILE\AppData\Local\Android\Sdk")
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $project
$sdkTools = Join-Path $SdkRoot 'build-tools\36.0.0'
$androidJar = Join-Path $SdkRoot 'platforms\android-36\android.jar'
$javaBin = Split-Path (Get-Command java).Source -Parent
function Run([string]$exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "Build step failed: $exe" }
}
New-Item -ItemType Directory -Force build\manual\generated,build\manual\classes,build\manual\dex,dist | Out-Null
Run (Join-Path $sdkTools 'aapt2.exe') @('compile','--dir','app\src\main\res','-o','build\manual\resources.zip')
$manifest = [IO.File]::ReadAllText((Join-Path $project 'app\src\main\AndroidManifest.xml')).Replace('<manifest xmlns:', '<manifest package="cn.chendian.app" xmlns:')
[IO.File]::WriteAllText((Join-Path $project 'build\manual\AndroidManifest.xml'),$manifest,[Text.UTF8Encoding]::new($false))
Run (Join-Path $sdkTools 'aapt2.exe') @('link','-o','build\manual\unsigned.apk','--manifest','build\manual\AndroidManifest.xml','-I',$androidJar,'--java','build\manual\generated','--min-sdk-version','26','--target-sdk-version','35','--version-code','3','--version-name','0.1.2','--debug-mode','build\manual\resources.zip')
$sources = @(Get-ChildItem app\src\main\java,build\manual\generated -Recurse -Filter '*.java' | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' })
[IO.File]::WriteAllLines((Join-Path $project 'build\manual\sources.txt'),$sources,[Text.UTF8Encoding]::new($false))
Run (Join-Path $javaBin 'javac.exe') @('-encoding','UTF-8','--release','17','-classpath',$androidJar,'-d','build\manual\classes','@build/manual/sources.txt')
Run (Join-Path $javaBin 'jar.exe') @('cf','build\manual\classes.jar','-C','build\manual\classes','.')
Run (Join-Path $javaBin 'java.exe') @('-Xmx768m','-cp',(Join-Path $sdkTools 'lib\d8.jar'),'com.android.tools.r8.D8','--lib',$androidJar,'--min-api','26','--output','build\manual\dex','build\manual\classes.jar')
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkPath = Join-Path $project 'build\manual\unsigned.apk'
$archive = [IO.Compression.ZipFile]::Open($apkPath,[IO.Compression.ZipArchiveMode]::Update)
try { Get-ChildItem build\manual\dex -Filter '*.dex' | ForEach-Object { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$_.FullName,$_.Name) | Out-Null } } finally { $archive.Dispose() }
Run (Join-Path $sdkTools 'zipalign.exe') @('-f','4','build\manual\unsigned.apk','build\manual\aligned.apk')
$key='build\manual\debug.keystore'
if (!(Test-Path -LiteralPath $key)) {
    Run (Join-Path $javaBin 'keytool.exe') @('-genkeypair','-keystore',$key,'-storepass','android','-keypass','android','-alias','chendian-debug','-dname','CN=Chendian Debug,O=Local,C=CN','-keyalg','RSA','-keysize','2048','-validity','10000')
}
Run (Join-Path $javaBin 'java.exe') @('-jar',(Join-Path $sdkTools 'lib\apksigner.jar'),'sign','--ks',$key,'--ks-pass','pass:android','--key-pass','pass:android','--out','dist\chendian-0.1.2.apk','build\manual\aligned.apk')
Run (Join-Path $javaBin 'java.exe') @('-jar',(Join-Path $sdkTools 'lib\apksigner.jar'),'verify','--verbose','dist\chendian-0.1.2.apk')
Run (Join-Path $sdkTools 'aapt2.exe') @('dump','badging','dist\chendian-0.1.2.apk')
Get-FileHash -LiteralPath dist\chendian-0.1.2.apk -Algorithm SHA256
