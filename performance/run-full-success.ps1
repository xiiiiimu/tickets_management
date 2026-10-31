# Full-success path capacity benchmark (stock=200). Collection only — no config/code changes.
param(
  [string]$JMeterHome = "$env:USERPROFILE\Downloads\apache-jmeter-5.6.3\apache-jmeter-5.6.3",
  [string]$BaseUrl = "http://127.0.0.1:8082",
  [string]$JMeterHeap = "-Xms64m -Xmx192m",
  [int]$GapBetweenRunsSec = 10,
  [int]$DrainMaxSec = 180,
  # Existing fixture from init_perf_data.sql (stock=200). Do not invent broken DDL.
  [string]$SkuId = "perf_sku_risk",
  [int]$InitialStock = 200
)

$ErrorActionPreference = "Continue"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$PerfRoot = Join-Path $Root "performance"
$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$OutDir = Join-Path $PerfRoot "results\full-success-$Stamp"
$LogDir = Join-Path $OutDir "logs"
New-Item -ItemType Directory -Force -Path $OutDir, $LogDir | Out-Null

$JMeterJar = Join-Path $JMeterHome "bin\ApacheJMeter.jar"
$JavaExe = (Get-Command java -ErrorAction SilentlyContinue).Source
$ThreadLevels = @(20, 50, 100)
$Repeats = 3

function Invoke-Mysql([string]$Sql) {
  $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try { $Sql | docker exec -i credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local ticketing 2>$null | Out-Null }
  finally { $ErrorActionPreference = $prev }
}
function Invoke-MysqlQuery([string]$Sql, [string]$OutFile) {
  $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try {
    $Sql | docker exec -i credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local ticketing --table 2>$null |
      Out-File -Encoding UTF8 $OutFile
  } finally { $ErrorActionPreference = $prev }
}
function MysqlN([string]$Sql) {
  $tmp = Join-Path $env:TEMP ("mysqln-" + [guid]::NewGuid().ToString('N') + ".sql")
  Set-Content -Path $tmp -Value $Sql -Encoding ASCII
  $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try {
    $out = Get-Content $tmp -Raw | docker exec -i credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local ticketing -N 2>$null
    $line = (@($out) | Where-Object { $_ -and $_ -notmatch 'Warning' } | Select-Object -First 1)
    if (-not $line) { return 'NA' }
    return ([string]$line).Trim()
  } finally {
    $ErrorActionPreference = $prev
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
  }
}
function MysqlStatus([string]$Name) {
  $v = MysqlN "SHOW GLOBAL STATUS LIKE '$Name';"
  if ($v -eq 'NA' -or -not $v) { return 'NA' }
  if ($v -match '(\d+)\s*$') { return [long]$Matches[1] }
  $parts = ($v -split '\s+')
  if ($parts.Count -ge 2 -and $parts[-1] -match '^\d+$') { return [long]$parts[-1] }
  return 'NA'
}
function Prom([string]$Name) {
  try { Invoke-WebRequest "$BaseUrl/actuator/prometheus" -UseBasicParsing -TimeoutSec 10 -OutFile (Join-Path $OutDir "prometheus-$Name.txt") }
  catch { "capture_failed=$($_.Exception.Message)" | Set-Content (Join-Path $OutDir "prometheus-$Name.txt") }
}
function PromVal([string]$File, [string]$Regex) {
  if (-not (Test-Path $File)) { return 'NA' }
  $m = [regex]::Match((Get-Content $File -Raw), $Regex)
  if ($m.Success) { return $m.Groups[1].Value }
  return 'NA'
}
function AsDouble($v) {
  if ($null -eq $v -or $v -eq '' -or $v -eq 'NA') { return $null }
  $d = 0.0
  if ([double]::TryParse([string]$v, [ref]$d)) { return $d }
  return $null
}
function TimeoutDelta($before, $after) {
  $b = AsDouble $before; $a = AsDouble $after
  if ($null -eq $b -or $null -eq $a) { return 'NA' }
  if ($a -lt $b) { return "COUNTER_RESET(before=$b,after=$a)" }
  return [Math]::Round($a - $b, 0)
}
function DeltaNA($b,$a) {
  if ($b -eq 'NA' -or $a -eq 'NA') { return 'NA' }
  return ([long]$a - [long]$b)
}
function DeltaProm($b,$a) {
  $bd = AsDouble $b; $ad = AsDouble $a
  if ($null -eq $bd -or $null -eq $ad) { return 'NA' }
  if ($ad -lt $bd) { return 'COUNTER_RESET' }
  return [Math]::Round($ad - $bd, 0)
}

