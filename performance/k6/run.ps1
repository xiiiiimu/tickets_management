$ErrorActionPreference='Stop'
$env:PYTHONIOENCODING='utf-8'
& python (Join-Path $PSScriptRoot 'run_diagnostic.py')
if($LASTEXITCODE -ne 0){throw 'Diagnostic failed; inspect preserved k6-diagnostic result directory'}
