$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$jdk='C:\Program Files\Java\jdk-21.0.11\bin'
$cp=(Join-Path $root 'ticket-risk-platform/target/classes')+';'+(Get-Content -Raw (Join-Path $root 'ticket-risk-platform/target/benchmark-classpath.txt')).Trim()
$classes=Join-Path $root '.tmp/fullchain-classes'
New-Item -ItemType Directory -Force $classes | Out-Null
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 -proc:none -source 8 -target 8 -cp $cp -d $classes (Join-Path $PSScriptRoot 'FullChainProbe.java') (Join-Path $PSScriptRoot 'FullChainAgent.java')
if($LASTEXITCODE -ne 0){throw 'Diagnostic compilation failed; no business requests were sent'}
$manifest=Join-Path $root '.tmp/fullchain-manifest.mf'
Set-Content $manifest "Premain-Class: fullchain.FullChainAgent`nCan-Redefine-Classes: false`nCan-Retransform-Classes: false`n" -Encoding ascii
& (Join-Path $jdk 'jar.exe') cfm (Join-Path $root '.tmp/fullchain-agent.jar') $manifest -C $classes .
if($LASTEXITCODE -ne 0){throw 'Diagnostic packaging failed'}
$env:PYTHONIOENCODING='utf-8'
& python (Join-Path $PSScriptRoot 'full_chain_diagnostic.py')
if($LASTEXITCODE -ne 0){throw 'Single batch stopped; inspect raw data. Do not automatically repeat.'}