function Summarize-Jtl([string]$JtlPath) {
  if (-not (Test-Path $JtlPath) -or (Get-Item $JtlPath).Length -eq 0) { return $null }
  $rows = @(Import-Csv $JtlPath)
  $sys=0; $sockTo=0; $http503=0; $otherSys=0; $http200=0; $biz409=0; $bizOther=0
  $latAll = New-Object System.Collections.Generic.List[int]
  foreach ($row in $rows) {
    $code=0; [void][int]::TryParse([string]$row.responseCode,[ref]$code)
    $el=0; [void][int]::TryParse([string]$row.elapsed,[ref]$el)
    $msg=[string]$row.responseMessage
    if ($code -eq 200) { $http200++; $latAll.Add($el); continue }
    if ($code -eq 409) { $biz409++; $latAll.Add($el); continue }
    if ($code -in 400,403,429) { $bizOther++; $latAll.Add($el); continue }
    if ($code -eq 503) { $http503++; $sys++; continue }
    if ($msg -match 'SocketTimeout' -or $code -eq 0) { $sockTo++; $sys++; continue }
    $otherSys++; $sys++
  }
  function Pct($list,[double]$p) {
    if ($list.Count -eq 0) { return 'NA' }
    $sorted=@($list|Sort-Object)
    $idx=[Math]::Min($sorted.Count-1,[Math]::Ceiling($sorted.Count*$p)-1)
    if ($idx -lt 0) { $idx=0 }
    return $sorted[$idx]
  }
  function Avg($list) {
    if ($list.Count -eq 0) { return 'NA' }
    return [Math]::Round(($list|Measure-Object -Average).Average,2)
  }
  $completion = $http200 + $biz409 + $bizOther
  $times = @($rows | ForEach-Object { [long]$_.timeStamp } | Sort-Object)
  $dur = 0.001
  if ($times.Count -ge 2) { $dur = [Math]::Max(0.001, ($times[-1]-$times[0])/1000.0) }
  if ($rows.Count -gt 1 -and $dur -lt 0.05 -and $latAll.Count -gt 0) {
    $dur = [Math]::Max(0.05, (($times[-1]-$times[0]) + (Avg $latAll))/1000.0)
  }
  return [pscustomobject]@{
    requests=$rows.Count; http_200=$http200; http_409=$biz409; http_503=$http503
    socket_timeout=$sockTo; other_errors=$otherSys; system_errors=$sys
    completion_count=$completion
    completion_rate=[Math]::Round(100.0*$completion/[Math]::Max(1,$rows.Count),2)
    system_error_rate=[Math]::Round(100.0*$sys/[Math]::Max(1,$rows.Count),2)
    throughput_rps=[Math]::Round($rows.Count/$dur,2)
    successful_reserve_qps=[Math]::Round($http200/$dur,2)
    avg_ms=(Avg $latAll); p50_ms=(Pct $latAll 0.5); p90_ms=(Pct $latAll 0.9)
    p95_ms=(Pct $latAll 0.95); p99_ms=(Pct $latAll 0.99)
    max_ms=if($latAll.Count -eq 0){'NA'}else{(@($latAll|Sort-Object)[-1])}
  }
}

function Login-Users([int]$FromId,[int]$ToId,[string]$CsvPath) {
  $lines=@("userId,token,deviceId")
  for ($id=$FromId; $id -le $ToId; $id++) {
    # Skip perf BLOCK-profile fixture user (used by risk_profile_block scenario).
    if ($id -eq 90100) { continue }
    $phone = "1399{0:D7}" -f ($id - 90000)
    $code = (Invoke-RestMethod -Method Post "$BaseUrl/api/user/code?phone=$phone").data
    $token = (Invoke-RestMethod -Method Post "$BaseUrl/api/user/login" -ContentType "application/json" -Body (@{phone=$phone;code=$code}|ConvertTo-Json)).data
    $me = Invoke-RestMethod "$BaseUrl/api/user/me" -Headers @{authorization=$token}
    $uid=[int]$me.data.id
    $lines += "$uid,$token,perf-device-$uid"
  }
  $lines | Set-Content -Encoding UTF8 $CsvPath
}

