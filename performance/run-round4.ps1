# Round 4: bottleneck validation (single vs multi SKU, sold-out entry, drain latency, Hikari 10/15/20).
# No architecture changes. Numbers from this run only.
param(
  [string]$JMeterHome = "$env:USERPROFILE\Downloads\apache-jmeter-5.6.3\apache-jmeter-5.6.3",
  [string]$BaseUrl = "http://127.0.0.1:8082",
  [string]$JMeterHeap = "-Xms64m -Xmx256m",
  [int]$RampSec = 10,
  [int]$WarmupSec = 15,
  [int]$MeasureSec = 60,
  [int]$CooldownSec = 10,
  [int]$DrainMaxSec = 120,
  [int]$SkuCount = 10,
  [int]$LargeStock = 50000,
  [int]$LowStock = 20,
  [switch]$SkipHikariSweep,
  [switch]$Skip100
)

$ErrorActionPreference = "Continue"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$PerfRoot = Join-Path $Root "performance"
$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$OutDir = Join-Path $PerfRoot "results\round4-$Stamp"
$LogDir = Join-Path $OutDir "logs"
New-Item -ItemType Directory -Force -Path $OutDir, $LogDir, (Join-Path $OutDir "explain"), (Join-Path $OutDir "drain") | Out-Null

$JMeterJar = Join-Path $JMeterHome "bin\ApacheJMeter.jar"
$JavaExe = (Get-Command java -ErrorAction SilentlyContinue).Source
$DurationSec = $RampSec + $WarmupSec + $MeasureSec + $CooldownSec
$PlanSteady = Join-Path $PerfRoot "jmeter\round4_reserve.jmx"
$PlanFixed = Join-Path $PerfRoot "jmeter\round4_fixed_loops.jmx"
$AllRows = New-Object System.Collections.Generic.List[object]

function Invoke-Mysql([string]$Sql) {
  $prev=$ErrorActionPreference; $ErrorActionPreference='Continue'
  try { $Sql | docker exec -i credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local ticketing 2>$null | Out-Null }
  finally { $ErrorActionPreference=$prev }
}
function Invoke-MysqlQuery([string]$Sql,[string]$OutFile) {
  $prev=$ErrorActionPreference; $ErrorActionPreference='Continue'
  try { $Sql | docker exec -i credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local ticketing --table 2>$null | Out-File -Encoding UTF8 $OutFile }
  finally { $ErrorActionPreference=$prev }
}
function MysqlN([string]$Sql) {
  $tmp=Join-Path $env:TEMP ("r4n-"+[guid]::NewGuid().ToString('N')+".sql")
  Set-Content -Path $tmp -Value $Sql -Encoding ASCII
  $prev=$ErrorActionPreference; $ErrorActionPreference='Continue'
  try {
    $out=Get-Content $tmp -Raw | docker exec -i credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local ticketing -N 2>$null
    $line=(@($out)|Where-Object{$_ -and $_ -notmatch 'Warning'}|Select-Object -First 1)
    if(-not $line){return 'NA'}; return ([string]$line).Trim()
  } finally { $ErrorActionPreference=$prev; Remove-Item $tmp -Force -EA SilentlyContinue }
}
function Prom([string]$Name) {
  try{ Invoke-WebRequest "$BaseUrl/actuator/prometheus" -UseBasicParsing -TimeoutSec 10 -OutFile (Join-Path $OutDir "prometheus-$Name.txt") }
  catch{ "capture_failed=$($_.Exception.Message)" | Set-Content (Join-Path $OutDir "prometheus-$Name.txt") }
}
function PromVal([string]$File,[string]$Regex) {
  if(-not (Test-Path $File)){return 'NA'}
  $m=[regex]::Match((Get-Content $File -Raw),$Regex)
  if($m.Success){return $m.Groups[1].Value}; return 'NA'
}
function AsDouble($v){ if($null -eq $v -or $v -eq '' -or $v -eq 'NA'){return $null}; $d=0.0; if([double]::TryParse([string]$v,[ref]$d)){return $d}; return $null }
function TimeoutDelta($b,$a){
  $bd=AsDouble $b; $ad=AsDouble $a
  if($null -eq $bd -or $null -eq $ad){return 'NA'}
  if($ad -lt $bd){return "COUNTER_RESET"}
  return [Math]::Round($ad-$bd,0)
}
function Median($arr){
  $s=@($arr|Where-Object{$null -ne $_}|Sort-Object); if($s.Count -eq 0){return $null}
  $m=[int][Math]::Floor(($s.Count-1)/2.0); if($s.Count % 2 -eq 1){return $s[$m]}
  return [Math]::Round(($s[$m]+$s[$m+1])/2.0,2)
}

function Login-Users([int]$FromId,[int]$ToId,[string]$CsvPath,[string[]]$SkuIds) {
  $lines=@("userId,token,deviceId,skuId")
  $i=0
  for($id=$FromId;$id -le $ToId;$id++){
    if($id -eq 90100){continue}
    $phone="1399{0:D7}" -f ($id-90000)
    $code=(Invoke-RestMethod -Method Post "$BaseUrl/api/user/code?phone=$phone").data
    $token=(Invoke-RestMethod -Method Post "$BaseUrl/api/user/login" -ContentType "application/json" -Body (@{phone=$phone;code=$code}|ConvertTo-Json)).data
    $me=Invoke-RestMethod "$BaseUrl/api/user/me" -Headers @{authorization=$token}
    $uid=[int]$me.data.id
    $sku=$SkuIds[$i % $SkuIds.Count]
    $lines += "$uid,$token,r4-device-$uid,$sku"
    $i++
  }
  $lines | Set-Content -Encoding UTF8 $CsvPath
}

