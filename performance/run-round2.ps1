# Round-2 performance validation: async auto-pay only. Same env as Round1 (Hikari max=10, heap 384m).
# Runs 20x3 + 50x3 stock_contention; waits for autopay drain after each JMeter run.
param(
  [string]$JMeterHome = "$env:USERPROFILE\Downloads\apache-jmeter-5.6.3\apache-jmeter-5.6.3",
  [string]$BaseUrl = "http://127.0.0.1:8082",
  [string]$JMeterHeap = "-Xms64m -Xmx160m",
  [int]$GapBetweenRunsSec = 10,
  [int]$AutopayDrainMaxSec = 90
)

$ErrorActionPreference = "Continue"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$PerfRoot = Join-Path $Root "performance"
$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$OutDir = Join-Path $PerfRoot "results\round2-$Stamp"
$LogDir = Join-Path $OutDir "logs"
New-Item -ItemType Directory -Force -Path $OutDir, $LogDir | Out-Null

$JMeterJar = Join-Path $JMeterHome "bin\ApacheJMeter.jar"
$JavaExe = (Get-Command java -ErrorAction SilentlyContinue).Source
$ThreadLevels = @(20, 50)
$Repeats = 3

function Invoke-Mysql([string]$Sql) {
  $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try { $Sql | docker exec -i credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local ticketing 2>$null | Out-Null }
  finally { $ErrorActionPreference = $prev }
}
function Invoke-MysqlFile([string]$Path, [string]$OutFile) {
  $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try {
    Get-Content $Path -Raw | docker exec -i credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local ticketing --table 2>$null |
      Out-File -Encoding UTF8 $OutFile
  } finally { $ErrorActionPreference = $prev }
}
function Prom([string]$Name) {
  try { Invoke-WebRequest "$BaseUrl/actuator/prometheus" -UseBasicParsing -TimeoutSec 10 -OutFile (Join-Path $OutDir "prometheus-$Name.txt") }
  catch { "capture_failed=$($_.Exception.Message)" | Set-Content (Join-Path $OutDir "prometheus-$Name.txt") }
}
function PromVal([string]$File, [string]$Regex) {
  if (-not (Test-Path $File)) { return '' }
  $m = [regex]::Match((Get-Content $File -Raw), $Regex)
  if ($m.Success) { return $m.Groups[1].Value }
  return ''
}
function MysqlStatus([string]$Name) {
  $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try {
    $line = docker exec credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local -N -e "SHOW GLOBAL STATUS LIKE '$Name';" 2>$null
    if ($line -match '\s(\d+)\s*$') { return [long]$Matches[1] }
  } finally { $ErrorActionPreference = $prev }
  return 0
}
function MysqlScalar([string]$Sql) {
  $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try {
    $line = docker exec credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local -N -e $Sql 2>$null
    if ($null -eq $line) { return '' }
    return ([string]$line).Trim()
  } finally { $ErrorActionPreference = $prev }
}

function Summarize-Jtl([string]$JtlPath, $Concurrency, $RunIndex) {
  if (-not (Test-Path $JtlPath) -or (Get-Item $JtlPath).Length -eq 0) {
    return [pscustomobject]@{ scenario='stock_contention'; concurrency=$Concurrency; run_index=$RunIndex; requests=''; success=''; business_rejected=''; system_errors=''; error_rate=''; throughput_rps=''; avg_ms=''; p50_ms=''; p95_ms=''; p99_ms=''; max_ms=''; bad_rows=''; host_status='MISSING_JTL'; note='' }
  }
  $rows = @(Import-Csv $JtlPath)
  $elapsed = @($rows | ForEach-Object { [int]$_.elapsed }) | Sort-Object
  function Pct([double]$p) { if ($elapsed.Count -eq 0) { return '' }; $idx = [Math]::Min($elapsed.Count-1, [Math]::Ceiling($elapsed.Count*$p)-1); if ($idx -lt 0) { $idx = 0 }; return $elapsed[$idx] }
  $ok=0; $biz=0; $sys=0; $timeouts=0
  foreach ($row in $rows) {
    $code=0; [void][int]::TryParse([string]$row.responseCode, [ref]$code)
    if ($row.success -eq 'true') { $ok++ }
    elseif ($code -in 400,403,409,429) { $biz++ }
    else { $sys++ }
    if ($row.responseMessage -match 'SocketTimeout' -or $code -eq 0) { $timeouts++ }
  }
  $times = @($rows | ForEach-Object { [long]$_.timeStamp } | Sort-Object)
  $dur = [Math]::Max(0.001, ($times[-1]-$times[0])/1000.0)
  if ($rows.Count -gt 1 -and $dur -lt 0.05) { $dur = [Math]::Max(0.05, (($times[-1]-$times[0]) + ($elapsed|Measure-Object -Average).Average)/1000.0) }
  return [pscustomobject]@{
    scenario='stock_contention'; concurrency=$Concurrency; run_index=$RunIndex
    requests=$rows.Count; success=$ok; business_rejected=$biz; system_errors=$sys
    error_rate=[Math]::Round(100.0*($biz+$sys)/[Math]::Max(1,$rows.Count),2)
    throughput_rps=[Math]::Round($rows.Count/$dur,2)
    avg_ms=[Math]::Round(($elapsed|Measure-Object -Average).Average,2)
    p50_ms=(Pct 0.5); p95_ms=(Pct 0.95); p99_ms=(Pct 0.99); max_ms=($elapsed|Select-Object -Last 1)
    timeout_count=$timeouts
    bad_rows=''; host_status='OK'; note=''
  }
}

