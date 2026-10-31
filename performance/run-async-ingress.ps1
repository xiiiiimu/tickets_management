param([ValidateSet('faults','multinode','load','all')][string]$Suite='all',[int]$Duration=120,[int]$Rate=10)
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$credit=Join-Path $repo 'docker/credit_risk'
$started=New-Object System.Collections.Generic.List[string]
$run=Get-Date -Format 'yyyyMMdd_HHmmss'
$logs=Join-Path $repo ('.tmp/async-ingress-run-'+$run)
New-Item -ItemType Directory -Path $logs | Out-Null
function Check-Owned([string]$name,[string]$owner){
    $raw=docker inspect $name
    if($LASTEXITCODE -ne 0){throw ('Missing owned test service '+$name)}
    $info=($raw|ConvertFrom-Json)[0]
    if($info.Config.Labels.'com.docker.compose.project' -ne $owner){throw ('Unexpected owner: '+$name)}
    if($info.HostConfig.RestartPolicy.Name -ne 'no'){throw ('Restart policy is not no: '+$name)}
    return $info.State.Running
}
try {
    docker ps -a --format '{{.Names}} {{.Status}}' | Set-Content (Join-Path $logs 'initial-containers.txt')
    if(!(Check-Owned 'credit-risk-dev-mysql' 'credit-risk-dev')){
        docker compose -p credit-risk-dev -f (Join-Path $credit 'compose.yaml') --env-file (Join-Path $credit '.env') up -d mysql
        if($LASTEXITCODE -ne 0){throw 'MySQL startup failed'}
        $started.Add('mysql')
    }
    if(!(Check-Owned 'credit-risk-v2-redis' 'credit-risk-v2-tests')){
        docker compose -p credit-risk-v2-tests -f (Join-Path $credit 'compose-v2-tests.yaml') --env-file (Join-Path $credit '.env') up -d redis
        if($LASTEXITCODE -ne 0){throw 'Redis startup failed'}
        $started.Add('redis')
    }
    $healthy=$false
    for($i=0;$i -lt 90;$i++){
        $inspect=(docker inspect credit-risk-dev-mysql | ConvertFrom-Json)[0]
        if($inspect.State.Health.Status -eq 'healthy'){$healthy=$true;break}
        Start-Sleep -Seconds 1
    }
    if(!$healthy){throw 'MySQL not healthy'}
    & python (Join-Path $PSScriptRoot 'async_ingress_benchmark.py') --suite $Suite --duration $Duration --rate $Rate *> (Join-Path $logs 'run.log')
    if($LASTEXITCODE -ne 0){throw ('Verification failed; raw log: '+(Join-Path $logs 'run.log'))}
    Get-Content (Join-Path $logs 'run.log')
} finally {
    if($started.Contains('redis')){docker compose -p credit-risk-v2-tests -f (Join-Path $credit 'compose-v2-tests.yaml') --env-file (Join-Path $credit '.env') stop redis}
    if($started.Contains('mysql')){docker compose -p credit-risk-dev -f (Join-Path $credit 'compose.yaml') --env-file (Join-Path $credit '.env') stop mysql}
    docker ps -a --format '{{.Names}} {{.Status}}' | Set-Content (Join-Path $logs 'final-containers.txt')
    Write-Output ('Services started by this run were stopped; existing running services were preserved. Logs: '+$logs)
}