function Ensure-Sku {
  # Reinstate fixture row if wiped; columns match schema.sql / init_perf_data.sql
  Invoke-Mysql @"
INSERT INTO tb_event(id,name,description,venue,status) VALUES('perf_event','PERF 压测演出','performance fixture','Perf Arena','ON_SALE')
  ON DUPLICATE KEY UPDATE status='ON_SALE';
INSERT INTO tb_event_session(id,event_id,starts_at) VALUES('perf_session','perf_event','2027-12-31 19:30:00')
  ON DUPLICATE KEY UPDATE event_id='perf_event';
INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,sale_start_time,sale_end_time,purchase_limit,status,version)
VALUES('$SkuId','perf_session','FULL_SUCCESS',10.00,$InitialStock,$InitialStock,'2020-01-01 00:00:00','2030-12-31 23:59:59',10,'ON_SALE',0)
ON DUPLICATE KEY UPDATE total_stock=$InitialStock, remaining_stock=$InitialStock, purchase_limit=10, status='ON_SALE', version=version+1;
"@
}

function Reset-Sku {
  Invoke-Mysql @"
DELETE FROM tb_issued_ticket WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='$SkuId') x);
DELETE FROM tb_payment_transaction WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='$SkuId') x);
DELETE FROM tb_wallet_ledger WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='$SkuId') x);
DELETE FROM tb_risk_decision WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='$SkuId') x);
DELETE FROM tb_outbox_message WHERE aggregate_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='$SkuId') x);
DELETE FROM tb_ticket_order WHERE sku_id='$SkuId';
DELETE FROM tb_reservation WHERE sku_id='$SkuId';
DELETE FROM tb_reserve_request WHERE sku_id='$SkuId';
DELETE FROM tb_user_purchase WHERE sku_id='$SkuId';
UPDATE tb_ticket_sku SET remaining_stock=total_stock, version=version+1 WHERE id='$SkuId';
"@
  docker exec credit_risk-ticket-redis-1 redis-cli EVAL "local k=redis.call('keys','*$SkuId*'); for i=1,#k do redis.call('del',k[i]) end; return #k" 0 2>$null | Out-Null
}

function Warm-Sku([string]$UsersCsv) {
  # Cold ensureReady once (writes Redis config+stock from DB), then reset business rows
  # while keeping Redis config and aligning stock — same pattern as Round1/Final warm path.
  $u = Import-Csv $UsersCsv | Select-Object -First 1
  $body = @{ requestId="warm-fs-$Stamp-$(Get-Random)"; userId=[int]$u.userId; eventSessionId='perf_session'; ticketSkuId=$SkuId; quantity=1; deviceId=$u.deviceId } | ConvertTo-Json
  try { Invoke-RestMethod -Method Post "$BaseUrl/api/tickets/reserve" -Headers @{authorization=$u.token;'Content-Type'='application/json'} -Body $body -TimeoutSec 60 | Out-Null } catch {}
  Invoke-Mysql @"
DELETE FROM tb_issued_ticket WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='$SkuId') x);
DELETE FROM tb_payment_transaction WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='$SkuId') x);
DELETE FROM tb_wallet_ledger WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='$SkuId') x);
DELETE FROM tb_risk_decision WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='$SkuId') x);
DELETE FROM tb_outbox_message WHERE aggregate_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='$SkuId') x);
DELETE FROM tb_ticket_order WHERE sku_id='$SkuId';
DELETE FROM tb_reservation WHERE sku_id='$SkuId';
DELETE FROM tb_reserve_request WHERE sku_id='$SkuId';
DELETE FROM tb_user_purchase WHERE sku_id='$SkuId';
UPDATE tb_ticket_sku SET remaining_stock=total_stock, version=version+1 WHERE id='$SkuId';
"@
  docker exec credit_risk-ticket-redis-1 redis-cli SET "ticket:{$SkuId}:stock" "$InitialStock" 2>$null | Out-Null
  docker exec credit_risk-ticket-redis-1 redis-cli DEL "ticket:{$SkuId}:pending" 2>$null | Out-Null
  docker exec credit_risk-ticket-redis-1 redis-cli EVAL "local k=redis.call('keys','ticket:{$SkuId}:user:*'); for i=1,#k do redis.call('del',k[i]) end; local r=redis.call('keys','ticket:{$SkuId}:reservation:*'); for i=1,#r do redis.call('del',r[i]) end; return 1" 0 2>$null | Out-Null
}