function Ensure-Skus([string[]]$SkuIds,[int]$Stock,[int]$PurchaseLimit) {
  Invoke-Mysql @"
INSERT INTO tb_event(id,name,description,venue,status) VALUES('perf_event','PERF','fixture','Arena','ON_SALE')
 ON DUPLICATE KEY UPDATE status='ON_SALE';
INSERT INTO tb_event_session(id,event_id,starts_at) VALUES('perf_session','perf_event','2027-12-31 19:30:00')
 ON DUPLICATE KEY UPDATE event_id='perf_event';
"@
  foreach($sku in $SkuIds){
    Invoke-Mysql @"
INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,sale_start_time,sale_end_time,purchase_limit,status,version)
VALUES('$sku','perf_session','R4',10.00,$Stock,$Stock,'2020-01-01 00:00:00','2030-12-31 23:59:59',$PurchaseLimit,'ON_SALE',0)
ON DUPLICATE KEY UPDATE total_stock=$Stock, remaining_stock=$Stock, purchase_limit=$PurchaseLimit, status='ON_SALE', version=version+1;
"@
  }
}

function Reset-Skus([string[]]$SkuIds,[int]$Stock) {
  Invoke-Mysql "DELETE FROM tb_outbox_message WHERE aggregate_id NOT IN (SELECT id FROM tb_ticket_order);"
  $in = ($SkuIds | ForEach-Object { "'$_'" }) -join ','
  Invoke-Mysql @"
DELETE FROM tb_issued_ticket WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id IN ($in)) x);
DELETE FROM tb_payment_transaction WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id IN ($in)) x);
DELETE FROM tb_wallet_ledger WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id IN ($in)) x);
DELETE FROM tb_risk_decision WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id IN ($in)) x);
DELETE FROM tb_message_consume_record WHERE business_key LIKE 'AUTO_PAYMENT:%' OR business_key LIKE 'ISSUE:%' OR business_key LIKE 'RISK_CHECK:%';
DELETE FROM tb_outbox_message WHERE aggregate_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id IN ($in)) x);
DELETE FROM tb_ticket_order WHERE sku_id IN ($in);
DELETE FROM tb_reservation WHERE sku_id IN ($in);
DELETE FROM tb_reserve_request WHERE sku_id IN ($in);
DELETE FROM tb_user_purchase WHERE sku_id IN ($in);
UPDATE tb_ticket_sku SET remaining_stock=$Stock, total_stock=$Stock, version=version+1 WHERE id IN ($in);
"@
  foreach($sku in $SkuIds){
    docker exec credit_risk-ticket-redis-1 redis-cli EVAL "local k=redis.call('keys','*$sku*'); for i=1,#k do redis.call('del',k[i]) end; return #k" 0 2>$null | Out-Null
    docker exec credit_risk-ticket-redis-1 redis-cli SET "ticket:{$sku}:stock" "$Stock" 2>$null | Out-Null
  }
}

function Warm-Skus([string]$UsersCsv,[string[]]$SkuIds,[int]$Stock) {
  $u=Import-Csv $UsersCsv | Select-Object -First 1
  foreach($sku in $SkuIds){
    $body=@{requestId="r4-warm-$Stamp-$sku-$(Get-Random)";userId=[int]$u.userId;eventSessionId='perf_session';ticketSkuId=$sku;quantity=1;deviceId=$u.deviceId}|ConvertTo-Json
    try{ Invoke-RestMethod -Method Post "$BaseUrl/api/tickets/reserve" -Headers @{authorization=$u.token;'Content-Type'='application/json'} -Body $body -TimeoutSec 30 | Out-Null } catch {}
  }
  Reset-Skus $SkuIds $Stock
}

function Analyze-Window([string]$JtlPath,[long]$WindowStartMs,[long]$WindowEndMs) {
  if(-not (Test-Path $JtlPath) -or (Get-Item $JtlPath).Length -eq 0){ return $null }
  $rows=@(Import-Csv $JtlPath)
  $win=@(); foreach($r in $rows){ $ts=[long]$r.timeStamp; if($ts -ge $WindowStartMs -and $ts -lt $WindowEndMs){ $win += $r } }
  if($WindowStartMs -lt 0){ $win=$rows }
  $http200=0;$biz409=0;$http503=0;$sock=0;$sys=0
  $lats=New-Object System.Collections.Generic.List[int]
  foreach($r in $win){
    $code=0;[void][int]::TryParse([string]$r.responseCode,[ref]$code)
    $el=0;[void][int]::TryParse([string]$r.elapsed,[ref]$el)
    $msg=[string]$r.responseMessage
    if($code -eq 200){$http200++;$lats.Add($el);continue}
    if($code -eq 409){$biz409++;$lats.Add($el);continue}
    if($code -in 400,403,429){$lats.Add($el);continue}
    if($code -eq 503){$http503++;$sys++;continue}
    if($msg -match 'SocketTimeout' -or $code -eq 0){$sock++;$sys++;continue}
    $sys++
  }
  function Pct($list,[double]$p){ if($list.Count -eq 0){return 'NA'}; $s=@($list|Sort-Object); $i=[Math]::Min($s.Count-1,[Math]::Ceiling($s.Count*$p)-1); if($i -lt 0){$i=0}; return $s[$i] }
  function Avg($list){ if($list.Count -eq 0){return 'NA'}; return [Math]::Round(($list|Measure-Object -Average).Average,2) }
  $measSec = if($WindowStartMs -lt 0){
    $times=@($win|ForEach-Object{[long]$_.timeStamp}|Sort-Object); if($times.Count -lt 2){0.001} else { [Math]::Max(0.001,($times[-1]-$times[0])/1000.0) }
  } else { [Math]::Max(0.001,($WindowEndMs-$WindowStartMs)/1000.0) }
  return [pscustomobject]@{
    window_requests=$win.Count; http_200=$http200; business_409=$biz409; http_503=$http503; socket_timeout=$sock
    system_errors=$sys; system_error_rate=[Math]::Round(100.0*$sys/[Math]::Max(1,$win.Count),3)
    measure_seconds=[Math]::Round($measSec,3)
    success_qps=[Math]::Round($http200/$measSec,2)
    entry_rps=[Math]::Round($win.Count/$measSec,2)
    avg_ms=(Avg $lats); p50_ms=(Pct $lats 0.5); p90_ms=(Pct $lats 0.9); p95_ms=(Pct $lats 0.95); p99_ms=(Pct $lats 0.99)
  }
}

