param([ValidateSet('A','B','C')][string]$Suite='B',
      [ValidateSet('poll','counter')][string]$Observation='poll',
      [ValidateSet('both','valid')][string]$Workload='both')
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$credit=Join-Path $repo 'docker/credit_risk'
$started=New-Object System.Collections.Generic.List[string]
function State([string]$name,[string]$project){
    $raw=docker inspect $name --format '{{index .Config.Labels "com.docker.compose.project"}} {{.State.Running}} {{.HostConfig.RestartPolicy.Name}}' 2>$null
    if($LASTEXITCODE -ne 0){return $false}
    if($raw -notlike ($project+' * no')){throw "Unexpected ownership/restart policy: $name"}
    return $raw -like '* true no'
}
try{
    if(!(State 'credit-risk-dev-mysql' 'credit-risk-dev')){
        docker compose -p credit-risk-dev -f (Join-Path $credit 'compose.yaml') --env-file (Join-Path $credit '.env') up -d mysql
        if($LASTEXITCODE -ne 0){throw 'MySQL startup failed'};$started.Add('mysql')
    }
    if(!(State 'credit-risk-v2-redis' 'credit-risk-v2-tests')){
        docker compose -p credit-risk-v2-tests -f (Join-Path $credit 'compose-v2-tests.yaml') --env-file (Join-Path $credit '.env') up -d redis
        if($LASTEXITCODE -ne 0){throw 'Test Redis startup failed'};$started.Add('redis')
    }
    if($Suite -eq 'C'){
        foreach($pair in @(@('namesrv','credit-risk-v2-bench-namesrv'),@('broker','credit-risk-v2-bench-broker'))){
            if(!(State $pair[1] 'credit-risk-v2-mq')){
                docker compose -p credit-risk-v2-mq -f (Join-Path $credit 'compose-v2-mq.yaml') up -d $pair[0]
                if($LASTEXITCODE -ne 0){throw 'Benchmark MQ startup failed'};$started.Add($pair[0])
            }
        }
        Start-Sleep -Seconds 15
        $topicReady=$false
        for($attempt=0;$attempt -lt 20;$attempt++){
            docker exec credit-risk-v2-bench-broker sh /home/rocketmq/rocketmq-4.9.7/bin/mqadmin updateTopic -n namesrv:9876 -c credit-risk-v2-bench-cluster -t ticket-events -r 4 -w 4 *> $null
            if($LASTEXITCODE -eq 0){$topicReady=$true;break}
            Start-Sleep -Seconds 2
        }
        if(!$topicReady){throw 'Dedicated benchmark MQ topic could not be provisioned'}
    }
    Set-Location -LiteralPath $repo
    python (Join-Path $PSScriptRoot 'reservation_pipeline_benchmark.py') --suite $Suite --observation $Observation --workload $Workload
    if($LASTEXITCODE -ne 0){throw 'Benchmark failed; raw logs/data were retained'}
} finally {
    foreach($service in @('broker','namesrv','redis','mysql')){
        if(!$started.Contains($service)){continue}
        if($service -eq 'redis'){docker compose -p credit-risk-v2-tests -f (Join-Path $credit 'compose-v2-tests.yaml') --env-file (Join-Path $credit '.env') stop redis}
        elseif($service -eq 'mysql'){docker compose -p credit-risk-dev -f (Join-Path $credit 'compose.yaml') --env-file (Join-Path $credit '.env') stop mysql}
        else{docker compose -p credit-risk-v2-mq -f (Join-Path $credit 'compose-v2-mq.yaml') stop $service}
    }
}
