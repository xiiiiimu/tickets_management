# Manual invocation only. No scheduled tasks or automatic triggers are registered.
. (Join-Path $PSScriptRoot '_common.ps1')
Assert-CreditOwnership
# Changing the Compose mount does not migrate existing data. Never silently recreate a legacy store.
$namedStorageConfigured = [IO.File]::ReadAllText($CreditComposeFile).Contains('credit-risk-mysql-data:/var/lib/mysql')
foreach ($container in @(Get-CreditContainers)) {
    foreach ($mount in @($container.Mounts)) {
        if (($container.Name -eq '/credit-risk-dev-mysql' -and $mount.Destination -eq '/var/lib/mysql') -or
            ($container.Name -eq '/credit-risk-dev-redis' -and $mount.Destination -eq '/data')) {
            if ($namedStorageConfigured -and $mount.Type -ne 'volume') { throw 'Legacy bind-mounted storage detected. Use migrate-to-named-volumes.ps1 after reviewing its dry run; start.ps1 never migrates data.' }
        }
    }
}
$values = Read-CreditEnv
Sync-CreditConfigs $values
Invoke-CreditCompose config --quiet
$occupiedByOurs = @()
foreach ($container in @(Get-CreditContainers)) {
    if ($container.State.Running) {
        foreach ($entry in $container.NetworkSettings.Ports.PSObject.Properties) {
            foreach ($binding in @($entry.Value)) { if ($null -ne $binding) { $occupiedByOurs += [int]$binding.HostPort } }
        }
    }
}
foreach ($key in @('CREDIT_RISK_MYSQL_PORT','CREDIT_RISK_REDIS_PORT','CREDIT_RISK_NAMESRV_PORT','CREDIT_RISK_BROKER_PORT','CREDIT_RISK_BROKER_VIP_PORT','CREDIT_RISK_PROMETHEUS_PORT','CREDIT_RISK_GRAFANA_PORT')) {
    $creditPort = [int]$values[$key]
    if ($occupiedByOurs -contains $creditPort) { continue }
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$creditPort)
    try { $listener.Start() } catch { throw "Port $creditPort is unavailable; no other service will be stopped." } finally { $listener.Stop() }
}
Invoke-CreditCompose up -d --wait --wait-timeout 240
Invoke-CreditCompose exec -T broker sh /home/rocketmq/rocketmq-4.9.7/bin/mqadmin updateTopic -n namesrv:9876 -c credit-risk-dev-cluster -t ticket-events -r 8 -w 8
foreach ($container in @(Get-CreditContainers)) {
    if ($container.HostConfig.RestartPolicy.Name -ne 'no') { throw 'Unexpected restart policy; inspect the isolated environment.' }
}
Write-Host 'credit-risk-dev is running. Services start only when you run start.ps1.'
& (Join-Path $PSScriptRoot 'status.ps1')
