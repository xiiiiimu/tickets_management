$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$jdk='C:\Program Files\Java\jdk-21.0.11\bin'
$cp=(Join-Path $root 'ticket-risk-platform/target/classes')+';'+(Get-Content -Raw (Join-Path $root 'ticket-risk-platform/target/benchmark-classpath.txt')).Trim()
$classes=Join-Path $root '.tmp/lockdiag-classes'
New-Item -ItemType Directory -Force $classes | Out-Null
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 -proc:none -source 8 -target 8 -cp $cp -d $classes (Join-Path $PSScriptRoot 'LockProbe.java') (Join-Path $PSScriptRoot 'LockDiagnosticAgent.java')
if($LASTEXITCODE -ne 0){throw 'Test-only probe compilation failed'}
$manifest=Join-Path $root '.tmp/lockdiag-manifest.mf'
Set-Content $manifest "Premain-Class: lockdiag.LockDiagnosticAgent`nCan-Redefine-Classes: false`nCan-Retransform-Classes: false`n" -Encoding ascii
& (Join-Path $jdk 'jar.exe') cfm (Join-Path $root '.tmp/lockdiag-agent.jar') $manifest -C $classes .
if($LASTEXITCODE -ne 0){throw 'Test-only agent packaging failed'}
$env:PYTHONIOENCODING='utf-8'
& python (Join-Path $PSScriptRoot 'real_lock_diagnostic.py')
if($LASTEXITCODE -ne 0){throw 'Diagnosis stopped; preserve and inspect owned namespace/logs'}