function Wait-Drain([string[]]$SkuIds,[string]$Tag) {
  $in=($SkuIds|ForEach-Object{"'$_'"}) -join ','
  $t0=Get-Date; $deadline=$t0.AddSeconds($DrainMaxSec); $stable=0
  $peakPending=0.0; $peakOutbox=0
  while((Get-Date) -lt $deadline){
    Prom "drain-$Tag-$(Get-Date -Format HHmmssfff)"
    $latest=Get-ChildItem $OutDir -Filter "prometheus-drain-$Tag-*.txt" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $pend=AsDouble (PromVal $latest.FullName 'hikaricp_connections_pending\{pool="HikariPool-1",\} ([0-9.]+)')
    if($null -ne $pend -and $pend -gt $peakPending){$peakPending=$pend}
    $w=MysqlN "SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id IN ($in) AND status='WAITING_PAYMENT';"
    $ob=MysqlN "SELECT COUNT(*) FROM tb_outbox_message WHERE status IN ('PENDING','RETRY','SENDING') AND message_key LIKE 'AUTO_PAYMENT:%' AND aggregate_id IN (SELECT id FROM tb_ticket_order WHERE sku_id IN ($in));"
    $obi=0; if($ob -ne 'NA'){[void][int]::TryParse($ob,[ref]$obi)}; if($obi -gt $peakOutbox){$peakOutbox=$obi}
    if($w -eq '0' -and $obi -eq 0){ $stable++; if($stable -ge 2){break} } else { $stable=0 }
    Start-Sleep 2
  }
  Prom "drain-final-$Tag"
  return [pscustomobject]@{
    drain_seconds=[Math]::Round(((Get-Date)-$t0).TotalSeconds,1)
    waiting=(MysqlN "SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id IN ($in) AND status='WAITING_PAYMENT';")
    paid=(MysqlN "SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id IN ($in) AND status='PAID';")
    issued=(MysqlN "SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id IN ($in) AND status='ISSUED';")
    orders=(MysqlN "SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id IN ($in);")
    payments=(MysqlN "SELECT COUNT(*) FROM tb_payment_transaction WHERE status='SUCCESS' AND type='PAYMENT' AND order_id IN (SELECT id FROM tb_ticket_order WHERE sku_id IN ($in));")
    tickets=(MysqlN "SELECT COUNT(*) FROM tb_issued_ticket WHERE order_id IN (SELECT id FROM tb_ticket_order WHERE sku_id IN ($in));")
    outbox_auto_pending=(MysqlN "SELECT COUNT(*) FROM tb_outbox_message WHERE status IN ('PENDING','RETRY','SENDING') AND message_key LIKE 'AUTO_PAYMENT:%' AND aggregate_id IN (SELECT id FROM tb_ticket_order WHERE sku_id IN ($in));")
    hikari_pending_peak=$peakPending; outbox_backlog_peak=$peakOutbox
  }
}

