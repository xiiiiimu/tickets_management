param([string]$Test = 'ReservationV2IT')
$ErrorActionPreference = 'Stop'
if($Test -notmatch '^[A-Za-z0-9_,#]+$'){throw 'Invalid test selection'}
$repo = Split-Path -Parent $PSScriptRoot
$credit = Join-Path $repo 'docker/credit_risk'
. (Join-Path $credit '_common.ps1')
$values = Read-CreditEnv
$run = Get-Date -Format 'yyyyMMdd_HHmmss'
$out = Join-Path $repo ('.tmp/reservation-v2-it-'+$run)
New-Item -ItemType Directory -Path $out | Out-Null
$mysqlStarted=$false; $redisStarted=$false
$compose = Join-Path $credit 'compose-v2-tests.yaml'
try {
    docker info --format '{{.ServerVersion}}' | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Docker Engine is unavailable'}
    $mysqlInfo = docker inspect credit-risk-dev-mysql --format '{{index .Config.Labels "com.docker.compose.project"}} {{.State.Running}}'
    if($LASTEXITCODE -ne 0 -or $mysqlInfo -notmatch '^credit-risk-dev (true|false)$'){throw 'MySQL is not owned by credit-risk-dev'}
    if($mysqlInfo -match 'false$'){
        docker compose -p credit-risk-dev -f (Join-Path $credit 'compose.yaml') --env-file (Join-Path $credit '.env') up -d mysql
        if($LASTEXITCODE -ne 0){throw 'MySQL startup failed'}
        $mysqlStarted=$true
    }
    $existing=docker ps -aq --filter 'name=^/credit-risk-v2-redis$'
    if($existing){
        $redisInfo=docker inspect credit-risk-v2-redis --format '{{index .Config.Labels "com.docker.compose.project"}} {{.State.Running}}'
        if($redisInfo -notmatch '^credit-risk-v2-tests (true|false)$'){throw 'Redis name has another owner'}
        $redisStarted=$redisInfo -match 'false$'
    }else{
        if(Get-NetTCPConnection -State Listen -LocalPort 26379 -ErrorAction SilentlyContinue){throw 'Test Redis port is occupied'}
        $redisStarted=$true
    }
    docker compose -p credit-risk-v2-tests -f $compose --env-file (Join-Path $credit '.env') up -d redis
    if($LASTEXITCODE -ne 0){throw 'Test Redis startup failed'}
    $ready=$false
    for($i=0;$i -lt 90;$i++){
        $health=docker inspect credit-risk-dev-mysql --format '{{.State.Health.Status}}'
        if($health -eq 'healthy'){$ready=$true;break}
        Start-Sleep -Seconds 1
    }
    if(!$ready){throw 'MySQL not healthy'}
    $claimScript=@'
export REDISCLI_AUTH="$REDIS_PASSWORD"
for db in $(seq 1 255); do
  claimed=$(redis-cli --no-auth-warning --raw -n "$db" EVAL "if redis.call('DBSIZE')==0 then redis.call('SET',KEYS[1],ARGV[1]); return 1 else return 0 end" 1 credit-risk:v2-test-owner __OWNER__)
  if [ "$claimed" = "1" ]; then printf '%s' "$db"; exit 0; fi
done
exit 1
'@
    $redisDb=docker exec credit-risk-v2-redis sh -c $claimScript.Replace('__OWNER__',$run)
    if($LASTEXITCODE -ne 0 -or $redisDb -notmatch '^\d+$' -or [int]$redisDb -lt 1 -or [int]$redisDb -gt 255){throw 'No empty test Redis database or Redis unavailable; nothing was cleared'}
    $schema='credit_risk_v2_it_'+$run; $dbUser='crv2_'+$run
    $bytes=New-Object byte[] 24;[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $password=([BitConverter]::ToString($bytes)).Replace('-','').ToLowerInvariant()
    $ddl="CREATE DATABASE $schema CHARACTER SET utf8mb4; CREATE USER '$dbUser'@'%' IDENTIFIED BY '$password'; GRANT ALL ON $schema.* TO '$dbUser'@'%';"
    $ddl | docker exec -i credit-risk-dev-mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B'
    if($LASTEXITCODE -ne 0){throw 'Fresh test database creation failed'}
    "DB_USER=$dbUser`nDB_PASSWORD=$password`nDB_NAME=$schema`nREDIS_DB=$redisDb" | Set-Content -LiteralPath (Join-Path $out '.env')
    $env:SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:'+$values['CREDIT_RISK_MYSQL_PORT']+'/'+$schema+'?useSSL=false&serverTimezone=UTC&useLegacyDatetimeCode=false&characterEncoding=utf8&allowPublicKeyRetrieval=true'
    $env:SPRING_DATASOURCE_USERNAME=$dbUser;$env:SPRING_DATASOURCE_PASSWORD=$password
    $env:SPRING_REDIS_HOST='127.0.0.1';$env:SPRING_REDIS_PORT='26379';$env:SPRING_REDIS_PASSWORD=$values['CREDIT_RISK_REDIS_PASSWORD'];$env:SPRING_REDIS_DATABASE=[string]$redisDb
    $env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11';$env:PATH=$env:JAVA_HOME+'\bin;'+$env:PATH
    & mvn -f (Join-Path $repo 'ticket-risk-platform/pom.xml') -Pintegration test-compile failsafe:integration-test failsafe:verify "-Dit.test=$Test" '-Dticketing.v2.integration=true' '-Dspring.jdbc.getParameterType.ignore=true' '-Duser.timezone=UTC' '-Dstyle.color=never' *> (Join-Path $out 'tests.log')
    if($LASTEXITCODE -ne 0){throw ('Integration tests failed. Raw logs: '+$out)}
    Write-Output ('Integration tests passed. Raw logs: '+$out)
} finally {
    if($redisStarted){docker compose -p credit-risk-v2-tests -f $compose --env-file (Join-Path $credit '.env') stop redis}
    if($mysqlStarted){docker compose -p credit-risk-dev -f (Join-Path $credit 'compose.yaml') --env-file (Join-Path $credit '.env') stop mysql}
    Write-Output ('Test database and Redis DB retained; started services stopped. Artifacts: '+$out)
}
