$ErrorActionPreference='Stop'
$env:PYTHONIOENCODING='utf-8'
& python (Join-Path $PSScriptRoot 'run_vu_capacity.py')
if($LASTEXITCODE -ne 0){throw 'VU capacity test stopped; inspect preserved k6-vu-capacity results'}