function Export-DrainLatency([string[]]$SkuIds,[string]$OutCsv) {
  $in=($SkuIds|ForEach-Object{"'$_'"}) -join ','
  $sql=@"
SELECT
 o.id AS order_id,
 TIMESTAMPDIFF(MICROSECOND, ap.created_at, ap.updated_at)/1000.0 AS auto_outbox_create_to_sent_ms,
 TIMESTAMPDIFF(MICROSECOND, ap.updated_at, ac.created_at)/1000.0 AS auto_mq_wait_ms,
 TIMESTAMPDIFF(MICROSECOND, ac.created_at, p.created_at)/1000.0 AS payment_tx_ms,
 TIMESTAMPDIFF(MICROSECOND, p.created_at, iss.created_at)/1000.0 AS issue_outbox_create_lag_ms,
 TIMESTAMPDIFF(MICROSECOND, iss.created_at, iss.updated_at)/1000.0 AS issue_outbox_create_to_sent_ms,
 TIMESTAMPDIFF(MICROSECOND, iss.updated_at, ic.created_at)/1000.0 AS issue_mq_wait_ms,
 TIMESTAMPDIFF(MICROSECOND, ic.created_at, o.updated_at)/1000.0 AS issue_consumer_ms,
 TIMESTAMPDIFF(MICROSECOND, ap.created_at, o.updated_at)/1000.0 AS total_fulfillment_ms
FROM tb_ticket_order o
JOIN tb_outbox_message ap ON ap.message_key=CONCAT('AUTO_PAYMENT:',o.id) AND ap.status='SENT'
LEFT JOIN tb_message_consume_record ac ON ac.business_key=CONCAT('AUTO_PAYMENT:',o.id) AND ac.status='DONE'
LEFT JOIN tb_payment_transaction p ON p.order_id=o.id AND p.status='SUCCESS' AND p.type='PAYMENT'
LEFT JOIN tb_outbox_message iss ON iss.message_key=CONCAT('ISSUE:',o.id) AND iss.status='SENT'
LEFT JOIN tb_message_consume_record ic ON ic.business_key=CONCAT('ISSUE:',o.id) AND ic.status='DONE'
WHERE o.sku_id IN ($in) AND o.status IN ('PAID','ISSUED')
LIMIT 5000;
"@
  $tmp=Join-Path $env:TEMP ("r4d-"+[guid]::NewGuid().ToString('N')+".sql")
  Set-Content -Path $tmp -Value $sql -Encoding ASCII
  $prev=$ErrorActionPreference; $ErrorActionPreference='Continue'
  try {
    Get-Content $tmp -Raw | docker exec -i credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local ticketing -N --batch --raw 2>$null |
      ForEach-Object { $_ -replace '\t',',' } | Set-Content -Encoding UTF8 $OutCsv
    # prepend header
    $body=Get-Content $OutCsv
    @('order_id,auto_outbox_create_to_sent_ms,auto_mq_wait_ms,payment_tx_ms,issue_outbox_create_lag_ms,issue_outbox_create_to_sent_ms,issue_mq_wait_ms,issue_consumer_ms,total_fulfillment_ms') + $body | Set-Content -Encoding UTF8 $OutCsv
  } finally { $ErrorActionPreference=$prev; Remove-Item $tmp -Force -EA SilentlyContinue }
}

function Summarize-DrainCsv([string]$Csv,[string]$OutTxt) {
  if(-not (Test-Path $Csv) -or (Get-Item $Csv).Length -lt 10){ "no_data" | Set-Content $OutTxt; return $null }
  $rows=@(Import-Csv $Csv)
  if($rows.Count -eq 0){ "empty" | Set-Content $OutTxt; return $null }
  function Stats($name){
    $vals=@($rows | ForEach-Object { AsDouble $_.$name } | Where-Object { $null -ne $_ } | Sort-Object)
    if($vals.Count -eq 0){ return "$name,NA,NA,NA,NA,NA" }
    function P($p){ $i=[Math]::Min($vals.Count-1,[Math]::Ceiling($vals.Count*$p)-1); if($i -lt 0){$i=0}; return [Math]::Round($vals[$i],2) }
    $avg=[Math]::Round(($vals|Measure-Object -Average).Average,2)
    return "$name,$avg,$(P 0.5),$(P 0.95),$(P 0.99),$([Math]::Round($vals[-1],2))"
  }
  $lines=@('stage,avg,p50,p95,p99,max')
  foreach($c in @('auto_outbox_create_to_sent_ms','auto_mq_wait_ms','payment_tx_ms','issue_outbox_create_to_sent_ms','issue_mq_wait_ms','issue_consumer_ms','total_fulfillment_ms')){
    $lines += (Stats $c)
  }
  $lines | Set-Content -Encoding UTF8 $OutTxt
  return $lines
}

function Validate-Skus([string[]]$SkuIds,[string]$OutFile,[int]$InitialStock) {
  $in=($SkuIds|ForEach-Object{"'$_'"}) -join ','
  Invoke-MysqlQuery @"
SELECT 'NEG_STOCK' AS check_name, COUNT(*) AS bad_rows FROM tb_ticket_sku WHERE id IN ($in) AND (remaining_stock<0 OR remaining_stock>total_stock);
SELECT 'ORDERS_OVER_STOCK' AS check_name, COUNT(*) AS bad_rows FROM (
 SELECT s.id FROM tb_ticket_sku s LEFT JOIN tb_ticket_order o ON o.sku_id=s.id AND o.status NOT IN ('CANCELLED','PAY_TIMEOUT','RISK_BLOCKED')
 WHERE s.id IN ($in) GROUP BY s.id,s.total_stock HAVING COALESCE(SUM(o.quantity),0)>s.total_stock) t;
SELECT 'DUP_PAYMENT_SUCCESS' AS check_name, COUNT(*) AS bad_rows FROM (
 SELECT order_id FROM tb_payment_transaction WHERE order_id IN (SELECT id FROM tb_ticket_order WHERE sku_id IN ($in)) AND status='SUCCESS' AND type='PAYMENT' GROUP BY order_id HAVING COUNT(*)>1) t;
SELECT 'DUP_ISSUED_SEQ' AS check_name, COUNT(*) AS bad_rows FROM (
 SELECT order_id,sequence_no FROM tb_issued_ticket WHERE order_id IN (SELECT id FROM tb_ticket_order WHERE sku_id IN ($in)) GROUP BY order_id,sequence_no HAVING COUNT(*)>1) t;
SELECT 'PURCHASE_OVER_LIMIT' AS check_name, COUNT(*) AS bad_rows FROM tb_user_purchase p JOIN tb_ticket_sku s ON s.id=p.sku_id WHERE s.id IN ($in) AND p.quantity>s.purchase_limit;
SELECT 'ORDER_COUNT' AS metric, COUNT(*) AS value FROM tb_ticket_order WHERE sku_id IN ($in);
SELECT 'PAID_OR_ISSUED' AS metric, COUNT(*) AS value FROM tb_ticket_order WHERE sku_id IN ($in) AND status IN ('PAID','ISSUED');
SELECT 'WAITING_PAYMENT' AS metric, COUNT(*) AS value FROM tb_ticket_order WHERE sku_id IN ($in) AND status='WAITING_PAYMENT';
SELECT 'PAYMENT_SUCCESS' AS metric, COUNT(*) AS value FROM tb_payment_transaction WHERE status='SUCCESS' AND type='PAYMENT' AND order_id IN (SELECT id FROM tb_ticket_order WHERE sku_id IN ($in));
SELECT 'ISSUED_TICKETS' AS metric, COUNT(*) AS value FROM tb_issued_ticket WHERE order_id IN (SELECT id FROM tb_ticket_order WHERE sku_id IN ($in));
SELECT 'REMAINING_SUM' AS metric, COALESCE(SUM(remaining_stock),0) AS value FROM tb_ticket_sku WHERE id IN ($in);
"@ $OutFile
}

