# Manual stop, scoped to this one Compose project; containers and data are retained.
. (Join-Path $PSScriptRoot '_common.ps1')
Assert-CreditOwnership
# The official 4.9.7 launch scripts leave a shell as PID 1; use its own shutdown utility first.
foreach ($service in @('broker','namesrv')) {
    $owned = @((Get-CreditContainers) | Where-Object { $_.Name -eq ('/credit-risk-dev-'+$service) -and $_.State.Running })
    if ($owned.Count -gt 0) {
        try {
            Invoke-CreditCompose exec -T $service sh /home/rocketmq/rocketmq-4.9.7/bin/mqshutdown $service
            $deadline = [DateTime]::UtcNow.AddSeconds(20)
            do {
                $running = & docker inspect --format '{{.State.Running}}' ('credit-risk-dev-'+$service)
                if ($running -eq 'false') { break }
                Start-Sleep -Milliseconds 500
            } while ([DateTime]::UtcNow -lt $deadline)
        } catch { Write-Warning 'Graceful RocketMQ shutdown did not complete; the scoped Compose stop still runs.' }
    }
}
Invoke-CreditCompose stop
$running = @((Get-CreditContainers) | Where-Object { $_.State.Running })
if ($running.Count -gt 0) { throw 'Some credit-risk-dev containers are still running.' }
Write-Host 'credit-risk-dev stopped. Containers, network and persistent data are retained.'
& (Join-Path $PSScriptRoot 'status.ps1')