function Login-Users([int]$FromId, [int]$ToId, [string]$CsvPath) {
  $lines = @("userId,token,deviceId")
  for ($id=$FromId; $id -le $ToId; $id++) {
    $phone = "1399{0:D7}" -f ($id - 90000)
    $code = (Invoke-RestMethod -Method Post "$BaseUrl/api/user/code?phone=$phone").data
    $token = (Invoke-RestMethod -Method Post "$BaseUrl/api/user/login" -ContentType "application/json" -Body (@{phone=$phone;code=$code}|ConvertTo-Json)).data
    $me = Invoke-RestMethod "$BaseUrl/api/user/me" -Headers @{authorization=$token}
    $lines += "$([int]$me.data.id),$token,perf-device-$([int]$me.data.id)"
  }
  $lines | Set-Content -Encoding UTF8 $CsvPath
}

function Reset-Stock20 {
  Invoke-Mysql @"
DELETE FROM tb_issued_ticket WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='perf_sku_stock20') x);
DELETE FROM tb_payment_transaction WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='perf_sku_stock20') x);
DELETE FROM tb_wallet_ledger WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='perf_sku_stock20') x);
DELETE FROM tb_risk_decision WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='perf_sku_stock20') x);
DELETE FROM tb_outbox_message WHERE aggregate_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='perf_sku_stock20') x);
DELETE FROM tb_ticket_order WHERE sku_id='perf_sku_stock20';
DELETE FROM tb_reservation WHERE sku_id='perf_sku_stock20';
DELETE FROM tb_reserve_request WHERE sku_id='perf_sku_stock20';
DELETE FROM tb_user_purchase WHERE sku_id='perf_sku_stock20';
UPDATE tb_ticket_sku SET remaining_stock=total_stock, version=version+1 WHERE id='perf_sku_stock20';
"@
  docker exec credit_risk-ticket-redis-1 redis-cli EVAL "local k=redis.call('keys','*perf_sku_stock20*'); for i=1,#k do redis.call('del',k[i]) end; return #k" 0 2>$null | Out-Null
}

function Warm-Sku([string]$UsersCsv) {
  $u = Import-Csv $UsersCsv | Select-Object -First 1
  $body = @{ requestId = "warm-$Stamp-$(Get-Random)"; userId = [int]$u.userId; eventSessionId = 'perf_session'; ticketSkuId = 'perf_sku_stock20'; quantity = 1; deviceId = $u.deviceId } | ConvertTo-Json
  try {
    Invoke-RestMethod -Method Post "$BaseUrl/api/tickets/reserve" -Headers @{ authorization = $u.token; 'Content-Type' = 'application/json' } -Body $body -TimeoutSec 60 | Out-Null
  } catch {}
  Invoke-Mysql @"
DELETE FROM tb_issued_ticket WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='perf_sku_stock20') x);
DELETE FROM tb_payment_transaction WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='perf_sku_stock20') x);
DELETE FROM tb_wallet_ledger WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='perf_sku_stock20') x);
DELETE FROM tb_risk_decision WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='perf_sku_stock20') x);
DELETE FROM tb_outbox_message WHERE aggregate_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='perf_sku_stock20') x);
DELETE FROM tb_ticket_order WHERE sku_id='perf_sku_stock20';
DELETE FROM tb_reservation WHERE sku_id='perf_sku_stock20';
DELETE FROM tb_reserve_request WHERE sku_id='perf_sku_stock20';
DELETE FROM tb_user_purchase WHERE sku_id='perf_sku_stock20';
UPDATE tb_ticket_sku SET remaining_stock=total_stock, version=version+1 WHERE id='perf_sku_stock20';
"@
  docker exec credit_risk-ticket-redis-1 redis-cli SET "ticket:{perf_sku_stock20}:stock" "20" 2>$null | Out-Null
  docker exec credit_risk-ticket-redis-1 redis-cli DEL "ticket:{perf_sku_stock20}:pending" 2>$null | Out-Null
  docker exec credit_risk-ticket-redis-1 redis-cli EVAL "local k=redis.call('keys','ticket:{perf_sku_stock20}:user:*'); for i=1,#k do redis.call('del',k[i]) end; local r=redis.call('keys','ticket:{perf_sku_stock20}:reservation:*'); for i=1,#r do redis.call('del',r[i]) end; return 1" 0 2>$null | Out-Null
}