function Get-BadRows([string]$f){
  if(-not (Test-Path $f)){return 'NA'}; $sum=0
  foreach($m in [regex]::Matches((Get-Content $f -Raw),'(?ms)\|\s*(NEG_STOCK|ORDERS_OVER_STOCK|DUP_PAYMENT_SUCCESS|DUP_ISSUED_SEQ|PURCHASE_OVER_LIMIT)\s*\|\s*(\d+)\s*\|')){ $sum+=[int]$m.Groups[2].Value }
  return $sum
}
function Get-Metric([string]$f,[string]$n){
  if(-not (Test-Path $f)){return 'NA'}
  $m=[regex]::Match((Get-Content $f -Raw),"(?ms)\|\s*$n\s*\|\s*(\d+)\s*\|"); if($m.Success){return $m.Groups[1].Value}; return 'NA'
}

function Capture-Pools([string]$Tag) {
  Prom $Tag
  $f=Join-Path $OutDir "prometheus-$Tag.txt"
  $pools=@('outbox','reservation','risk','autopay','compensation')
  $lines=@("tag=$Tag")
  foreach($p in $pools){
    $lines += "active_$p=$(PromVal $f ('threadpool_active\{pool="'+$p+'",\} ([0-9.]+)'))"
    $lines += "queue_$p=$(PromVal $f ('threadpool_queue\{pool="'+$p+'",\} ([0-9.]+)'))"
    $lines += "reject_$p=$(PromVal $f ('threadpool_reject\{pool="'+$p+'",\} ([0-9.]+)'))"
    $lines += "completed_$p=$(PromVal $f ('threadpool_completed\{pool="'+$p+'",\} ([0-9.]+)'))"
  }
  $lines += "hikari_active=$(PromVal $f 'hikaricp_connections_active\{pool="HikariPool-1",\} ([0-9.]+)')"
  $lines += "hikari_pending=$(PromVal $f 'hikaricp_connections_pending\{pool="HikariPool-1",\} ([0-9.]+)')"
  $lines += "hikari_max=$(PromVal $f 'hikaricp_connections_max\{pool="HikariPool-1",\} ([0-9.]+)')"
  $lines += "hikari_usage_max=$(PromVal $f 'hikaricp_connections_usage_seconds_max\{pool="HikariPool-1",\} ([0-9.eE+-]+)')"
  $lines += "outbox_pending=$(PromVal $f 'mq_outbox_pending ([0-9.]+)')"
  $lines | Set-Content (Join-Path $OutDir "pools-$Tag.txt")
}

function Restart-Platform([int]$HikariMax) {
  Write-Host "Restart platform hikari_max=$HikariMax"
  docker rm -f credit_risk-ticket-risk-platform-1 2>$null | Out-Null
  docker run -d --name credit_risk-ticket-risk-platform-1 --network credit_risk_default `
    -p 127.0.0.1:8082:8082 --memory 768m `
    -e DB_URL='jdbc:mysql://ticket-mysql:3306/ticketing?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true' `
    -e DB_USER=ticketing -e DB_PASSWORD=ticketing-local -e REDIS_HOST=ticket-redis `
    -e MQ_NAMESERVER=ticket-rmqnamesrv:9876 -e RISK_AGENT_URL='http://risk-agent:8090' `
    -e INTERNAL_API_KEY='local-risk-key-change-me' -e DEMO_LOGIN_ENABLED='true' `
    -e JAVA_TOOL_OPTIONS='-Xms128m -Xmx384m -XX:+UseSerialGC' `
    -e SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE="$HikariMax" `
    credit_risk-ticket-platform:perf | Out-Null
  for($i=0;$i -lt 40;$i++){ try{ if((Invoke-RestMethod "$BaseUrl/actuator/health" -TimeoutSec 3).status -eq 'UP'){break} }catch{}; Start-Sleep 3 }
  Prom "env-hikari-$HikariMax"
  $mx=PromVal (Join-Path $OutDir "prometheus-env-hikari-$HikariMax.txt") 'hikaricp_connections_max\{pool="HikariPool-1",\} ([0-9.]+)'
  Write-Host "hikari_observed=$mx"
  return $mx
}

