param([int]$Port=18088)
$ErrorActionPreference='Stop'
$env:PYTHONIOENCODING='utf-8'
& python (Join-Path $PSScriptRoot 'jmeter_ingress_crosscheck.py') --port $Port
if($LASTEXITCODE -ne 0){throw 'Cross-check failed; inspect preserved performance/results/jmeter-crosscheck-* logs'}
