$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$java='C:\Program Files\Java\jdk-21.0.11\bin'
$cp=(Get-Content -Raw (Join-Path $root 'ticket-risk-platform/target/benchmark-classpath.txt')).Trim()
$classes=Join-Path $root '.tmp/lettuce-wait-classes'
New-Item -ItemType Directory -Force $classes | Out-Null
& (Join-Path $java 'javac.exe') -encoding UTF-8 -proc:none -cp $cp -d $classes (Join-Path $PSScriptRoot 'LettuceWaitProbe.java')
if($LASTEXITCODE -ne 0){throw 'Diagnostic-only compile failed'}
$env:PYTHONIOENCODING='utf-8'
& python (Join-Path $PSScriptRoot 'lettuce_wait_diagnostic.py')
if($LASTEXITCODE -ne 0){throw 'Diagnostic failed; inspect preserved results'}
