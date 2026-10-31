[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$K6,
    [Parameter(Mandatory=$true)][string]$Fixtures,
    [Parameter(Mandatory=$true)][string]$Url,
    [Parameter(Mandatory=$true)][ValidateSet('single','multi')][string]$SkuMode,
    [Parameter(Mandatory=$true)][string]$ConfigurationEvidence,
    [ValidateRange(1,10000)][int]$Rate=100,
    [ValidateRange(300,600)][int]$Seconds=300,
    [ValidateRange(1,60)][int]$WarmupSeconds=10,
    [ValidateRange(1,512)][int]$VUs=128,
    [switch]$Execute
)
$ErrorActionPreference='Stop'
if(-not $Execute){throw 'No load started. Explicit -Execute and isolated fixtures are required.'}
if($Url -notmatch '^http://(localhost|127\.0\.0\.1):\d+/api/tickets/reservations$'){throw 'Only localhost isolated servers are allowed'}
$fixturePath=(Resolve-Path -LiteralPath $Fixtures).Path
$evidencePath=(Resolve-Path -LiteralPath $ConfigurationEvidence).Path
$k6Path=(Resolve-Path -LiteralPath $K6).Path
$fixture=Get-Content -LiteralPath $fixturePath -Raw -Encoding UTF8 | ConvertFrom-Json
if($fixture.isolated -ne $true -or $fixture.schema -notmatch '^credit_risk_controlled_[a-z0-9_]+$'){throw 'Owned isolated fixture metadata required'}
# Evidence must be collected from the running test server, not inferred from requested settings.
$evidence=Get-Content -LiteralPath $evidencePath -Raw -Encoding UTF8 | ConvertFrom-Json
if($evidence.schema -ne $fixture.schema -or $evidence.fastConsumer -ne $false -or -not $evidence.codeSha256 -or -not $evidence.effectiveFlags){throw 'Server/schema/code/effective flags evidence required; fast consumers forbidden'}
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$runId=[guid]::NewGuid().ToString('N')
$out=Join-Path $root ('performance/results/attempt-steady-'+$runId)
New-Item -ItemType Directory -Path $out | Out-Null
$settings=@{ATTEMPT_EXECUTE='isolated-schema-confirmed';ATTEMPT_URL=$Url;ATTEMPT_FIXTURES=$fixturePath;
    ATTEMPT_SKU_MODE=$SkuMode;ATTEMPT_RATE="$Rate";ATTEMPT_SECONDS="$Seconds";ATTEMPT_WARMUP_SECONDS="$WarmupSeconds";
    ATTEMPT_VUS="$VUs";ATTEMPT_RUN_ID=$runId;ATTEMPT_SUMMARY=(Join-Path $out 'summary.json')}
$previous=@{}
foreach($name in $settings.Keys){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$settings[$name],'Process')}
$proc=$null
try {
    $manifest=@{url=$Url;rate=$Rate;seconds=$Seconds;warmup_seconds=$WarmupSeconds;vus=$VUs;sku_mode=$SkuMode;
        fixture_sha256=(Get-FileHash -LiteralPath $fixturePath -Algorithm SHA256).Hash;
        script_sha256=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'steady_state.js') -Algorithm SHA256).Hash;
        effective_server=$evidence;server_owned_by_runner=$false;flags_changed=$false}
    $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $out 'manifest.json') -Encoding UTF8
    & $k6Path version | Set-Content -LiteralPath (Join-Path $out 'k6-version.txt') -Encoding UTF8
    if($LASTEXITCODE -ne 0){throw 'Cannot read k6 version'}
    # Only this newly created k6 process is ever stopped. No server/container lifecycle actions.
    $script=Join-Path $PSScriptRoot 'steady_state.js'
    $proc=Start-Process -FilePath $k6Path -ArgumentList @('run','--quiet',('"'+$script+'"')) -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $out 'stdout.log') -RedirectStandardError (Join-Path $out 'stderr.log')
    if(-not $proc.WaitForExit(($Seconds+$WarmupSeconds+60)*1000)){throw 'Bounded runner timeout; only the owned k6 process will be stopped'}
    if($proc.ExitCode -ne 0){throw ('k6 failed, exit='+$proc.ExitCode)}
} finally {
    if($null -ne $proc -and -not $proc.HasExited){Stop-Process -Id $proc.Id; $proc.WaitForExit()}
    foreach($name in $settings.Keys){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}
    Write-Output $out
}
