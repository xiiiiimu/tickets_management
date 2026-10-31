# Read-only status; never starts a service.
. (Join-Path $PSScriptRoot '_common.ps1')
$states = @((Get-CreditContainers) | ForEach-Object {
    [pscustomobject]@{ Container=$_.Name.TrimStart('/'); State=$_.State.Status; RestartPolicy=$_.HostConfig.RestartPolicy.Name }
})
if ($states.Count -eq 0) { Write-Host 'No credit-risk-dev containers have been created.' } else { $states | Format-Table -AutoSize }
foreach ($container in @(Get-CreditContainers)) {
    foreach ($mount in @($container.Mounts)) {
        if (($container.Name -eq '/credit-risk-dev-mysql' -and $mount.Destination -eq '/var/lib/mysql') -or
            ($container.Name -eq '/credit-risk-dev-redis' -and $mount.Destination -eq '/data')) {
            [pscustomobject]@{Container=$container.Name.TrimStart('/');Destination=$mount.Destination;Type=$mount.Type;Storage=$(if($mount.Type -eq 'volume'){$mount.Name}else{$mount.Source})} | Format-Table -AutoSize
        }
    }
}