function Run-Steady([string]$Name,[int]$Threads,[string]$UsersCsv,[string[]]$SkuIds,[int]$Stock,[string]$Mode) {
  Write-Host "=== $Name threads=$Threads mode=$Mode skus=$($SkuIds.Count) ==="
  Reset-Skus $SkuIds $Stock
  Warm-Skus $UsersCsv $SkuIds $Stock
  Capture-Pools "before-$Name"
  $toB=PromVal (Join-Path $OutDir "prometheus-before-$Name.txt") 'hikaricp_connections_timeout_total\{pool="HikariPool-1",\} ([0-9.]+)'
  $jtl=Join-Path $OutDir "$Name.jtl"; $log=Join-Path $LogDir "$Name.log"
  $t0=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
  $argList=@($JMeterHeap.Split(' ')+@('-jar',$JMeterJar,'-n','-t',$PlanSteady,'-l',$jtl,'-j',$log,
    '-Jhost=127.0.0.1','-Jport=8082',"-Jthreads=$Threads","-Jramp=$RampSec","-Jduration=$DurationSec",
    "-Jusers=$UsersCsv",'-JsessionId=perf_session'))
  $proc=Start-Process -FilePath $JavaExe -ArgumentList $argList -PassThru -NoNewWindow
  $midWait=$RampSec+$WarmupSec+[int]($MeasureSec/2)
  Start-Sleep $midWait
  Capture-Pools "during-$Name"
  $pendingDuring=PromVal (Join-Path $OutDir "prometheus-during-$Name.txt") 'hikaricp_connections_pending\{pool="HikariPool-1",\} ([0-9.]+)'
  $activeDuring=PromVal (Join-Path $OutDir "prometheus-during-$Name.txt") 'hikaricp_connections_active\{pool="HikariPool-1",\} ([0-9.]+)'
  $waitLeft=[Math]::Max(5,$DurationSec-$midWait+30)
  Wait-Process -Id $proc.Id -Timeout $waitLeft -ErrorAction SilentlyContinue
  if(-not $proc.HasExited){ Stop-Process -Id $proc.Id -Force -EA SilentlyContinue }
  Capture-Pools "after-$Name"
  $toA=PromVal (Join-Path $OutDir "prometheus-after-$Name.txt") 'hikaricp_connections_timeout_total\{pool="HikariPool-1",\} ([0-9.]+)'
  $winStart=$t0+($RampSec+$WarmupSec)*1000; $winEnd=$winStart+$MeasureSec*1000
  $sum=Analyze-Window $jtl $winStart $winEnd
  $drain=Wait-Drain $SkuIds $Name
  $val=Join-Path $OutDir "validation-$Name.txt"
  Validate-Skus $SkuIds $val $Stock
  $bad=Get-BadRows $val
  $row=[pscustomobject]@{
    experiment='A'; mode=$Mode; name=$Name; concurrency=$Threads; sku_count=$SkuIds.Count
    success_qps=$sum.success_qps; entry_rps=$sum.entry_rps; avg_ms=$sum.avg_ms; p50_ms=$sum.p50_ms; p90_ms=$sum.p90_ms; p95_ms=$sum.p95_ms; p99_ms=$sum.p99_ms
    http_200=$sum.http_200; business_409=$sum.business_409; http_503=$sum.http_503; socket_timeout=$sum.socket_timeout
    system_error_rate=$sum.system_error_rate; hikari_pending_during=$pendingDuring; hikari_active_during=$activeDuring
    hikari_timeout_delta=(TimeoutDelta $toB $toA)
    orders=$drain.orders; payments=$drain.payments; tickets=$drain.tickets
    drain_seconds=$drain.drain_seconds; drain_waiting=$drain.waiting; outbox_auto_pending=$drain.outbox_auto_pending
    hikari_pending_peak_drain=$drain.hikari_pending_peak; bad_rows=$bad
    paid_or_issued=(Get-Metric $val 'PAID_OR_ISSUED'); remaining=(Get-Metric $val 'REMAINING_SUM')
    valid=($bad -eq 0 -or $bad -eq '0')
  }
  $AllRows.Add($row)
  ($row.PSObject.Properties|%{"$($_.Name)=$($_.Value)"}) -join "`n" | Set-Content (Join-Path $OutDir "metrics-$Name.txt")
  Write-Host ("RESULT {0} qps={1} p95={2} pending={3} waiting={4} bad={5}" -f $Name,$row.success_qps,$row.p95_ms,$pendingDuring,$row.drain_waiting,$bad)
  return $row
}