function Get-BadRows([string]$ValFile) {
  if (-not (Test-Path $ValFile)) { return 'NA' }
  $sum=0
  foreach ($m in [regex]::Matches((Get-Content $ValFile -Raw), '(?ms)\|\s*(NEG_STOCK|ORDERS_OVER_STOCK|DUP_REQUEST_RESERVATION|DUP_RESERVATION_ORDER|DUP_PAYMENT_SUCCESS|DUP_ISSUED_SEQ|PURCHASE_OVER_LIMIT|STOCK_CONSISTENCY|INVALID_ORDER_STATE)\s*\|\s*(\d+)\s*\|')) {
    $sum += [int]$m.Groups[2].Value
  }
  return $sum
}
function Get-Metric([string]$ValFile,[string]$Name) {
  if (-not (Test-Path $ValFile)) { return 'NA' }
  $m = [regex]::Match((Get-Content $ValFile -Raw), "(?ms)\|\s*$Name\s*\|\s*(\d+)\s*\|")
  if ($m.Success) { return $m.Groups[1].Value }
  return 'NA'
}

function Wait-Clear([string]$Tag) {
  $peakQ=0.0; $peakA=0.0; $peakP=0.0
  $deadline=(Get-Date).AddSeconds($DrainMaxSec)
  $stable=0
  while ((Get-Date) -lt $deadline) {
    Prom "drain-$Tag-$(Get-Date -Format HHmmssfff)"
    $latest = Get-ChildItem $OutDir -Filter "prometheus-drain-$Tag-*.txt" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $q = AsDouble (PromVal $latest.FullName 'threadpool_queue\{pool="autopay",\} ([0-9.]+)')
    $a = AsDouble (PromVal $latest.FullName 'threadpool_active\{pool="autopay",\} ([0-9.]+)')
    $p = AsDouble (PromVal $latest.FullName 'hikaricp_connections_pending\{pool="HikariPool-1",\} ([0-9.]+)')
    if ($null -ne $q -and $q -gt $peakQ) { $peakQ=$q }
    if ($null -ne $a -and $a -gt $peakA) { $peakA=$a }
    if ($null -ne $p -and $p -gt $peakP) { $peakP=$p }
    $waiting = MysqlN "SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id='$SkuId' AND status='WAITING_PAYMENT';"
    $paying = MysqlN "SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id='$SkuId' AND status='PAYING';"
    $outbox = MysqlN "SELECT COUNT(*) FROM tb_outbox_message WHERE status IN ('NEW','PENDING','SENDING') AND aggregate_id IN (SELECT id FROM tb_ticket_order WHERE sku_id='$SkuId');"
    $qOk = ($null -eq $q -or $q -eq 0); $aOk = ($null -eq $a -or $a -eq 0); $pOk = ($null -eq $p -or $p -le 0)
    if ($qOk -and $aOk -and $pOk -and $waiting -eq '0' -and $paying -eq '0' -and ($outbox -eq '0' -or $outbox -eq 'NA')) {
      $stable++; if ($stable -ge 2) { break }
    } else { $stable=0 }
    Start-Sleep 2
  }
  Prom "drain-final-$Tag"
  return [pscustomobject]@{ queue_peak=$peakQ; active_peak=$peakA; pending_peak=$peakP }
}

# ---- env gate ----
Prom 'env-check'
$hikari = PromVal (Join-Path $OutDir 'prometheus-env-check.txt') 'hikaricp_connections_max\{pool="HikariPool-1",\} ([0-9.]+)'
$ac = PromVal (Join-Path $OutDir 'prometheus-env-check.txt') 'threadpool_core\{pool="autopay",\} ([0-9.]+)'
$am = PromVal (Join-Path $OutDir 'prometheus-env-check.txt') 'threadpool_max\{pool="autopay",\} ([0-9.]+)'
$mem = docker inspect credit_risk-ticket-risk-platform-1 --format '{{.HostConfig.Memory}}' 2>$null
$jvm = (docker inspect credit_risk-ticket-risk-platform-1 --format '{{range .Config.Env}}{{println .}}{{end}}' 2>$null | Select-String 'JAVA_TOOL' | ForEach-Object { $_.Line })
if ($hikari -ne '10.0' -or $ac -ne '2.0' -or $am -ne '4.0' -or $mem -ne '805306368' -or "$jvm" -notmatch 'Xmx384m') {
  "DIFFERENT_ENVIRONMENT hikari=$hikari autopay=$ac/$am mem=$mem jvm=$jvm" | Tee-Object (Join-Path $OutDir 'DIFFERENT_ENVIRONMENT.txt')
  throw 'DIFFERENT_ENVIRONMENT'
}

