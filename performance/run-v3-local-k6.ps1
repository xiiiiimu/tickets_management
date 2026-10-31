[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Run,[ValidateSet('joint')][string]$Mode='joint',
      [switch]$Execute)
$ErrorActionPreference='Stop'
if(-not $Execute){throw 'No load started. Separate explicit -Execute required after reviewing the owned environment.'}
$env:PYTHONIOENCODING='utf-8'
& python (Join-Path $PSScriptRoot 'run_v3_joint.py') --run $Run --execute
if($LASTEXITCODE -ne 0){throw 'Owned V3 workload failed; retained raw logs, no reset or automatic retry performed.'}