function Run-Fixed([string]$Name,[int]$Threads,[int]$Loops,[string]$UsersCsv,[string[]]$SkuIds,[int]$Stock) {
  Write-Host "=== $Name threads=$Threads loops=$Loops stock=$Stock ==="
  Reset-Skus $SkuIds $Stock
  # no warm that consumes stock for sold-out test — just redis stock set via Reset
  Capture-Pools "before-$Name"
  $jtl=Join-Path $OutDir "$Name.jtl"; $log=Join-Path $LogDir "$Name.log"
  $argList=@($JMeterHeap.Split(' ')+@('-jar',$JMeterJar,'-n','-t',$PlanFixed,'-l',$jtl,'-j',$log,
    '-Jhost=127.0.0.1','-Jport=8082',"-Jthreads=$Threads","-Jramp=5","-Jloops=$Loops",
    "-Jusers=$UsersCsv",'-JsessionId=perf_session'))
  $proc=Start-Process -FilePath $JavaExe -ArgumentList $argList -PassThru -NoNewWindow
  Start-Sleep ([Math]::Max(8,[int]($Threads/10)))
  Capture-Pools "during-$Name"
  Wait-Process -Id $proc.Id -Timeout 600 -ErrorAction SilentlyContinue
  if(-not $proc.HasExited){ Stop-Process -Id $proc.Id -Force -EA SilentlyContinue }
  Capture-Pools "after-$Name"
  $sum=Analyze-Window $jtl -1 -1
  $drain=Wait-Drain $SkuIds $Name
  $val=Join-Path $OutDir "validation-$Name.txt"
  Validate-Skus $SkuIds $val $Stock
  $bad=Get-BadRows $val
  $successOrders=[int](Get-Metric $val 'PAID_OR_ISSUED')
  $row=[pscustomobject]@{
    experiment='B'; mode='low_stock'; name=$Name; concurrency=$Threads; loops=$Loops; initial_stock=$Stock
    total_requests=$sum.window_requests; entry_rps=$sum.entry_rps; success_qps=$sum.success_qps
    http_200=$sum.http_200; business_409=$sum.business_409; http_503=$sum.http_503; socket_timeout=$sum.socket_timeout
    system_errors=$sum.system_errors; avg_ms=$sum.avg_ms; p95_ms=$sum.p95_ms; p99_ms=$sum.p99_ms
    success_orders=$successOrders; payments=$drain.payments; tickets=$drain.tickets
    remaining=(Get-Metric $val 'REMAINING_SUM'); bad_rows=$bad
    drain_waiting=$drain.drain_waiting; drain_seconds=$drain.drain_seconds
    oversell=([int]$successOrders -gt $Stock); valid=(($bad -eq 0 -or $bad -eq '0') -and ([int]$successOrders -le $Stock))
  }
  $AllRows.Add($row)
  ($row.PSObject.Properties|%{"$($_.Name)=$($_.Value)"}) -join "`n" | Set-Content (Join-Path $OutDir "metrics-$Name.txt")
  Write-Host ("RESULT {0} entry_rps={1} success_orders={2}/{3} biz409={4} bad={5}" -f $Name,$row.entry_rps,$successOrders,$Stock,$row.business_409,$bad)
  return $row
}

# -------- bootstrap --------
Prom 'env-check'
$hikari=PromVal (Join-Path $OutDir 'prometheus-env-check.txt') 'hikaricp_connections_max\{pool="HikariPool-1",\} ([0-9.]+)'
$mem=docker inspect credit_risk-ticket-risk-platform-1 --format '{{.HostConfig.Memory}}' 2>$null
$jvm=(docker inspect credit_risk-ticket-risk-platform-1 --format '{{range .Config.Env}}{{println .}}{{end}}' 2>$null | Select-String 'JAVA_TOOL' | ForEach-Object{$_.Line})
@"
stamp=$Stamp
round=4
purpose=bottleneck_validation_not_qps_chase
hikari_max=$hikari
platform_mem=$mem
jvm=$jvm
git_head=$(git -C $Root rev-parse HEAD)
git_describe=$(git -C $Root describe --always --dirty 2>$null)
duration_s=$DurationSec
measure_s=$MeasureSec
sku_count_multi=$SkuCount
large_stock=$LargeStock
low_stock=$LowStock
payment_consumer_thread_model=RocketMQ consumeThreadMax=16 direct call (AUTO_PAYMENT); no nested autopay TaskPool
"@ | Set-Content (Join-Path $OutDir 'environment.txt')