@"
stamp=$Stamp
sku=$SkuId
initial_stock=$InitialStock
git_head=$(git -C $Root rev-parse HEAD)
hikari_max=$hikari
autopay_core=$ac
autopay_max=$am
platform_memory_bytes=$mem
jvm=$jvm
jmeter=5.6.3
timeout_sec=30
base_url=$BaseUrl
"@ | Set-Content -Encoding UTF8 (Join-Path $OutDir 'environment.txt')

for ($i=0;$i -lt 30;$i++) {
  try { if ((Invoke-RestMethod "$BaseUrl/actuator/health" -TimeoutSec 3).status -eq 'UP') { break } } catch {}
  Start-Sleep 2
}

Get-Content (Join-Path $PerfRoot 'sql\init_perf_data.sql') -Raw | docker exec -i credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local ticketing 2>$null | Out-Null
Ensure-Sku
Invoke-Mysql "UPDATE tb_risk_rule SET config=JSON_SET(config,'$.threshold',100000) WHERE rule_code IN ('IP_FREQUENCY','DEVICE_FREQUENCY','USER_FREQUENCY');"

$usersCsv = Join-Path $OutDir 'users.csv'
Write-Host 'Login 120 users...'
Login-Users 90001 90120 $usersCsv
Invoke-Mysql "INSERT INTO tb_wallet_account(user_id,available_balance,frozen_balance,version) SELECT id,10000,0,0 FROM tb_user WHERE phone LIKE '1399%' ON DUPLICATE KEY UPDATE available_balance=GREATEST(available_balance,10000);"

$plan = Join-Path $PerfRoot 'jmeter\stock_contention.jmx'
$rowsOut = New-Object System.Collections.Generic.List[object]

