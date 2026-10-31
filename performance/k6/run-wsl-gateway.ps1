$ErrorActionPreference='Stop'
$env:PYTHONIOENCODING='utf-8'
& python (Join-Path $PSScriptRoot 'run_wsl_gateway.py')
if($LASTEXITCODE -ne 0){throw 'WSL diagnostic stopped; inspect preserved wsl-gateway-load result directory'}
