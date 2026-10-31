$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$jdk='C:\Program Files\Java\jdk-21.0.11\bin'
$cp=(Join-Path $root 'ticket-risk-platform/target/classes')+';'+(Join-Path $root 'ticket-risk-platform/target/fast-ingress-benchmark-classes')+';'+(Get-Content -Raw (Join-Path $root 'ticket-risk-platform/target/benchmark-classpath.txt')).Trim()
$classes=Join-Path $root '.tmp/auth-mode-classes'
New-Item -ItemType Directory -Force $classes | Out-Null
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 -proc:none -source 8 -target 8 -cp $cp -d $classes (Join-Path $PSScriptRoot 'AuthModeIngress.java')
if($LASTEXITCODE -ne 0){throw 'Test fixture compilation failed'}
$env:PYTHONIOENCODING='utf-8'
& python (Join-Path $PSScriptRoot 'auth_mode_comparison.py')
if($LASTEXITCODE -ne 0){throw 'Auth-mode experiment stopped; inspect preserved auth-mode result directory'}