function Get-BadRows([string]$ValFile) {
  if (-not (Test-Path $ValFile)) { return '' }
  $sum = 0
  foreach ($m in [regex]::Matches((Get-Content $ValFile -Raw), '(?ms)\|\s*(NEG_STOCK|ORDERS_OVER_STOCK|DUP_REQUEST_RESERVATION|DUP_RESERVATION_ORDER|DUP_PAYMENT_SUCCESS|DUP_ISSUED_SEQ|PURCHASE_OVER_LIMIT|STOCK_CONSISTENCY)\s*\|\s*(\d+)\s*\|')) {
    $sum += [int]$m.Groups[2].Value
  }
  return $sum
}

function Get-AutopaySnapshot([string]$PromFile) {
  return [pscustomobject]@{
    pool_size = (PromVal $PromFile 'threadpool_max\{pool="autopay",\} ([0-9.]+)')
    active = (PromVal $PromFile 'threadpool_active\{pool="autopay",\} ([0-9.]+)')
    queue = (PromVal $PromFile 'threadpool_queue\{pool="autopay",\} ([0-9.]+)')
    completed = (PromVal $PromFile 'threadpool_completed\{pool="autopay",\} ([0-9.]+)')
    rejected_gauge = (PromVal $PromFile 'threadpool_reject\{pool="autopay",\} ([0-9.]+)')
    rejected_counter = (PromVal $PromFile 'payment_auto_rejected_total ([0-9.]+)')
    attempt = (PromVal $PromFile 'payment_auto_attempt_total ([0-9.]+)')
    success = (PromVal $PromFile 'payment_auto_success_total ([0-9.]+)')
    failure = (PromVal $PromFile 'payment_auto_failure_total ([0-9.]+)')
  }
}

function Wait-AutopayDrain([string]$Name) {
  $peakQueue = 0.0
  $peakActive = 0.0
  $deadline = (Get-Date).AddSeconds($AutopayDrainMaxSec)
  $stable = 0
  while ((Get-Date) -lt $deadline) {
    Prom "drain-$Name-$(Get-Date -Format HHmmss)"
    $latest = Get-ChildItem $OutDir -Filter "prometheus-drain-$Name-*.txt" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $snap = Get-AutopaySnapshot $latest.FullName
    $q = 0.0; [void][double]::TryParse([string]$snap.queue, [ref]$q)
    $a = 0.0; [void][double]::TryParse([string]$snap.active, [ref]$a)
    if ($q -gt $peakQueue) { $peakQueue = $q }
    if ($a -gt $peakActive) { $peakActive = $a }
    $waiting = MysqlScalar "SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id='perf_sku_stock20' AND status='WAITING_PAYMENT';"
    $paying = MysqlScalar "SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id='perf_sku_stock20' AND status='PAYING';"
    if ($q -eq 0 -and $a -eq 0 -and [string]$waiting -eq '0' -and [string]$paying -eq '0') {
      $stable++
      if ($stable -ge 2) { break }
    } else { $stable = 0 }
    Start-Sleep 2
  }
  Prom "drain-final-$Name"
  $final = Get-AutopaySnapshot (Join-Path $OutDir "prometheus-drain-final-$Name.txt")
  $paid = MysqlScalar "SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id='perf_sku_stock20' AND status IN ('PAID','ISSUED');"
  $waitingFinal = MysqlScalar "SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id='perf_sku_stock20' AND status='WAITING_PAYMENT';"
  $paySuccess = MysqlScalar "SELECT COUNT(*) FROM tb_payment_transaction p JOIN tb_ticket_order o ON o.id=p.order_id WHERE o.sku_id='perf_sku_stock20' AND p.status='SUCCESS';"
  return [pscustomobject]@{
    queue_peak=$peakQueue; active_peak=$peakActive
    queue_final=$final.queue; active_final=$final.active; completed=$final.completed
    rejected_gauge=$final.rejected_gauge; rejected_counter=$final.rejected_counter
    paid_or_issued=$paid; waiting_payment=$waitingFinal; payment_success=$paySuccess
    attempt=$final.attempt; success_metric=$final.success; failure_metric=$final.failure
  }
}

