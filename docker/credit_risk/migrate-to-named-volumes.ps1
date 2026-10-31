#requires -Version 7.0
[CmdletBinding()]
param([switch]$Execute,[switch]$FreshStart,[switch]$WritersQuiesced,[ValidateRange(30,3600)][int]$DatabaseTimeoutSeconds=600)
$ErrorActionPreference='Stop'
$steps=@(
 'Verify ownership, running legacy bind mounts, fresh target volumes, and manually quiesced application writers.',
 'Dump ALL MySQL databases (--single-transaction --routines --triggers --events); capture exact table counts before/after and SHA-256.',
 'Wait for Redis BGSAVE; copy RDB; record DBSIZE for every configured Redis DB and persistence settings.',
 'Call stop.ps1; start only MySQL/Redis using the new Compose file and wait for health.',
 'Import all-database dump; compare every table/count; stop on any difference.',
 'Stop Redis; copy RDB into the NEW volume and seed its Redis 7 multipart AOF base/manifest without changing appendonly/appendfsync.',
 'Start Redis; compare every DB size and persistence settings; BGSAVE/BGREWRITEAOF; verify a further Redis restart.',
 'Start remaining project services; write migration report and rollback instructions. Old data directories remain untouched.'
)
if($FreshStart){$steps=@(
 'FRESH START: no MySQL dump or Redis restore; the new environment contains fresh seed data only.',
 'Verify project/service ownership of the two exact MySQL/Redis containers; require BOTH target volumes to be absent.',
 'Validate new Compose/initdb mounts and derive the complete schema/column/index contract from the existing schema.sql.',
 'Save a rollback Compose copy; call stop.ps1; recheck ownership and volume absence.',
 'Remove ONLY the stopped, verified IDs of credit-risk-dev-mysql and credit-risk-dev-redis, without force or volume removal.',
 'Create and verify fresh run-owned volumes; start only mysql/redis and wait for health.',
 'Verify volume names/mount types, all schema tables/columns/declared indexes, deadline precision and default protocol/settings.',
 'Verify tb_ticket_sale_assembly is queryable; initialize only the fresh coordinator row with default protocol 2 and all mode flags OFF.',
 'Start the four remaining project services without recreating them; save the report. Keep old ./data intact for rollback.'
)}
if(-not $Execute){$steps | ForEach-Object {Write-Output $_};Write-Output 'DRY RUN ONLY. No Docker/DB commands executed. Execution requires -Execute -WritersQuiesced (plus -FreshStart for a new empty environment) after stopping Java, Python, test clients and other writers.';return}
if(-not $WritersQuiesced){throw 'Stop all application/test writers first, then explicitly supply -WritersQuiesced. The script cannot make external writers quiescent.'}
. (Join-Path $PSScriptRoot '_common.ps1')
$values=Read-CreditEnv
foreach($key in @('CREDIT_RISK_MYSQL_ROOT_PASSWORD','CREDIT_RISK_REDIS_PASSWORD')){
 if(-not $values.ContainsKey($key) -or $values[$key] -match "[\r\n`0]"){throw "Invalid private .env value: $key"}
}
$run=[guid]::NewGuid().ToString('N')
$backup=Join-Path $CreditRoot ('backup/'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+$run.Substring(0,8))
$report=@{run=$run;success=$false;phase='preflight';old_directories_retained=$true;rollback='Stop this project; restore compose.bind.rollback.yaml to docker/credit_risk/compose.yaml, then use scoped docker compose up. Never down -v. Old bind directories are retained. New writes after migration are not present in the old copy.'}
$report.mode=if($FreshStart){'fresh-start'}else{'migrate'}
$switched=$false
function Save-Report {
 if(Test-Path -LiteralPath $backup){$report | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $backup 'migration-report.json') -Encoding utf8}
}
function Invoke-MigrationDocker {
 param([string[]]$DockerArguments,[string]$SecretLine='',[string]$TextInput='',[string]$InputFile='',[string]$OutputFile='',[int]$TimeoutSeconds=60)
 # Secrets go over stdin, never docker CLI arguments, shell interpolation or console output.
 $info=[Diagnostics.ProcessStartInfo]::new('docker');$info.UseShellExecute=$false;$info.CreateNoWindow=$true
 $info.RedirectStandardInput=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
 $info.StandardInputEncoding=[Text.UTF8Encoding]::new($false)
 foreach($item in $DockerArguments){$info.ArgumentList.Add($item)}
 $proc=[Diagnostics.Process]::new();$proc.StartInfo=$info
 $clock=[Diagnostics.Stopwatch]::StartNew();$sink=$null;$source=$null;$started=$false
 try {
  if(-not $proc.Start()){throw 'Cannot start Docker command'}
  $started=$true
  $errors=$proc.StandardError.ReadToEndAsync()
  if($OutputFile){$sink=[IO.File]::Create($OutputFile);$receive=$proc.StandardOutput.BaseStream.CopyToAsync($sink)}else{$receive=$proc.StandardOutput.ReadToEndAsync()}
  # Explicit LF: Windows WriteLine's CRLF would leave a trailing CR in the Linux password.
  if($SecretLine){$proc.StandardInput.Write($SecretLine+"`n");$proc.StandardInput.Flush()}
  if($InputFile){$source=[IO.File]::OpenRead($InputFile);$send=$source.CopyToAsync($proc.StandardInput.BaseStream)}else{$send=$proc.StandardInput.WriteAsync($TextInput)}
  if(-not $send.Wait([TimeSpan]::FromSeconds([Math]::Max(1,$TimeoutSeconds-$clock.Elapsed.TotalSeconds)))){throw 'Docker stdin timed out'}
  $proc.StandardInput.Close()
  if(-not $proc.WaitForExit([int]([Math]::Max(1,$TimeoutSeconds-$clock.Elapsed.TotalSeconds)*1000))){throw 'Docker phase timed out'}
  if(-not $receive.Wait([TimeSpan]::FromSeconds(5)) -or -not $errors.Wait([TimeSpan]::FromSeconds(5))){throw 'Docker output did not close'}
  if($proc.ExitCode -ne 0){throw ('Docker phase failed; exit code '+$proc.ExitCode+' (stderr withheld to protect credentials)')}
  if($OutputFile){return ''};return $receive.Result.Trim()
 } finally {
  if($started -and -not $proc.HasExited){$proc.Kill($true);$proc.WaitForExit()}
  if($null -ne $source){$source.Dispose()};if($null -ne $sink){$sink.Dispose()};$proc.Dispose()
 }
}
function Mysql([string]$Sql){
 Invoke-MigrationDocker -DockerArguments @('exec','-i','credit-risk-dev-mysql','sh','-ec','IFS= read -r MYSQL_PWD; export MYSQL_PWD; exec mysql --protocol=TCP -h127.0.0.1 -uroot --batch --raw --skip-column-names --connect-timeout=5') -SecretLine $values['CREDIT_RISK_MYSQL_ROOT_PASSWORD'] -TextInput ($Sql+"`n") -TimeoutSeconds $DatabaseTimeoutSeconds
}
function Redis([string[]]$Command){
 Invoke-MigrationDocker -DockerArguments (@('exec','-i','credit-risk-dev-redis','sh','-ec','IFS= read -r REDISCLI_AUTH; export REDISCLI_AUTH; exec redis-cli --no-auth-warning --raw -e "$@"','migration')+$Command) -SecretLine $values['CREDIT_RISK_REDIS_PASSWORD']
}
function Get-TableCounts {
 $sql=@'
SELECT CONCAT('SELECT ''',HEX(TABLE_SCHEMA),''',''',HEX(TABLE_NAME),''',COUNT(*) FROM `',REPLACE(TABLE_SCHEMA,'`','``'),'`.`',REPLACE(TABLE_NAME,'`','``'),'`;')
FROM information_schema.TABLES WHERE TABLE_TYPE='BASE TABLE' AND TABLE_SCHEMA NOT IN ('information_schema','performance_schema','sys') ORDER BY TABLE_SCHEMA,TABLE_NAME;
'@
 $statements=Mysql $sql
 if(-not $statements){throw 'No persistent tables found; refusing empty migration'}
 @((Mysql $statements) -split "`n" | ForEach-Object {$_.TrimEnd("`r")} | Sort-Object)
}
function Compare-Counts($Before,$After,[string]$Name){
 $difference=@(Compare-Object -ReferenceObject @($Before) -DifferenceObject @($After))
 if($difference.Count){$difference | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $backup ($Name+'-mismatch.json')) -Encoding utf8;throw "$Name counts differ; migration stopped, no automatic retry"}
}
function Get-RedisSizes([int]$Databases){
 $result=@();for($index=0;$index -lt $Databases;$index++){$size=Redis -Command @('-n',"$index",'DBSIZE');if($size -notmatch '^\d+$'){throw 'Invalid DBSIZE response'};$result+=($index.ToString()+':'+$size)};return $result
}
function Wait-RedisPersistence([string]$Operation){
 $reply=Redis -Command @($Operation);if($reply -notmatch 'started|scheduled'){throw "$Operation did not start"}
 $deadline=[DateTime]::UtcNow.AddSeconds(180)
 do {
  Start-Sleep -Milliseconds 300;$info=Redis -Command @('INFO','persistence')
  if($Operation -eq 'BGSAVE'){$busy=$info -match '(?m)^rdb_bgsave_in_progress:1\r?$';$ok=$info -match '(?m)^rdb_last_bgsave_status:ok\r?$'}
  else {$busy=$info -match '(?m)^aof_rewrite_(in_progress|scheduled):1\r?$';$ok=$info -match '(?m)^aof_last_bgrewrite_status:ok\r?$'}
  if(-not $busy){if(-not $ok){throw "$Operation persistence failed"};return}
 }while([DateTime]::UtcNow -lt $deadline)
 throw "$Operation exceeded bounded 180 second wait"
}
function Wait-Healthy([string]$Name){
 $deadline=[DateTime]::UtcNow.AddSeconds(300)
 do {
  $state=(Invoke-MigrationDocker -DockerArguments @('inspect','--format','{{json .State}}',$Name)) | ConvertFrom-Json
  if(-not $state.Running){throw "$Name exited during migration"}
  if($state.Health.Status -eq 'healthy'){return}
  Start-Sleep -Seconds 1
 }while([DateTime]::UtcNow -lt $deadline)
 throw "$Name health check exceeded 300 seconds"
}
function Assert-NamedMount([string]$Service,[string]$Destination,[string]$Volume){
 $container=@((Get-CreditContainers) | Where-Object {$_.Name -eq ('/credit-risk-dev-'+$Service)})
 $mount=@($container[0].Mounts | Where-Object {$_.Destination -eq $Destination})
 if($mount.Count -ne 1 -or $mount[0].Type -ne 'volume' -or $mount[0].Name -ne $Volume){throw 'Unexpected target storage; refusing restore'}
}
function Assert-FreshTargetVolumesAbsent {
 foreach($volume in @('credit-risk-mysql-data','credit-risk-redis-data')){
  $found=Invoke-MigrationDocker -DockerArguments @('volume','ls','--filter',"name=^${volume}$",'--format','{{.Name}}')
  if($found){throw "FreshStart target volume already exists: $volume. No overwrite, deletion or reuse is permitted."}
 }
}
function Get-FreshOwnedTargets {
 $containers=@(Get-CreditContainers)
 foreach($service in @('mysql','redis')){
  $name='/credit-risk-dev-'+$service
  $targets=@($containers | Where-Object {$_.Name -eq $name})
  if($targets.Count -ne 1){throw "FreshStart requires the existing owned container $name; no guessed or wildcard removal"}
  $target=$targets[0]
  if($target.Config.Labels.'com.docker.compose.project' -ne 'credit-risk-dev' -or
     $target.Config.Labels.'com.docker.compose.service' -ne $service -or $target.Id -notmatch '^[a-f0-9]{64}$'){
   throw "FreshStart ownership/service identity mismatch: $name"
  }
  $target
 }
}
function Split-FreshDdlItems([string]$Definition){
 # Split only top-level commas: DECIMAL, compound keys, CHECKs and quoted strings stay intact.
 $depth=0;$quote=[char]0;$begin=0
 for($position=0;$position -lt $Definition.Length;$position++){
  $character=$Definition[$position]
  if($quote -ne [char]0){
   if($character -eq $quote){
    if($position+1 -lt $Definition.Length -and $Definition[$position+1] -eq $quote){$position++}else{$quote=[char]0}
   }elseif($character -eq [char]92 -and $quote -ne [char]96){$position++}
   continue
  }
  if($character -in @([char]39,[char]34,[char]96)){$quote=$character;continue}
  if($character -eq '('){$depth++}elseif($character -eq ')'){$depth--}
  elseif($character -eq ',' -and $depth -eq 0){$Definition.Substring($begin,$position-$begin).Trim();$begin=$position+1}
 }
 if($depth -ne 0 -or $quote -ne [char]0){throw 'Unsupported/unbalanced fresh schema definition; refusing partial validation'}
 $Definition.Substring($begin).Trim()
}
function Get-FreshSchemaContract([string]$SchemaPath){
 $text=[IO.File]::ReadAllText($SchemaPath)
 $text=[regex]::Replace($text,'(?m)^\s*--[^\r\n]*','')
 $ddl=[regex]::Matches($text,'(?is)CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS\s+`?(?<table>tb_\w+)`?\s*\((?<body>.*?)\)\s*(?:ENGINE[^;]*)?;')
 $createCount=[regex]::Matches($text,'(?i)CREATE\s+TABLE\b').Count
 if($ddl.Count -eq 0 -or $ddl.Count -ne $createCount){throw 'Cannot derive every table from schema.sql; refusing incomplete fresh initialization'}
 $tables=@();$columns=@();$indexes=@()
 foreach($tableMatch in $ddl){
  $table=$tableMatch.Groups['table'].Value;$tables+=$table
  foreach($item in @(Split-FreshDdlItems $tableMatch.Groups['body'].Value)){
   if($item -match '^PRIMARY\s+KEY\s*\((?<cols>[^)]+)\)'){
    $indexes+=@{table=$table;name='PRIMARY';columns=($Matches['cols'] -replace '[`\s]','');non_unique=0};continue
   }
   if($item -match '^(?<unique>UNIQUE\s+)?KEY\s+`?(?<name>\w+)`?\s*\((?<cols>[^)]+)\)'){
    $indexes+=@{table=$table;name=$Matches['name'];columns=($Matches['cols'] -replace '[`\s]','');non_unique=$(if($Matches['unique']){0}else{1})};continue
   }
   if($item -match '^(FOREIGN|CHECK|CONSTRAINT)\b'){continue}
   if($item -notmatch '^`?(?<column>\w+)`?\s+(?<type>\w+)(?:\((?<size>[^)]+)\))?(?<tail>.*)$'){throw "Unrecognized schema item in $table"}
   $column=$Matches['column'];$type=$Matches['type'].ToLowerInvariant();$size=$Matches['size'];$tail=$Matches['tail']
   if($type -eq 'boolean'){$type='tinyint'}
   if($type -eq 'integer'){$type='int'}
   $precision=if($type -in @('timestamp','datetime')){if($size){[int]$size}else{0}}else{$null}
   $columns+=@{table=$table;column=$column;type=$type;precision=$precision}
   if($tail -match '\bPRIMARY\s+KEY\b'){$indexes+=@{table=$table;name='PRIMARY';columns=$column;non_unique=0}}
   elseif($tail -match '\bUNIQUE\b'){$indexes+=@{table=$table;name=$null;columns=$column;non_unique=0}}
  }
 }
 return @{tables=$tables;columns=$columns;indexes=$indexes;sha256=(Get-FileHash -LiteralPath $SchemaPath -Algorithm SHA256).Hash}
}
function Invoke-FreshSql([string]$Sql){
 $database=$values['CREDIT_RISK_MYSQL_DATABASE']
 if($database -notmatch '^credit_risk_[A-Za-z0-9_]{1,51}$'){throw 'FreshStart requires an explicit credit_risk_ database name from .env'}
 Mysql ('USE `'+$database+'`; '+$Sql)
}
function Assert-FreshDatabase($Contract){
 $actualTables=@((Invoke-FreshSql "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;") -split "`r?`n")
 if(@(Compare-Object @($Contract.tables | Sort-Object) @($actualTables | Sort-Object)).Count){throw 'Fresh database table set differs from complete schema.sql'}
 $actualColumns=@{}
 foreach($line in @((Invoke-FreshSql "SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE,COALESCE(DATETIME_PRECISION,-1),COALESCE(COLUMN_DEFAULT,'<NULL>') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE();") -split "`r?`n")){
  $fields=$line -split "`t";if($fields.Count -ne 5){throw 'Invalid fresh column metadata'}
  $actualColumns[$fields[0]+'.'+$fields[1]]=@{type=$fields[2];precision=[int]$fields[3];default=$fields[4]}
 }
 if($actualColumns.Count -ne $Contract.columns.Count){throw 'Fresh column count differs from schema.sql'}
 foreach($column in $Contract.columns){
  $key=$column.table+'.'+$column.column
  if(-not $actualColumns.ContainsKey($key) -or $actualColumns[$key].type -ne $column.type){throw "Missing/wrong column type: $key"}
  if($null -ne $column.precision -and $actualColumns[$key].precision -ne $column.precision){throw "Wrong millisecond deadline/timestamp precision: $key"}
 }
 $actualIndexes=@()
 $sql="SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ','),MIN(IS_VISIBLE) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() GROUP BY TABLE_NAME,INDEX_NAME,NON_UNIQUE;"
 foreach($line in @((Invoke-FreshSql $sql) -split "`r?`n")){
  $fields=$line -split "`t";if($fields.Count -ne 5){throw 'Invalid fresh index metadata'}
  $actualIndexes+=@{table=$fields[0];name=$fields[1];non_unique=[int]$fields[2];columns=$fields[3];visible=$fields[4]}
 }
 foreach($index in $Contract.indexes){
  $found=@($actualIndexes | Where-Object {$_.table -eq $index.table -and $_.columns -eq $index.columns -and $_.non_unique -eq $index.non_unique -and $_.visible -eq 'YES' -and (-not $index.name -or $_.name -eq $index.name)})
  if(-not $found.Count){throw ('Missing/wrong fresh index: '+$index.table+'/'+$index.columns)}
 }
 foreach($pair in @(@('tb_reservation_dispatch.runtime_protocol','2'),@('tb_reservation_dispatch.ordered_lua_enabled','0'),@('tb_reservation_dispatch.quick_review_enabled','0'),@('tb_reservation_dispatch.vector_inventory_enabled','0'),@('tb_reservation_job.attempt_protocol','2'),@('tb_reservation_job.lua_turn_finished','0'),@('tb_ticket_sku.single_purchase_limit','100'))){
  if(-not $actualColumns.ContainsKey($pair[0]) -or $actualColumns[$pair[0]].default -ne $pair[1]){throw ('Unexpected fresh protocol default: '+$pair[0])}
 }
 foreach($table in @('tb_reservation_dispatch','tb_reservation_job','tb_reserve_request','tb_reservation','tb_ticket_order','tb_outbox_message','tb_stock_bucket_allocation','tb_stock_bucket_allocation_item','tb_reservation_sku_order','tb_ticket_sale_assembly','tb_ticket_sale_version')){
  if((Invoke-FreshSql ('SELECT COUNT(*) FROM `'+$table+'`;')) -ne '0'){throw "FreshStart found unexpected existing work in $table; no overwrite permitted"}
 }
 # Same defaults as ReservationDispatch.initialize; never activate protocol 3 or enabled mode bits.
 $protocol=Invoke-FreshSql @'
INSERT INTO tb_reservation_dispatch(id,pass_run,pass_weight,capacity,active_limit,review_age_ms,runtime_protocol,ordered_lua_enabled,quick_review_enabled,vector_inventory_enabled) VALUES(1,0,2,512,4,2000,2,0,0,0);
SELECT runtime_protocol,ordered_lua_enabled,quick_review_enabled,vector_inventory_enabled,capacity,active_limit,pass_weight,review_age_ms FROM tb_reservation_dispatch WHERE id=1;
'@
 if($protocol -ne "2`t0`t0`t0`t512`t4`t2`t2000"){throw 'Fresh coordinator protocol/default settings validation failed'}
 if((Invoke-FreshSql 'SELECT COUNT(*) FROM tb_ticket_sale_assembly;') -ne '0'){throw 'Fresh Armory table verification failed'}
 $report.fresh_database=@{tables=$actualTables.Count;columns=$actualColumns.Count;declared_indexes=$Contract.indexes.Count;schema_sha256=$Contract.sha256;runtime_protocol=2;ordered_lua_enabled=0;quick_review_enabled=0;vector_inventory_enabled=0;capacity=512;active_limit=4;armory_query_verified=$true;durable_attempts_activated=$false}
}
function Invoke-FreshStart {
 $report.phase='fresh-preflight';Save-Report
 Assert-CreditOwnership
 Assert-FreshTargetVolumesAbsent
 if($values['CREDIT_RISK_MYSQL_DATABASE'] -notmatch '^credit_risk_[A-Za-z0-9_]{1,51}$'){throw 'FreshStart requires an explicit credit_risk_ database name in .env before stopping anything'}
 $targets=@(Get-FreshOwnedTargets)
 $composeArgs=@('compose','--env-file',$CreditEnvFile,'-p',$CreditProject,'-f',$CreditComposeFile,'config','--format','json')
 $config=(Invoke-MigrationDocker -DockerArguments $composeArgs) | ConvertFrom-Json
 if($config.name -ne 'credit-risk-dev'){throw 'Unexpected Compose project'}
 if($config.services.mysql.environment.MYSQL_DATABASE -ne $values['CREDIT_RISK_MYSQL_DATABASE']){throw 'Compose database differs from private .env; refusing FreshStart'}
 foreach($spec in @(@('mysql','/var/lib/mysql','credit-risk-mysql-data'),@('redis','/data','credit-risk-redis-data'))){
  $service=$config.services.($spec[0]);$mount=@($service.volumes | Where-Object {$_.target -eq $spec[1]})
  if($service.container_name -ne ('credit-risk-dev-'+$spec[0]) -or $service.restart -ne 'no' -or $mount.Count -ne 1 -or $mount[0].type -ne 'volume' -or $mount[0].source -ne $spec[2] -or $config.volumes.($spec[2]).name -ne $spec[2]){throw 'FreshStart Compose storage/identity mismatch'}
 }
 $schemaPath=[IO.Path]::GetFullPath((Join-Path $CreditRoot '../../ticket-risk-platform/src/main/resources/schema.sql'))
 $dataPath=[IO.Path]::GetFullPath((Join-Path $CreditRoot '../../ticket-risk-platform/src/main/resources/data.sql'))
 foreach($spec in @(@($schemaPath,'/docker-entrypoint-initdb.d/01-schema.sql'),@($dataPath,'/docker-entrypoint-initdb.d/02-data.sql'))){
  $mount=@($config.services.mysql.volumes | Where-Object {$_.target -eq $spec[1]})
  if($mount.Count -ne 1 -or $mount[0].type -ne 'bind' -or -not $mount[0].read_only -or [IO.Path]::GetFullPath($mount[0].source) -ne $spec[0] -or -not (Test-Path -LiteralPath $spec[0] -PathType Leaf)){throw 'FreshStart requires the unchanged, readonly project initdb files'}
 }
 $contract=Get-FreshSchemaContract $schemaPath
 $dataHash=(Get-FileHash -LiteralPath $dataPath -Algorithm SHA256).Hash
 Copy-Item -LiteralPath $CreditComposeFile -Destination (Join-Path $backup 'compose.named.yaml')
 $rollback=[IO.File]::ReadAllText($CreditComposeFile).Replace('credit-risk-mysql-data:/var/lib/mysql','./data/mysql:/var/lib/mysql').Replace('credit-risk-redis-data:/data','./data/redis:/data')
 Write-CreditText (Join-Path $backup 'compose.bind.rollback.yaml') $rollback
 $contract | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $backup 'fresh-schema-contract.json') -Encoding utf8
 $report.source_data_imported=$false;$report.removal_allowlist=@('credit-risk-dev-mysql','credit-risk-dev-redis');$report.removed_containers=@()
 $report.phase='fresh-stop';Save-Report
 $script:switched=$true
 & (Join-Path $CreditRoot 'stop.ps1')
 Assert-FreshTargetVolumesAbsent
 foreach($target in $targets){
  # Recheck immutable ID, exact name and both owner labels immediately before a non-force rm.
  $current=@((Invoke-MigrationDocker -DockerArguments @('inspect',$target.Id)) | ConvertFrom-Json)
  if($current.Count -ne 1 -or $current[0].Id -ne $target.Id -or $current[0].Name -ne $target.Name -or $current[0].Config.Labels.'com.docker.compose.project' -ne 'credit-risk-dev' -or $current[0].Config.Labels.'com.docker.compose.service' -ne $target.Config.Labels.'com.docker.compose.service' -or $current[0].State.Running){throw 'Owned stopped container identity changed; refusing removal'}
  Invoke-MigrationDocker -DockerArguments @('rm',$target.Id) | Out-Null
  $report.removed_containers+=@{name=$target.Name.TrimStart('/');id=$target.Id};Save-Report
 }
 $report.phase='fresh-volumes';Save-Report
 foreach($volume in @('credit-risk-mysql-data','credit-risk-redis-data')){
  Invoke-MigrationDocker -DockerArguments @('volume','create','--driver','local','--label','com.docker.compose.project=credit-risk-dev','--label',('com.docker.compose.volume='+$volume),'--label','credit-risk.environment=manual-development','--label',('credit-risk.migration-owner='+$run),$volume) | Out-Null
  $metadata=@((Invoke-MigrationDocker -DockerArguments @('volume','inspect',$volume)) | ConvertFrom-Json)[0]
  if($metadata.Labels.'credit-risk.migration-owner' -ne $run -or $metadata.Driver -ne 'local' -or $metadata.Options){throw 'Fresh volume creation race/ownership mismatch; refusing reuse'}
 }
 $report.phase='fresh-initialize';Save-Report
 Invoke-CreditCompose up -d --no-deps mysql redis
 Wait-Healthy 'credit-risk-dev-mysql';Wait-Healthy 'credit-risk-dev-redis'
 $newTargets=@(Get-FreshOwnedTargets)
 $report.fresh_containers=@($newTargets | ForEach-Object {@{name=$_.Name.TrimStart('/');id=$_.Id;restart=$_.HostConfig.RestartPolicy.Name}})
 if(@($newTargets | Where-Object {$_.HostConfig.RestartPolicy.Name -ne 'no'}).Count){throw 'Unexpected fresh container restart policy'}
 Assert-NamedMount 'mysql' '/var/lib/mysql' 'credit-risk-mysql-data';Assert-NamedMount 'redis' '/data' 'credit-risk-redis-data'
 if((Get-FileHash -LiteralPath $schemaPath -Algorithm SHA256).Hash -ne $contract.sha256 -or (Get-FileHash -LiteralPath $dataPath -Algorithm SHA256).Hash -ne $dataHash){throw 'Initdb files changed during FreshStart; refusing success'}
 Assert-FreshDatabase $contract
 if((Redis -Command @('PING')) -ne 'PONG'){throw 'Fresh Redis PING failed'}
 if((Redis -Command @('INFO','keyspace')) -match '(?m)^db\d+:keys=[1-9]'){throw 'Fresh Redis contains unexpected keys; keep application writers stopped'}
 $report.fresh_redis_empty=$true
 $report.fresh_database.data_sha256=$dataHash
 $report.phase='fresh-start-other-services';Save-Report
 Invoke-CreditCompose up -d --no-deps --no-recreate --wait --wait-timeout 300 namesrv broker prometheus grafana
 $report.phase='complete';$report.success=$true;$report.completed_at=(Get-Date).ToString('o');Save-Report
 Write-Output ('FreshStart verified (new seed data, protocol 2; attempt features remain OFF). Report: '+(Join-Path $backup 'migration-report.json'))
}
New-Item -ItemType Directory -Path $backup | Out-Null
Save-Report
try {
 if($FreshStart){Invoke-FreshStart;return}
 Assert-CreditOwnership
 $containers=@(Get-CreditContainers)
 foreach($spec in @(@('mysql','/var/lib/mysql','mysql'),@('redis','/data','redis'))){
  $container=@($containers | Where-Object {$_.Name -eq ('/credit-risk-dev-'+$spec[0])})
  if($container.Count -ne 1 -or -not $container[0].State.Running){throw 'Both existing MySQL and Redis must be running'}
  $mount=@($container[0].Mounts | Where-Object {$_.Destination -eq $spec[1]})
  $expected=(Join-Path $CreditRoot ('data/'+$spec[2])).Replace('\','/').ToLowerInvariant()
  $actual=if($mount.Count -eq 1){$mount[0].Source.Replace('\','/').TrimEnd('/').ToLowerInvariant()}else{''}
  $linuxSuffix='/'+$expected.Substring(0,1)+$expected.Substring(2)
  if($mount.Count -ne 1 -or $mount[0].Type -ne 'bind' -or ($actual -ne $expected -and -not $actual.EndsWith($linuxSuffix))){throw 'Expected this project legacy bind mount; no data will be guessed or overwritten'}
 }
 foreach($volume in @('credit-risk-mysql-data','credit-risk-redis-data')){
  $found=Invoke-MigrationDocker -DockerArguments @('volume','ls','--filter',"name=^${volume}$",'--format','{{.Name}}')
  if($found){throw 'Target named volume already exists; inspect it manually. Migration never overwrites or removes an existing volume.'}
 }
 Invoke-CreditCompose config --quiet
 # Runtime writers cannot be safely fenced by a filesystem migration alone.
 if((Mysql 'SELECT @@event_scheduler,@@general_log,@@slow_query_log;') -notmatch '^(OFF|DISABLED)\t0\t0$'){throw 'Event scheduler/general/slow logs must already be inactive for stable exact counts; script will not change them'}
 Copy-Item -LiteralPath $CreditComposeFile -Destination (Join-Path $backup 'compose.named.yaml')
 $rollback=[IO.File]::ReadAllText($CreditComposeFile).Replace('credit-risk-mysql-data:/var/lib/mysql','./data/mysql:/var/lib/mysql').Replace('credit-risk-redis-data:/data','./data/redis:/data')
 Write-CreditText (Join-Path $backup 'compose.bind.rollback.yaml') $rollback
 $report.phase='backup-mysql';Save-Report
 $report.mysql_durability=Mysql 'SELECT @@innodb_flush_log_at_trx_commit,@@sync_binlog;'
 $before=@(Get-TableCounts);$before | Set-Content -LiteralPath (Join-Path $backup 'mysql-counts-before.tsv') -Encoding utf8
 $dump=Join-Path $backup 'all-databases.sql'
 Invoke-MigrationDocker -DockerArguments @('exec','-i','credit-risk-dev-mysql','sh','-ec','IFS= read -r MYSQL_PWD; export MYSQL_PWD; exec mysqldump --protocol=TCP -h127.0.0.1 -uroot --all-databases --single-transaction --routines --triggers --events --hex-blob --set-gtid-purged=OFF --no-tablespaces --column-statistics=0') -SecretLine $values['CREDIT_RISK_MYSQL_ROOT_PASSWORD'] -OutputFile $dump -TimeoutSeconds $DatabaseTimeoutSeconds | Out-Null
 Compare-Counts $before @(Get-TableCounts) 'source-mysql'
 if((Get-Item -LiteralPath $dump).Length -eq 0){throw 'Dump is empty'}
 $report.mysql_dump_sha256=(Get-FileHash -LiteralPath $dump -Algorithm SHA256).Hash
 $report.phase='backup-redis';Save-Report
 foreach($pair in @(@('dir','/data'),@('dbfilename','dump.rdb'),@('appendfilename','appendonly.aof'),@('appenddirname','appendonlydir'),@('appendonly','yes'),@('appendfsync','everysec'))){
  $actual=(Redis -Command @('CONFIG','GET',$pair[0])) -split "`r?`n"
  if($actual.Count -ne 2 -or $actual[1] -ne $pair[1]){throw ('Unexpected Redis setting '+$pair[0]+'; do not guess restore layout')}
 }
 $databaseSetting=(Redis -Command @('CONFIG','GET','databases')) -split "`r?`n";$databases=[int]$databaseSetting[1]
 if($databases -lt 1 -or $databases -gt 1024){throw 'Unexpected Redis database count'}
 Wait-RedisPersistence 'BGSAVE'
 $sizes=@(Get-RedisSizes $databases)
 Invoke-MigrationDocker -DockerArguments @('cp','credit-risk-dev-redis:/data/dump.rdb',(Join-Path $backup 'dump.rdb')) | Out-Null
 Compare-Counts $sizes @(Get-RedisSizes $databases) 'source-redis'
 $sizes | Set-Content -LiteralPath (Join-Path $backup 'redis-dbsize-before.txt') -Encoding utf8
 $report.redis_rdb_sha256=(Get-FileHash -LiteralPath (Join-Path $backup 'dump.rdb') -Algorithm SHA256).Hash
 @{ 'all-databases.sql'=$report.mysql_dump_sha256; 'dump.rdb'=$report.redis_rdb_sha256 } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $backup 'sha256.json') -Encoding utf8
 $report.redis_databases=$databases;$report.table_counts=$before;$report.redis_dbsize=$sizes
 Save-Report
 # No production client should restart until this report says success=true.
 & (Join-Path $CreditRoot 'stop.ps1')
 $switched=$true;$report.phase='restore-mysql';Save-Report
 # Atomically create then verify a run-specific label BEFORE any database can mount a volume.
 # docker volume create may return an already-existing name; the token check rejects that race.
 foreach($volume in @('credit-risk-mysql-data','credit-risk-redis-data')){
  Invoke-MigrationDocker -DockerArguments @('volume','create','--driver','local','--label','com.docker.compose.project=credit-risk-dev','--label',('com.docker.compose.volume='+$volume),'--label','credit-risk.environment=manual-development','--label',('credit-risk.migration-owner='+$run),$volume) | Out-Null
  $metadata=@((Invoke-MigrationDocker -DockerArguments @('volume','inspect',$volume)) | ConvertFrom-Json)[0]
  if($metadata.Labels.'credit-risk.migration-owner' -ne $run -or $metadata.Driver -ne 'local' -or $metadata.Options){throw 'Fresh volume ownership/driver mismatch; no database will be started'}
 }
 Invoke-CreditCompose up -d --no-deps mysql redis
 Wait-Healthy 'credit-risk-dev-mysql';Wait-Healthy 'credit-risk-dev-redis'
 Assert-NamedMount 'mysql' '/var/lib/mysql' 'credit-risk-mysql-data';Assert-NamedMount 'redis' '/data' 'credit-risk-redis-data'
 Invoke-MigrationDocker -DockerArguments @('exec','-i','credit-risk-dev-mysql','sh','-ec','IFS= read -r MYSQL_PWD; export MYSQL_PWD; exec mysql --protocol=TCP -h127.0.0.1 -uroot --connect-timeout=5') -SecretLine $values['CREDIT_RISK_MYSQL_ROOT_PASSWORD'] -InputFile $dump -TimeoutSeconds $DatabaseTimeoutSeconds | Out-Null
 $after=@(Get-TableCounts);$after | Set-Content -LiteralPath (Join-Path $backup 'mysql-counts-after.tsv') -Encoding utf8
 Compare-Counts $before $after 'restored-mysql'
 if((Mysql 'SELECT @@innodb_flush_log_at_trx_commit,@@sync_binlog;') -ne $report.mysql_durability){throw 'MySQL durability values differ; script will not change them automatically'}
 $report.mysql_counts_match=$true;$report.phase='restore-redis';Save-Report
 $targetDatabaseSetting=(Redis -Command @('CONFIG','GET','databases')) -split "`r?`n"
 if([int]$targetDatabaseSetting[1] -ne $databases){throw 'Target Redis database count differs; do not change config automatically'}
 Invoke-CreditCompose stop redis
 # Redis 7 AOF-enabled startup loads AOF, not plain dump.rdb. Seed an RDB-format BASE + manifest.
 # Source: redis/redis 7.2 src/aof.c manifest format and src/server.c loadDataFromDisk.
 $staging=Join-Path $backup 'redis-restore/appendonlydir';New-Item -ItemType Directory -Path $staging -Force | Out-Null
 Copy-Item -LiteralPath (Join-Path $backup 'dump.rdb') -Destination (Join-Path $staging 'appendonly.aof.1.base.rdb')
 Write-CreditText (Join-Path $staging 'appendonly.aof.manifest') "file appendonly.aof.1.base.rdb seq 1 type b`n"
 $helper='credit-risk-migration-'+$run
 # Only this run's fresh named volume is writable. The old host data is never mounted in this helper.
 $prepare='set -eu; test ! -e /data/migration-initial-aof; if test -d /data/appendonlydir; then mv /data/appendonlydir /data/migration-initial-aof; fi; test ! -e /data/appendonly.aof'
 Invoke-CreditCompose run --rm --no-deps --name $helper --entrypoint sh redis -ec $prepare
 Invoke-MigrationDocker -DockerArguments @('cp',(Join-Path $backup 'dump.rdb'),'credit-risk-dev-redis:/data/dump.rdb') | Out-Null
 Invoke-MigrationDocker -DockerArguments @('cp',$staging,'credit-risk-dev-redis:/data/appendonlydir') | Out-Null
 Invoke-CreditCompose run --rm --no-deps --name $helper --entrypoint sh redis -ec 'chown -R redis:redis /data/appendonlydir; chown redis:redis /data/dump.rdb; redis-check-rdb /data/dump.rdb; redis-check-aof /data/appendonlydir/appendonly.aof.manifest'
 Invoke-CreditCompose up -d --no-deps redis
 Wait-Healthy 'credit-risk-dev-redis'
 $restored=@(Get-RedisSizes $databases);$restored | Set-Content -LiteralPath (Join-Path $backup 'redis-dbsize-after.txt') -Encoding utf8
 Compare-Counts $sizes $restored 'restored-redis'
 Wait-RedisPersistence 'BGSAVE';Wait-RedisPersistence 'BGREWRITEAOF'
 Invoke-CreditCompose stop redis;Invoke-CreditCompose up -d --no-deps redis;Wait-Healthy 'credit-risk-dev-redis'
 Compare-Counts $sizes @(Get-RedisSizes $databases) 'restarted-redis'
 foreach($pair in @(@('appendonly','yes'),@('appendfsync','everysec'))){$actual=(Redis -Command @('CONFIG','GET',$pair[0])) -split "`r?`n";if($actual[1] -ne $pair[1]){throw 'Redis persistence settings changed unexpectedly'}}
 $report.redis_counts_match=$true;$report.redis_aof_restart_verified=$true
 Invoke-CreditCompose up -d --wait --wait-timeout 300
 $report.phase='complete';$report.success=$true;$report.completed_at=(Get-Date).ToString('o');Save-Report
 Write-Output ('Migration verified. Report: '+(Join-Path $backup 'migration-report.json'))
}catch {
 $detail=$_.Exception.Message
 foreach($key in $values.Keys){if($key -match 'PASSWORD|API_KEY' -and $values[$key]){$detail=$detail.Replace($values[$key],'[redacted]')}}
 $report.error='Migration stopped: '+$detail+' No automatic retry or rollback.'
 $report.failed_at=(Get-Date).ToString('o')
 if($switched){try{Invoke-CreditCompose stop;$report.new_services_stopped=$true}catch{$report.new_services_stopped=$false}}
 Save-Report
 throw ('Migration stopped in phase '+$report.phase+'. '+$report.error+' Backup: '+$backup)
}