Get-Content (Join-Path $PerfRoot 'sql\init_perf_data.sql') -Raw | docker exec -i credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local ticketing 2>$null | Out-Null
Invoke-Mysql @"
INSERT INTO tb_user(id,phone,nick_name,role,create_time)
SELECT 90000+n, CONCAT('1399',LPAD(n,7,'0')), CONCAT('perf_user_',n), 'USER', '2025-01-01'
FROM (
  SELECT a.N + b.N*10 + c.N*100 + 1 AS n
  FROM (SELECT 0 N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
  CROSS JOIN (SELECT 0 N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b
  CROSS JOIN (SELECT 0 N UNION SELECT 1 UNION SELECT 2) c
) nums WHERE n BETWEEN 1 AND 200
ON DUPLICATE KEY UPDATE nick_name=VALUES(nick_name);
UPDATE tb_risk_rule SET config=JSON_SET(config,'$.threshold',100000) WHERE rule_code IN ('IP_FREQUENCY','DEVICE_FREQUENCY','USER_FREQUENCY');
DELETE FROM tb_risk_profile WHERE subject_value IN (SELECT CAST(id AS CHAR) FROM tb_user WHERE id BETWEEN 90001 AND 90200) AND decision='BLOCK' AND id<>'perf_profile_block';
INSERT INTO tb_wallet_account(user_id,available_balance,frozen_balance,version) SELECT id,100000,0,0 FROM tb_user WHERE id BETWEEN 90001 AND 90200 ON DUPLICATE KEY UPDATE available_balance=GREATEST(available_balance,100000);
"@

# EXPLAIN dump
@"
EXPLAIN UPDATE tb_ticket_sku SET remaining_stock=remaining_stock-1, version=version+1 WHERE id='perf_sku_r4_00' AND remaining_stock>=1 AND status='ON_SALE';
EXPLAIN UPDATE tb_user_purchase SET quantity=quantity+1 WHERE user_id=90001 AND sku_id='perf_sku_r4_00' AND quantity+1<=100000;
EXPLAIN SELECT * FROM tb_outbox_message WHERE status IN ('PENDING','RETRY','SENDING') AND next_retry_time<=CURRENT_TIMESTAMP ORDER BY CASE WHEN message_key LIKE 'AUTO_PAYMENT:%' THEN 0 WHEN message_key LIKE 'ISSUE:%' THEN 1 ELSE 2 END, created_at LIMIT 200;
SHOW INDEX FROM tb_ticket_sku;
SHOW INDEX FROM tb_user_purchase;
SHOW INDEX FROM tb_outbox_message;
SHOW INDEX FROM tb_reserve_request;
"@ | docker exec -i credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local ticketing 2>$null | Out-File -Encoding UTF8 (Join-Path $OutDir "explain\hotpath.txt")

$singleSku=@('perf_sku_r4_single')
$multiSkus=@(0..($SkuCount-1) | ForEach-Object { 'perf_sku_r4_{0:D2}' -f $_ })
Ensure-Skus $singleSku $LargeStock 100000
Ensure-Skus $multiSkus $LargeStock 100000

$usersSingle=Join-Path $OutDir 'users_single.csv'
$usersMulti=Join-Path $OutDir 'users_multi.csv'
Write-Host 'Login users...'
Login-Users 90001 90200 $usersSingle $singleSku
Login-Users 90001 90200 $usersMulti $multiSkus

# -------- Experiment A --------
$levels=@(20,50)
if(-not $Skip100){ $levels += 100 }
foreach($t in $levels){
  $reps= if($t -eq 50){ 3 } else { 1 }
  for($r=1;$r -le $reps;$r++){
    [void](Run-Steady "A_single_t${t}_r$r" $t $usersSingle $singleSku $LargeStock 'single')
  }
}
foreach($t in $levels){
  $reps= if($t -eq 50){ 3 } else { 1 }
  for($r=1;$r -le $reps;$r++){
    [void](Run-Steady "A_multi_t${t}_r$r" $t $usersMulti $multiSkus $LargeStock 'multi')
  }
}

# Drain latency from last single-50 run (or best available)
$latSrc = Get-ChildItem $OutDir -Filter 'A_single_t50_r*.jtl' | Sort-Object Name | Select-Object -Last 1
if($latSrc){
  $tag=$latSrc.BaseName
  Export-DrainLatency $singleSku (Join-Path $OutDir "drain\drain-latency-$tag.csv")
  Summarize-DrainCsv (Join-Path $OutDir "drain\drain-latency-$tag.csv") (Join-Path $OutDir "drain\drain-summary-$tag.txt") | Out-Null
}
# Also from multi-50 last
$latSrc2 = Get-ChildItem $OutDir -Filter 'A_multi_t50_r*.jtl' | Sort-Object Name | Select-Object -Last 1
if($latSrc2){
  Export-DrainLatency $multiSkus (Join-Path $OutDir "drain\drain-latency-$($latSrc2.BaseName).csv")
  Summarize-DrainCsv (Join-Path $OutDir "drain\drain-latency-$($latSrc2.BaseName).csv") (Join-Path $OutDir "drain\drain-summary-$($latSrc2.BaseName).txt") | Out-Null
}

# -------- Experiment B --------
Ensure-Skus $singleSku $LowStock 100000
$bLevels=@(50,100)
if(-not $Skip100){ $bLevels += 200 }
foreach($t in $bLevels){
  $reps= if($t -eq 100){ 3 } else { 1 }
  $loops=[Math]::Max(1,[Math]::Ceiling(1000.0/$t))
  # ensure >=1000 requests: threads*loops
  if($t*$loops -lt 1000){ $loops=[Math]::Ceiling(1000.0/$t) }
  for($r=1;$r -le $reps;$r++){
    [void](Run-Fixed "B_lowstock_t${t}_r$r" $t $loops $usersSingle $singleSku $LowStock)
  }
}

# -------- Hikari sweep (50 single, 2 runs each) --------
if(-not $SkipHikariSweep){
  foreach($h in @(10,15,20)){
    $obs=Restart-Platform $h
    for($r=1;$r -le 2;$r++){
      $row=Run-Steady "H_hikari${h}_t50_r$r" 50 $usersSingle $singleSku $LargeStock "hikari_$h"
      $row | Add-Member -NotePropertyName hikari_configured -NotePropertyValue $h -Force
      $row | Add-Member -NotePropertyName hikari_observed -NotePropertyValue $obs -Force
      $row.experiment='H'
    }
  }
  # restore hikari=10
  [void](Restart-Platform 10)
}

$AllRows | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $OutDir 'summary.csv')
$AllRows | Format-Table experiment,mode,name,concurrency,success_qps,entry_rps,p95_ms,p99_ms,hikari_pending_during,drain_waiting,bad_rows,valid -AutoSize |
  Out-String | Tee-Object (Join-Path $OutDir 'summary_table.txt') | Write-Host
"out_dir=$OutDir" | Set-Content (Join-Path $OutDir 'DONE.txt')
Write-Host "ROUND4 DONE: $OutDir"