function TimeoutDelta([double]$Before, [double]$After) {
  if ($After -lt $Before) { return "COUNTER_RESET(before=$Before,after=$After)" }
  return [Math]::Round($After - $Before, 0)
}

# --- main ---
for ($i=0; $i -lt 30; $i++) {
  try { if ((Invoke-RestMethod "$BaseUrl/actuator/health" -TimeoutSec 3).status -eq 'UP') { break } } catch {}
  Start-Sleep 2
}
Get-Content (Join-Path $PerfRoot "sql\init_perf_data.sql") -Raw | docker exec -i credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local ticketing 2>$null | Out-Null

$usersCsv = Join-Path $OutDir "users.csv"
Login-Users 90001 90120 $usersCsv
Invoke-Mysql "INSERT INTO tb_wallet_account(user_id,available_balance,frozen_balance,version) SELECT id,10000,0,0 FROM tb_user WHERE phone LIKE '1399%' ON DUPLICATE KEY UPDATE available_balance=GREATEST(available_balance,10000);"

$plan = Join-Path $PerfRoot "jmeter\stock_contention.jmx"
$summaries = New-Object System.Collections.Generic.List[object]
$metricsRows = New-Object System.Collections.Generic.List[object]

Prom "suite-before"
$lockWaits0 = MysqlStatus 'Innodb_row_lock_waits'
$lockTime0 = MysqlStatus 'Innodb_row_lock_time'
$timeout0Raw = PromVal (Join-Path $OutDir 'prometheus-suite-before.txt') 'hikaricp_connections_timeout_total\{pool="HikariPool-1",\} ([0-9.]+)'
$timeout0 = 0.0; [void][double]::TryParse($timeout0Raw, [ref]$timeout0)

