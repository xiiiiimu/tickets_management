[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Run,[ValidateSet('start','stop','status')][string]$Action='status')
$ErrorActionPreference='Stop'
$repo=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$env:PYTHONIOENCODING='utf-8'
& python (Join-Path $repo 'performance/v3_local_env.py') $Action --run $Run --execute
if($LASTEXITCODE -ne 0){throw 'Owned V3 operation failed. No unrelated resources are stopped or reset.'}