foreach ($t in $ThreadLevels) {
  for ($r=1; $r -le $Repeats; $r++) {
    $name = "full_t${t}_r$r"
    Write-Host "=== $name ==="
    $null = Wait-Clear "pre-$name"
    Reset-Sku
    Warm-Sku $usersCsv

    $lwB = MysqlStatus 'Innodb_row_lock_waits'
    $ltB = MysqlStatus 'Innodb_row_lock_time'
    $ltmB = MysqlStatus 'Innodb_row_lock_time_max'
    Prom "before-$name"
    $toB = PromVal (Join-Path $OutDir "prometheus-before-$name.txt") 'hikaricp_connections_timeout_total\{pool="HikariPool-1",\} ([0-9.]+)'
    $compB = PromVal (Join-Path $OutDir "prometheus-before-$name.txt") 'threadpool_completed\{pool="autopay",\} ([0-9.]+)'
    $rejB = PromVal (Join-Path $OutDir "prometheus-before-$name.txt") 'threadpool_reject\{pool="autopay",\} ([0-9.]+)'
    $succB = PromVal (Join-Path $OutDir "prometheus-before-$name.txt") 'payment_auto_success_total ([0-9.]+)'
    $failB = PromVal (Join-Path $OutDir "prometheus-before-$name.txt") 'payment_auto_failure_total ([0-9.]+)'

    $jtl = Join-Path $OutDir "$name.jtl"
    $log = Join-Path $LogDir "$name.log"
    $argList = @($JMeterHeap.Split(' ') + @('-jar',$JMeterJar,'-n','-t',$plan,'-l',$jtl,'-j',$log,
      '-Jhost=127.0.0.1','-Jport=8082',"-Jthreads=$t",'-Jramp=2','-Jloops=1',
      "-Jusers=$usersCsv","-JskuId=$SkuId",'-JsessionId=perf_session'))
    $proc = Start-Process -FilePath $JavaExe -ArgumentList $argList -PassThru -NoNewWindow
    Start-Sleep 2
    Prom "during-$name"
    $pendingDuring = PromVal (Join-Path $OutDir "prometheus-during-$name.txt") 'hikaricp_connections_pending\{pool="HikariPool-1",\} ([0-9.]+)'
    $activeDuring = PromVal (Join-Path $OutDir "prometheus-during-$name.txt") 'hikaricp_connections_active\{pool="HikariPool-1",\} ([0-9.]+)'
    $idleDuring = PromVal (Join-Path $OutDir "prometheus-during-$name.txt") 'hikaricp_connections_idle\{pool="HikariPool-1",\} ([0-9.]+)'
    $qDuring = PromVal (Join-Path $OutDir "prometheus-during-$name.txt") 'threadpool_queue\{pool="autopay",\} ([0-9.]+)'
    $aDuring = PromVal (Join-Path $OutDir "prometheus-during-$name.txt") 'threadpool_active\{pool="autopay",\} ([0-9.]+)'
    Wait-Process -Id $proc.Id -Timeout 300 -ErrorAction SilentlyContinue
    if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }

    Prom "after-$name"
    $drain = Wait-Clear $name

    $val = Join-Path $OutDir "validation-$name.txt"
    Invoke-MysqlQuery @"
SELECT 'NEG_STOCK' AS check_name, COUNT(*) AS bad_rows FROM tb_ticket_sku WHERE id='$SkuId' AND (remaining_stock<0 OR remaining_stock>total_stock);
SELECT 'ORDERS_OVER_STOCK' AS check_name, COUNT(*) AS bad_rows FROM (
  SELECT s.id FROM tb_ticket_sku s
  LEFT JOIN tb_ticket_order o ON o.sku_id=s.id AND o.status NOT IN ('CANCELLED','PAY_TIMEOUT','RISK_BLOCKED')
  WHERE s.id='$SkuId' GROUP BY s.id,s.total_stock
  HAVING COALESCE(SUM(o.quantity),0)>s.total_stock
) t;
SELECT 'DUP_REQUEST_RESERVATION' AS check_name, COUNT(*) AS bad_rows FROM (
  SELECT request_id FROM tb_reservation WHERE sku_id='$SkuId' GROUP BY request_id HAVING COUNT(*)>1
) t;
SELECT 'DUP_RESERVATION_ORDER' AS check_name, COUNT(*) AS bad_rows FROM (
  SELECT reservation_id FROM tb_ticket_order WHERE sku_id='$SkuId' GROUP BY reservation_id HAVING COUNT(*)>1
) t;
SELECT 'DUP_PAYMENT_SUCCESS' AS check_name, COUNT(*) AS bad_rows FROM (
  SELECT order_id FROM tb_payment_transaction
  WHERE order_id IN (SELECT id FROM tb_ticket_order WHERE sku_id='$SkuId') AND status='SUCCESS' AND type='PAYMENT'
  GROUP BY order_id HAVING COUNT(*)>1
) t;
SELECT 'DUP_ISSUED_SEQ' AS check_name, COUNT(*) AS bad_rows FROM (
  SELECT order_id,sequence_no FROM tb_issued_ticket
  WHERE order_id IN (SELECT id FROM tb_ticket_order WHERE sku_id='$SkuId')
  GROUP BY order_id,sequence_no HAVING COUNT(*)>1
) t;
SELECT 'PURCHASE_OVER_LIMIT' AS check_name, COUNT(*) AS bad_rows
FROM tb_user_purchase p JOIN tb_ticket_sku s ON s.id=p.sku_id
WHERE s.id='$SkuId' AND p.quantity>s.purchase_limit;
SELECT 'INVALID_ORDER_STATE' AS check_name, COUNT(*) AS bad_rows
FROM tb_ticket_order WHERE sku_id='$SkuId'
  AND status NOT IN ('WAITING_PAYMENT','PAYING','PAID','ISSUED','CANCELLED','PAY_TIMEOUT','RISK_BLOCKED');
SELECT 'METRIC_REMAINING' AS metric, remaining_stock AS value FROM tb_ticket_sku WHERE id='$SkuId';
SELECT 'METRIC_TOTAL' AS metric, total_stock AS value FROM tb_ticket_sku WHERE id='$SkuId';
SELECT 'RESERVATION_COUNT' AS metric, COUNT(*) AS value FROM tb_reservation WHERE sku_id='$SkuId';
SELECT 'ORDER_COUNT' AS metric, COUNT(*) AS value FROM tb_ticket_order WHERE sku_id='$SkuId';
SELECT 'WAITING_PAYMENT' AS metric, COUNT(*) AS value FROM tb_ticket_order WHERE sku_id='$SkuId' AND status='WAITING_PAYMENT';
SELECT 'PAID_ORDERS' AS metric, COUNT(*) AS value FROM tb_ticket_order WHERE sku_id='$SkuId' AND status='PAID';
SELECT 'ISSUED_ORDERS' AS metric, COUNT(*) AS value FROM tb_ticket_order WHERE sku_id='$SkuId' AND status='ISSUED';
SELECT 'PAID_OR_ISSUED' AS metric, COUNT(*) AS value FROM tb_ticket_order WHERE sku_id='$SkuId' AND status IN ('PAID','ISSUED');
SELECT 'PAYMENT_SUCCESS' AS metric, COUNT(*) AS value FROM tb_payment_transaction p JOIN tb_ticket_order o ON o.id=p.order_id WHERE o.sku_id='$SkuId' AND p.status='SUCCESS';
SELECT 'FAILED_PAYMENT' AS metric, COUNT(*) AS value FROM tb_payment_transaction p JOIN tb_ticket_order o ON o.id=p.order_id WHERE o.sku_id='$SkuId' AND p.status<>'SUCCESS';
"@ $val

    $sum = Summarize-Jtl $jtl
    if ($null -eq $sum) { Write-Warning "Missing JTL $name"; continue }

    $toA = PromVal (Join-Path $OutDir "prometheus-after-$name.txt") 'hikaricp_connections_timeout_total\{pool="HikariPool-1",\} ([0-9.]+)'
    $acq = PromVal (Join-Path $OutDir "prometheus-after-$name.txt") 'hikaricp_connections_acquire_seconds_max\{pool="HikariPool-1",\} ([0-9.eE+-]+)'
    $usage = PromVal (Join-Path $OutDir "prometheus-after-$name.txt") 'hikaricp_connections_usage_seconds_max\{pool="HikariPool-1",\} ([0-9.eE+-]+)'
    $pendingAfter = PromVal (Join-Path $OutDir "prometheus-after-$name.txt") 'hikaricp_connections_pending\{pool="HikariPool-1",\} ([0-9.]+)'
    $compA = PromVal (Join-Path $OutDir "prometheus-drain-final-$name.txt") 'threadpool_completed\{pool="autopay",\} ([0-9.]+)'
    $rejA = PromVal (Join-Path $OutDir "prometheus-drain-final-$name.txt") 'threadpool_reject\{pool="autopay",\} ([0-9.]+)'
    $succA = PromVal (Join-Path $OutDir "prometheus-drain-final-$name.txt") 'payment_auto_success_total ([0-9.]+)'
    $failA = PromVal (Join-Path $OutDir "prometheus-drain-final-$name.txt") 'payment_auto_failure_total ([0-9.]+)'
    $qFinal = PromVal (Join-Path $OutDir "prometheus-drain-final-$name.txt") 'threadpool_queue\{pool="autopay",\} ([0-9.]+)'
    $poolSize = PromVal (Join-Path $OutDir "prometheus-drain-final-$name.txt") 'threadpool_max\{pool="autopay",\} ([0-9.]+)'

    $lwA = MysqlStatus 'Innodb_row_lock_waits'
    $ltA = MysqlStatus 'Innodb_row_lock_time'
    $ltmA = MysqlStatus 'Innodb_row_lock_time_max'
    $tcA = MysqlStatus 'Threads_connected'
    $trA = MysqlStatus 'Threads_running'

    $pendingPeak = 0.0
    foreach ($pv in @($pendingDuring,$pendingAfter,$drain.pending_peak)) {
      $d=AsDouble $pv; if ($null -ne $d -and $d -gt $pendingPeak) { $pendingPeak=$d }
    }
    $qPeak = $drain.queue_peak
    $qd = AsDouble $qDuring; if ($null -ne $qd -and $qd -gt $qPeak) { $qPeak=$qd }
    $aPeak = $drain.active_peak
    $ad = AsDouble $aDuring; if ($null -ne $ad -and $ad -gt $aPeak) { $aPeak=$ad }

    $timeoutDelta = TimeoutDelta $toB $toA
    $rejDelta = DeltaProm $rejB $rejA
    $orders = Get-Metric $val 'ORDER_COUNT'
    $paidOrIssued = Get-Metric $val 'PAID_OR_ISSUED'
    $waiting = Get-Metric $val 'WAITING_PAYMENT'
    $issued = Get-Metric $val 'ISSUED_ORDERS'
    $remain = Get-Metric $val 'METRIC_REMAINING'
    $bad = Get-BadRows $val

    $expectedOrders = $t
    $httpAllOk = ($sum.http_200 -eq $t -and $sum.http_409 -eq 0 -and $sum.http_503 -eq 0 -and $sum.socket_timeout -eq 0 -and $sum.system_errors -eq 0)
    $timeoutOk = ($timeoutDelta -eq '0' -or $timeoutDelta -eq 0)
    $rejOk = ($rejDelta -eq 'NA' -or $rejDelta -eq '0' -or $rejDelta -eq 0 -or $rejDelta -eq 'COUNTER_RESET')
    if ($rejDelta -ne 'NA' -and $rejDelta -ne 'COUNTER_RESET') {
      $rd = AsDouble $rejDelta; if ($null -ne $rd -and $rd -gt 0) { $rejOk = $false }
    }
    # reject gauge absolute check
    $rejAbs = AsDouble $rejA
    if ($null -ne $rejAbs -and $rejAbs -gt 0) { $rejOk = $false }
    $finalOk = ($paidOrIssued -eq "$expectedOrders" -and $waiting -eq '0' -and $bad -eq 0 -and $qFinal -eq '0.0')
    $clean = ($httpAllOk -and $timeoutOk -and $rejOk -and $finalOk)

    $row = [pscustomobject]@{
      scenario='full_success'; concurrency=$t; run=$r; requests=$sum.requests
      http_200=$sum.http_200; http_409=$sum.http_409; http_503=$sum.http_503
      socket_timeout=$sum.socket_timeout; other_errors=$sum.other_errors; system_errors=$sum.system_errors
      completion_rate=$sum.completion_rate; system_error_rate=$sum.system_error_rate
      overall_qps=$sum.throughput_rps; successful_reserve_qps=$sum.successful_reserve_qps
      avg_ms=$sum.avg_ms; p50_ms=$sum.p50_ms; p90_ms=$sum.p90_ms; p95_ms=$sum.p95_ms; p99_ms=$sum.p99_ms; max_ms=$sum.max_ms
      reservation_count=(Get-Metric $val 'RESERVATION_COUNT'); order_count=$orders
      waiting_payment=$waiting; paid_orders=(Get-Metric $val 'PAID_ORDERS'); issued_orders=$issued
      paid_or_issued=$paidOrIssued; payment_success=(Get-Metric $val 'PAYMENT_SUCCESS')
      failed_payment=(Get-Metric $val 'FAILED_PAYMENT'); remaining_stock=$remain; bad_rows=$bad
      hikari_timeout_delta=$timeoutDelta; hikari_pending_peak=$pendingPeak; hikari_acquire_max=$acq
      hikari_usage_max=$usage; hikari_active_during=$activeDuring; hikari_idle_during=$idleDuring
      mysql_lock_waits_delta=(DeltaNA $lwB $lwA); mysql_lock_time_delta=(DeltaNA $ltB $ltA)
      mysql_lock_time_max_after=$ltmA; mysql_threads_connected=$tcA; mysql_threads_running=$trA
      autopay_pool_size=$poolSize; autopay_active_peak=$aPeak; autopay_queue_peak=$qPeak
      autopay_queue_final=$qFinal; autopay_completed_delta=(DeltaProm $compB $compA)
      autopay_rejected=$rejDelta; payment_auto_success_delta=(DeltaProm $succB $succA)
      payment_auto_failure_delta=(DeltaProm $failB $failA); clean=([int]$clean)
    }
    $rowsOut.Add($row)
    ($row.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join "`n" | Set-Content (Join-Path $OutDir "metrics-$name.txt")
    Start-Sleep $GapBetweenRunsSec
  }
}

$rowsOut | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $OutDir 'full_success_summary.csv')
$rowsOut | Format-Table concurrency,run,http_200,http_409,http_503,socket_timeout,overall_qps,successful_reserve_qps,p95_ms,p99_ms,paid_or_issued,remaining_stock,bad_rows,clean -AutoSize |
  Out-String | Tee-Object (Join-Path $OutDir 'summary_table.txt') | Write-Host
"out_dir=$OutDir" | Set-Content (Join-Path $OutDir 'DONE.txt')
Write-Host "FULL_SUCCESS DONE: $OutDir"
