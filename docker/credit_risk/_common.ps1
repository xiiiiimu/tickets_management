# Helpers only: dot-sourcing this file never starts or stops a service.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$CreditRoot = $PSScriptRoot
$CreditEnvFile = Join-Path $CreditRoot '.env'
$CreditComposeFile = Join-Path $CreditRoot 'compose.yaml'
$CreditProject = 'credit-risk-dev'
$CreditNames = @('mysql','redis','namesrv','broker','prometheus','grafana') | ForEach-Object { 'credit-risk-dev-' + $_ }
function Read-CreditEnv {
    if (!(Test-Path -LiteralPath $CreditEnvFile)) { throw 'Create the private .env from .env.example first.' }
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($CreditEnvFile)) {
        if ($line -match '^\s*(#|$)') { continue }
        $offset = $line.IndexOf('=')
        if ($offset -lt 1) { throw 'Invalid .env line.' }
        $key = $line.Substring(0,$offset).Trim()
        $value = $line.Substring($offset+1).Trim()
        if ($value.Length -gt 1 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1,$value.Length-2) }
        $values[$key] = $value
    }
    return $values
}
function Invoke-CreditCompose {
    & docker compose --env-file $CreditEnvFile -p $CreditProject -f $CreditComposeFile @args
    if ($LASTEXITCODE -ne 0) { throw 'The isolated credit-risk-dev Compose command failed.' }
}
function Get-CreditContainers {
    $containerIds = @(& docker ps -aq --filter "label=com.docker.compose.project=$CreditProject")
    if ($LASTEXITCODE -ne 0) { throw 'Docker is not available.' }
    if ($containerIds.Count -eq 0) { return @() }
    $objects = @((& docker inspect @containerIds) | ConvertFrom-Json)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect isolated containers.' }
    return $objects
}
function Assert-CreditOwnership {
    $allNames = @(& docker ps -a --format '{{.Names}}')
    if ($LASTEXITCODE -ne 0) { throw 'Docker is not available.' }
    foreach ($name in $CreditNames) {
        if ($allNames -contains $name) {
            $owner = & docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' $name
            if ($LASTEXITCODE -ne 0 -or $owner -ne $CreditProject) { throw "Refusing to modify a foreign container named $name" }
        }
    }
    $networks = @(& docker network ls --format '{{.Name}}')
    if ($networks -contains 'credit-risk-dev-network') {
        $owner = & docker network inspect --format '{{index .Labels "com.docker.compose.project"}}' credit-risk-dev-network
        if ($LASTEXITCODE -ne 0 -or $owner -ne $CreditProject) { throw 'The network name belongs to another project.' }
    }
    foreach ($volume in @('credit-risk-mysql-data','credit-risk-redis-data')) {
        $existing = @(& docker volume ls --filter "name=^${volume}$" --format '{{.Name}}')
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect named volume ownership.' }
        if ($existing -contains $volume) {
            $metadata = @((& docker volume inspect $volume) | ConvertFrom-Json)
            if ($LASTEXITCODE -ne 0 -or $metadata.Count -ne 1 -or $metadata[0].Labels.'com.docker.compose.project' -ne $CreditProject) {
                throw "Refusing to use foreign named volume $volume"
            }
        }
    }
}
function Write-CreditText([string]$Path,[string]$Value) {
    [IO.File]::WriteAllText($Path,$Value,[Text.UTF8Encoding]::new($false))
}
function Sync-CreditConfigs($Values) {
    foreach ($key in @('CREDIT_RISK_MYSQL_PORT','CREDIT_RISK_REDIS_PORT','CREDIT_RISK_NAMESRV_PORT','CREDIT_RISK_BROKER_PORT','CREDIT_RISK_BROKER_VIP_PORT','CREDIT_RISK_PROMETHEUS_PORT','CREDIT_RISK_GRAFANA_PORT','CREDIT_RISK_JAVA_PORT')) {
        $creditPort = 0
        if (!$Values.ContainsKey($key) -or ![int]::TryParse($Values[$key],[ref]$creditPort) -or $creditPort -lt 1024 -or $creditPort -gt 65535) { throw "Invalid isolated port: $key" }
    }
    if ([int]$Values['CREDIT_RISK_BROKER_VIP_PORT'] -ne [int]$Values['CREDIT_RISK_BROKER_PORT']-2) { throw 'RocketMQ VIP port must equal broker port minus two.' }
    foreach ($key in @('CREDIT_RISK_MYSQL_PASSWORD','CREDIT_RISK_MYSQL_ROOT_PASSWORD','CREDIT_RISK_REDIS_PASSWORD','CREDIT_RISK_GRAFANA_ADMIN_PASSWORD')) {
        if (!$Values.ContainsKey($key) -or $Values[$key] -match '^replace-' -or $Values[$key].Length -lt 16) { throw "Set a private password in .env: $key" }
    }
    $broker = [IO.File]::ReadAllText((Join-Path $CreditRoot 'rocketmq/broker.conf.template')).Replace('__BROKER_PORT__',$Values['CREDIT_RISK_BROKER_PORT'])
    $prom = [IO.File]::ReadAllText((Join-Path $CreditRoot 'prometheus/prometheus.yml.template')).Replace('__JAVA_PORT__',$Values['CREDIT_RISK_JAVA_PORT'])
    [IO.Directory]::CreateDirectory((Join-Path $CreditRoot 'runtime')) | Out-Null
    Write-CreditText (Join-Path $CreditRoot 'runtime/broker.conf') $broker
    Write-CreditText (Join-Path $CreditRoot 'prometheus/prometheus.yml') $prom
}