foreach ($t in $ThreadLevels) {
  for ($r = 1; $r -le $Repeats; $r++) {
    $name = "stock20_t${t}_r$r"
    Write-Host "=== $name ==="
    Reset-Stock20
    Warm-Sku $usersCsv
    $lwBefore = MysqlStatus 'Innodb_row_lock_waits'
    $ltBefore = MysqlStatus 'Innodb_row_lock_time'
    Prom "before-$name"
    $toBeforeRaw = PromVal (Join-Path $OutDir "prometheus-before-$name.txt") 'hikaricp_connections_timeout_total\{pool="HikariPool-1",\} ([0-9.]+)'
    $toBefore = 0.0; [void][double]::TryParse($toBeforeRaw, [ref]$toBefore)

    $jtl = Join-Path $OutDir "$name.jtl"
    $log = Join-Path $LogDir "$name.log"
    $argList = @($JMeterHeap.Split(' ') + @('-jar', $JMeterJar, '-n', '-t', $plan, '-l', $jtl, '-j', $log,
      '-Jhost=127.0.0.1', '-Jport=8082', "-Jthreads=$t", '-Jramp=2', '-Jloops=1',
      "-Jusers=$usersCsv", '-JskuId=perf_sku_stock20', '-JsessionId=perf_session'))
    $proc = Start-Process -FilePath $JavaExe -ArgumentList $argList -PassThru -NoNewWindow
    Start-Sleep 2
    Prom "during-$name"
    $duringAutopay = Get-AutopaySnapshot (Join-Path $OutDir "prometheus-during-$name.txt")
    Wait-Process -Id $proc.Id -Timeout 180 -ErrorAction SilentlyContinue
    if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }

    Prom "after-$name"
    $drain = Wait-AutopayDrain $name

    $val = Join-Path $OutDir "validation-$name.txt"
    Invoke-MysqlFile (Join-Path $PerfRoot "sql\validate_stock20_round.sql") $val
    $sum = Summarize-Jtl $jtl $t $r
    $sum.bad_rows = Get-BadRows $val
    $remain = ''
    $m = [regex]::Match((Get-Content $val -Raw -ErrorAction SilentlyContinue), '(?ms)\|\s*METRIC_REMAINING\s*\|\s*(\d+)\s*\|')
    if ($m.Success) { $remain = $m.Groups[1].Value }
    $orders = ''
    $m2 = [regex]::Match((Get-Content $val -Raw -ErrorAction SilentlyContinue), '(?ms)\|\s*METRIC_VALID_ORDERS\s*\|\s*(\d+)\s*\|')
    if ($m2.Success) { $orders = $m2.Groups[1].Value }
    $toAfterRaw = PromVal (Join-Path $OutDir "prometheus-after-$name.txt") 'hikaricp_connections_timeout_total\{pool="HikariPool-1",\} ([0-9.]+)'
    $toAfter = 0.0; [void][double]::TryParse($toAfterRaw, [ref]$toAfter)
    $pending = PromVal (Join-Path $OutDir "prometheus-during-$name.txt") 'hikaricp_connections_pending\{pool="HikariPool-1",\} ([0-9.]+)'
    $acq = PromVal (Join-Path $OutDir "prometheus-after-$name.txt") 'hikaricp_connections_acquire_seconds_max\{pool="HikariPool-1",\} ([0-9.eE+-]+)'
    $usage = PromVal (Join-Path $OutDir "prometheus-after-$name.txt") 'hikaricp_connections_usage_seconds_max\{pool="HikariPool-1",\} ([0-9.eE+-]+)'
    $hikariActive = PromVal (Join-Path $OutDir "prometheus-during-$name.txt") 'hikaricp_connections_active\{pool="HikariPool-1",\} ([0-9.]+)'
    $hikariIdle = PromVal (Join-Path $OutDir "prometheus-during-$name.txt") 'hikaricp_connections_idle\{pool="HikariPool-1",\} ([0-9.]+)'
    $lwAfter = MysqlStatus 'Innodb_row_lock_waits'
    $ltAfter = MysqlStatus 'Innodb_row_lock_time'
    $tdelta = TimeoutDelta $toBefore $toAfter
    $sum.note = "remaining=$remain;orders=$orders;timeout_delta=$tdelta;pending_during=$pending;acquire_max=$acq;lock_waits_delta=$($lwAfter-$lwBefore);lock_time_delta=$($ltAfter-$ltBefore);autopay_q_peak=$($drain.queue_peak);autopay_rejected=$($drain.rejected_counter);paid=$($drain.paid_or_issued);waiting=$($drain.waiting_payment)"
    $summaries.Add($sum)
    $metricsRows.Add([pscustomobject]@{
      name=$name; concurrency=$t; run=$r; timeout_delta=$tdelta; pending_during=$pending
      acquire_max=$acq; usage_max=$usage; hikari_active=$hikariActive; hikari_idle=$hikariIdle
      lock_waits_delta=($lwAfter-$lwBefore); lock_time_delta=($ltAfter-$ltBefore)
      bad_rows=$sum.bad_rows; success=$sum.success; system_errors=$sum.system_errors; error_rate=$sum.error_rate
      p95=$sum.p95_ms; p99=$sum.p99_ms; timeout_count=$sum.timeout_count; remaining=$remain; orders=$orders
      autopay_queue_during=$duringAutopay.queue; autopay_active_during=$duringAutopay.active
      autopay_queue_peak=$drain.queue_peak; autopay_active_peak=$drain.active_peak
      autopay_queue_final=$drain.queue_final; autopay_completed=$drain.completed
      autopay_rejected=$drain.rejected_counter; autopay_reject_gauge=$drain.rejected_gauge
      paid_or_issued=$drain.paid_or_issued; waiting_payment=$drain.waiting_payment; payment_success=$drain.payment_success
    })
    Start-Sleep $GapBetweenRunsSec
  }
}

Prom "suite-after"
$timeout1Raw = PromVal (Join-Path $OutDir 'prometheus-suite-after.txt') 'hikaricp_connections_timeout_total\{pool="HikariPool-1",\} ([0-9.]+)'
$timeout1 = 0.0; [void][double]::TryParse($timeout1Raw, [ref]$timeout1)
$lockWaits1 = MysqlStatus 'Innodb_row_lock_waits'
$lockTime1 = MysqlStatus 'Innodb_row_lock_time'

$summaries | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $OutDir 'summary.csv')
$metricsRows | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $OutDir 'metrics.csv')
@"
suite_timeout_delta=$(TimeoutDelta $timeout0 $timeout1)
suite_lock_waits_delta=$($lockWaits1-$lockWaits0)
suite_lock_time_delta=$($lockTime1-$lockTime0)
out_dir=$OutDir
"@ | Set-Content (Join-Path $OutDir 'suite_deltas.txt')
$summaries | Format-Table -AutoSize | Out-String | Tee-Object (Join-Path $OutDir 'summary_table.txt') | Write-Host
$metricsRows | Format-Table -AutoSize | Out-String | Tee-Object (Join-Path $OutDir 'metrics_table.txt') | Write-Host
Write-Host "Results: $OutDir"
