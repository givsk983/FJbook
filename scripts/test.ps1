$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force build\tests | Out-Null
& javac -encoding UTF-8 -d build\tests app\src\main\java\cn\chendian\app\BanTime.java scripts\BanTimeTests.java
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& java -cp build\tests BanTimeTests
if ($LASTEXITCODE -ne 0) { throw 'Time tests failed' }
