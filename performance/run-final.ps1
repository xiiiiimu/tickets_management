# Final post-optimization performance benchmark. Collection only — no config/code changes.
param(
  [string]$JMeterHome = "$env:USERPROFILE\Downloads\apache-jmeter-5.6.3\apache-jmeter-5.6.3",
  [string]$BaseUrl = "http://127.0.0.1:8082",
  [string]$JMeterHeap = "-Xms64m -Xmx192m",
  [int]$GapBetweenRunsSec = 10,
  [int]$AutopayDrainMaxSec = 120
)

$ErrorActionPreference = "Continue"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$PerfRoot = Join-Path $Root "performance"
$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$OutDir = Join-Path $PerfRoot "results\final-$Stamp"
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
function Invoke-MysqlFile([string]$Path, [string]$OutFile) {
  $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try {
    Get-Content $Path -Raw | docker exec -i credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local ticketing --table 2>$null |
      Out-File -Encoding UTF8 $OutFile
  } finally { $ErrorActionPreference = $prev }
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
    if ($null -eq $out) { return 'NA' }
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
function NumOrNA($v) {
  if ($null -eq $v -or $v -eq '') { return 'NA' }
  return $v
}

function Summarize-Jtl([string]$JtlPath) {
  if (-not (Test-Path $JtlPath) -or (Get-Item $JtlPath).Length -eq 0) {
    return $null
  }
  $rows = @(Import-Csv $JtlPath)
  $sys = 0; $sockTo=0; $http503=0; $otherSys=0
  $http200=0; $biz409=0; $bizOther=0
  $latAll = New-Object System.Collections.Generic.List[int]
  $lat200 = New-Object System.Collections.Generic.List[int]
  $lat409 = New-Object System.Collections.Generic.List[int]
  foreach ($row in $rows) {
    $code = 0; [void][int]::TryParse([string]$row.responseCode, [ref]$code)
    $el = 0; [void][int]::TryParse([string]$row.elapsed, [ref]$el)
    $msg = [string]$row.responseMessage
    if ($code -eq 200) { $http200++; $latAll.Add($el); $lat200.Add($el); continue }
    if ($code -eq 409) { $biz409++; $latAll.Add($el); $lat409.Add($el); continue }
    if ($code -in 400,403,429) { $bizOther++; $latAll.Add($el); continue }
    if ($code -eq 503) { $http503++; $sys++; continue }
    if ($msg -match 'SocketTimeout' -or $code -eq 0) { $sockTo++; $sys++; continue }
    $otherSys++; $sys++
  }
  function Pct($list, [double]$p) {
    if ($list.Count -eq 0) { return 'NA' }
    $sorted = @($list | Sort-Object)
    $idx = [Math]::Min($sorted.Count-1, [Math]::Ceiling($sorted.Count*$p)-1)
    if ($idx -lt 0) { $idx = 0 }
    return $sorted[$idx]
  }
  function Avg($list) {
    if ($list.Count -eq 0) { return 'NA' }
    return [Math]::Round(($list | Measure-Object -Average).Average, 2)
  }
  $completion = $http200 + $biz409 + $bizOther
  $times = @($rows | ForEach-Object { [long]$_.timeStamp } | Sort-Object)
  $dur = 0.001
  if ($times.Count -ge 2) { $dur = [Math]::Max(0.001, ($times[-1]-$times[0])/1000.0) }
  if ($rows.Count -gt 1 -and $dur -lt 0.05 -and $latAll.Count -gt 0) {
    $dur = [Math]::Max(0.05, (($times[-1]-$times[0]) + (Avg $latAll))/1000.0)
  }
  return [pscustomobject]@{
    requests = $rows.Count
    http_200 = $http200
    business_409 = $biz409
    business_other = $bizOther
    http_503 = $http503
    socket_timeout = $sockTo
    other_system_error = $otherSys
    system_errors = $sys
    completion_count = $completion
    completion_rate = [Math]::Round(100.0 * $completion / [Math]::Max(1,$rows.Count), 2)
    system_error_rate = [Math]::Round(100.0 * $sys / [Math]::Max(1,$rows.Count), 2)
    throughput_rps = [Math]::Round($rows.Count / $dur, 2)
    avg_ms = (Avg $latAll)
    p50_ms = (Pct $latAll 0.5)
    p90_ms = (Pct $latAll 0.9)
    p95_ms = (Pct $latAll 0.95)
    p99_ms = (Pct $latAll 0.99)
    max_ms = if ($latAll.Count -eq 0) { 'NA' } else { (@($latAll | Sort-Object)[-1]) }
    avg_200_ms = (Avg $lat200)
    p95_200_ms = (Pct $lat200 0.95)
    avg_409_ms = (Avg $lat409)
    p95_409_ms = (Pct $lat409 0.95)
  }
}

function Login-Users([int]$FromId, [int]$ToId, [string]$CsvPath) {
  $lines = @("userId,token,deviceId")
  $map = @{}
  for ($id=$FromId; $id -le $ToId; $id++) {
    $phone = "1399{0:D7}" -f ($id - 90000)
    $code = (Invoke-RestMethod -Method Post "$BaseUrl/api/user/code?phone=$phone").data
    $token = (Invoke-RestMethod -Method Post "$BaseUrl/api/user/login" -ContentType "application/json" -Body (@{phone=$phone;code=$code}|ConvertTo-Json)).data
    $me = Invoke-RestMethod "$BaseUrl/api/user/me" -Headers @{authorization=$token}
    $uid = [int]$me.data.id
    $map[$id] = $uid
    $lines += "$uid,$token,perf-device-$uid"
  }
  $lines | Set-Content -Encoding UTF8 $CsvPath
  return $map
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
  try { Invoke-RestMethod -Method Post "$BaseUrl/api/tickets/reserve" -Headers @{ authorization = $u.token; 'Content-Type' = 'application/json' } -Body $body -TimeoutSec 60 | Out-Null } catch {}
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

function Reset-Sku([string]$Sku) {
  Invoke-Mysql @"
DELETE FROM tb_issued_ticket WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='$Sku') x);
DELETE FROM tb_payment_transaction WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='$Sku') x);
DELETE FROM tb_wallet_ledger WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='$Sku') x);
DELETE FROM tb_risk_decision WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='$Sku') x);
DELETE FROM tb_outbox_message WHERE aggregate_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id='$Sku') x);
DELETE FROM tb_ticket_order WHERE sku_id='$Sku';
DELETE FROM tb_reservation WHERE sku_id='$Sku';
DELETE FROM tb_reserve_request WHERE sku_id='$Sku';
DELETE FROM tb_user_purchase WHERE sku_id='$Sku';
UPDATE tb_ticket_sku SET remaining_stock=total_stock, version=version+1 WHERE id='$Sku';
"@
  docker exec credit_risk-ticket-redis-1 redis-cli EVAL "local k=redis.call('keys','*$Sku*'); for i=1,#k do redis.call('del',k[i]) end; return #k" 0 2>$null | Out-Null
}

function Get-BadRows([string]$ValFile) {
  if (-not (Test-Path $ValFile)) { return 'NA' }
  $sum = 0
  foreach ($m in [regex]::Matches((Get-Content $ValFile -Raw), '(?ms)\|\s*(NEG_STOCK|ORDERS_OVER_STOCK|DUP_REQUEST_RESERVATION|DUP_RESERVATION_ORDER|DUP_PAYMENT_SUCCESS|DUP_ISSUED_SEQ|PURCHASE_OVER_LIMIT|STOCK_CONSISTENCY|HELD_EXCEEDS_TOTAL|OUTBOX_FAILED|BLOCK_ORDERS|BLOCK_RESERVATIONS)\s*\|\s*(\d+)\s*\|')) {
    $sum += [int]$m.Groups[2].Value
  }
  return $sum
}
function Get-Metric([string]$ValFile, [string]$Name) {
  if (-not (Test-Path $ValFile)) { return 'NA' }
  $m = [regex]::Match((Get-Content $ValFile -Raw), "(?ms)\|\s*$Name\s*\|\s*(\d+)\s*\|")
  if ($m.Success) { return $m.Groups[1].Value }
  return 'NA'
}

function Wait-BacklogClear([string]$Tag) {
  $peakQ = 0.0; $peakActive = 0.0; $peakPending = 0.0
  $deadline = (Get-Date).AddSeconds($AutopayDrainMaxSec)
  $stable = 0
  while ((Get-Date) -lt $deadline) {
    Prom "drain-$Tag-$(Get-Date -Format HHmmssfff)"
    $latest = Get-ChildItem $OutDir -Filter "prometheus-drain-$Tag-*.txt" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $q = AsDouble (PromVal $latest.FullName 'threadpool_queue\{pool="autopay",\} ([0-9.]+)')
    $a = AsDouble (PromVal $latest.FullName 'threadpool_active\{pool="autopay",\} ([0-9.]+)')
    $p = AsDouble (PromVal $latest.FullName 'hikaricp_connections_pending\{pool="HikariPool-1",\} ([0-9.]+)')
    if ($null -ne $q -and $q -gt $peakQ) { $peakQ = $q }
    if ($null -ne $a -and $a -gt $peakActive) { $peakActive = $a }
    if ($null -ne $p -and $p -gt $peakPending) { $peakPending = $p }
    $waiting = MysqlN "SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id='perf_sku_stock20' AND status='WAITING_PAYMENT';"
    $paying = MysqlN "SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id='perf_sku_stock20' AND status='PAYING';"
    $qOk = ($null -eq $q -or $q -eq 0); $aOk = ($null -eq $a -or $a -eq 0)
    $pOk = ($null -eq $p -or $p -le 1)
    if ($qOk -and $aOk -and $pOk -and $waiting -eq '0' -and $paying -eq '0') {
      $stable++; if ($stable -ge 2) { break }
    } else { $stable = 0 }
    Start-Sleep 2
  }
  Prom "drain-final-$Tag"
  return [pscustomobject]@{ queue_peak=$peakQ; active_peak=$peakActive; pending_peak_drain=$peakPending }
}

function Write-Environment {
  $os = Get-CimInstance Win32_OperatingSystem
  $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
  $prom = Join-Path $OutDir 'prometheus-suite-before.txt'
  Prom 'suite-before'
  @"
stamp=$Stamp
git_head=$(git -C $Root rev-parse HEAD)
git_describe=$(git -C $Root describe --always --dirty 2>$null)
os=$($os.Caption)
os_version=$([System.Environment]::OSVersion.VersionString)
cpu=$($cpu.Name)
cpu_cores=$($cpu.NumberOfCores)
cpu_logical=$($cpu.NumberOfLogicalProcessors)
ram_total_mb=$([math]::Round($os.TotalVisibleMemorySize/1024,0))
ram_free_mb=$([math]::Round($os.FreePhysicalMemory/1024,0))
docker_version=$(docker version --format '{{.Server.Version}}' 2>$null)
platform_container=credit_risk-ticket-risk-platform-1
platform_memory_bytes=$(docker inspect credit_risk-ticket-risk-platform-1 --format '{{.HostConfig.Memory}}' 2>$null)
jvm_heap=$(docker inspect credit_risk-ticket-risk-platform-1 --format '{{range .Config.Env}}{{println .}}{{end}}' 2>$null | Select-String 'JAVA_TOOL' | ForEach-Object { $_.Line })
hikari_max=$(PromVal $prom 'hikaricp_connections_max\{pool="HikariPool-1",\} ([0-9.]+)')
autopay_core=$(PromVal $prom 'threadpool_core\{pool="autopay",\} ([0-9.]+)')
autopay_max=$(PromVal $prom 'threadpool_max\{pool="autopay",\} ([0-9.]+)')
mysql=credit_risk-ticket-mysql-1 (mysql:8.0 host 127.0.0.1:3307)
redis=credit_risk-ticket-redis-1 (redis:7-alpine 127.0.0.1:6379)
rocketmq=ticket-rmqnamesrv:9876 / ticket-rmqbroker
jmeter=5.6.3
jmeter_response_timeout_sec=30 (unchanged plan)
base_url=$BaseUrl
initial_stock=20
"@ | Set-Content -Encoding UTF8 (Join-Path $OutDir 'environment.txt')
}

# -------- main --------
for ($i=0; $i -lt 30; $i++) {
  try { if ((Invoke-RestMethod "$BaseUrl/actuator/health" -TimeoutSec 3).status -eq 'UP') { break } } catch {}
  Start-Sleep 2
}
Write-Environment
Get-Content (Join-Path $PerfRoot "sql\init_perf_data.sql") -Raw | docker exec -i credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local ticketing 2>$null | Out-Null
Invoke-Mysql "UPDATE tb_risk_rule SET config=JSON_SET(config,'$.threshold',100000) WHERE rule_code IN ('IP_FREQUENCY','DEVICE_FREQUENCY','USER_FREQUENCY');"

$usersCsv = Join-Path $OutDir "users.csv"
$usersSingle = Join-Path $OutDir "users_single.csv"
$usersBlock = Join-Path $OutDir "users_block.csv"
Write-Host "Login users..."
$null = Login-Users 90001 90120 $usersCsv
$null = Login-Users 90001 90001 $usersSingle
$blockMap = Login-Users 90100 90100 $usersBlock
$blockUid = $blockMap[90100]
Invoke-Mysql "DELETE FROM tb_risk_profile WHERE id='perf_profile_block'; INSERT INTO tb_risk_profile(id,subject_type,subject_value,risk_level,decision,score,reason_codes,source_order_id,source_decision_id,version,expires_at) VALUES('perf_profile_block','USER','$blockUid','HIGH','BLOCK',120,'PERF_BLOCK',NULL,'perf_seed',0,DATE_ADD(NOW(), INTERVAL 1 DAY));"
docker exec credit_risk-ticket-redis-1 redis-cli DEL "risk:profile:user:$blockUid" 2>$null | Out-Null
Invoke-Mysql "INSERT INTO tb_wallet_account(user_id,available_balance,frozen_balance,version) SELECT id,10000,0,0 FROM tb_user WHERE phone LIKE '1399%' ON DUPLICATE KEY UPDATE available_balance=GREATEST(available_balance,10000);"

$plan = Join-Path $PerfRoot "jmeter\stock_contention.jmx"
$rowsOut = New-Object System.Collections.Generic.List[object]

foreach ($t in $ThreadLevels) {
  for ($r = 1; $r -le $Repeats; $r++) {
    $name = "stock20_t${t}_r$r"
    Write-Host "=== $name ==="
    # ensure prior backlog cleared
    $null = Wait-BacklogClear "pre-$name"
    Reset-Stock20
    Warm-Sku $usersCsv

    $lwB = MysqlStatus 'Innodb_row_lock_waits'
    $ltB = MysqlStatus 'Innodb_row_lock_time'
    $ltmB = MysqlStatus 'Innodb_row_lock_time_max'
    $tcB = MysqlStatus 'Threads_connected'
    $trB = MysqlStatus 'Threads_running'
    Prom "before-$name"
    $toB = PromVal (Join-Path $OutDir "prometheus-before-$name.txt") 'hikaricp_connections_timeout_total\{pool="HikariPool-1",\} ([0-9.]+)'
    $compB = PromVal (Join-Path $OutDir "prometheus-before-$name.txt") 'threadpool_completed\{pool="autopay",\} ([0-9.]+)'
    $rejB = PromVal (Join-Path $OutDir "prometheus-before-$name.txt") 'threadpool_reject\{pool="autopay",\} ([0-9.]+)'
    $rejCb = PromVal (Join-Path $OutDir "prometheus-before-$name.txt") 'payment_auto_rejected_total ([0-9.]+)'
    $succB = PromVal (Join-Path $OutDir "prometheus-before-$name.txt") 'payment_auto_success_total ([0-9.]+)'
    $failB = PromVal (Join-Path $OutDir "prometheus-before-$name.txt") 'payment_auto_failure_total ([0-9.]+)'

    $jtl = Join-Path $OutDir "$name.jtl"
    $log = Join-Path $LogDir "$name.log"
    $argList = @($JMeterHeap.Split(' ') + @('-jar', $JMeterJar, '-n', '-t', $plan, '-l', $jtl, '-j', $log,
      '-Jhost=127.0.0.1', '-Jport=8082', "-Jthreads=$t", '-Jramp=2', '-Jloops=1',
      "-Jusers=$usersCsv", '-JskuId=perf_sku_stock20', '-JsessionId=perf_session'))
    $proc = Start-Process -FilePath $JavaExe -ArgumentList $argList -PassThru -NoNewWindow
    Start-Sleep 2
    Prom "during-$name"
    $pendingDuring = PromVal (Join-Path $OutDir "prometheus-during-$name.txt") 'hikaricp_connections_pending\{pool="HikariPool-1",\} ([0-9.]+)'
    $activeDuring = PromVal (Join-Path $OutDir "prometheus-during-$name.txt") 'hikaricp_connections_active\{pool="HikariPool-1",\} ([0-9.]+)'
    $idleDuring = PromVal (Join-Path $OutDir "prometheus-during-$name.txt") 'hikaricp_connections_idle\{pool="HikariPool-1",\} ([0-9.]+)'
    $maxDuring = PromVal (Join-Path $OutDir "prometheus-during-$name.txt") 'hikaricp_connections_max\{pool="HikariPool-1",\} ([0-9.]+)'
    $qDuring = PromVal (Join-Path $OutDir "prometheus-during-$name.txt") 'threadpool_queue\{pool="autopay",\} ([0-9.]+)'
    $aDuring = PromVal (Join-Path $OutDir "prometheus-during-$name.txt") 'threadpool_active\{pool="autopay",\} ([0-9.]+)'
    Wait-Process -Id $proc.Id -Timeout 240 -ErrorAction SilentlyContinue
    if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }

    Prom "after-$name"
    $drain = Wait-BacklogClear $name

    $val = Join-Path $OutDir "validation-$name.txt"
    Invoke-MysqlFile (Join-Path $PerfRoot "sql\validate_stock20_round.sql") $val
    $bizExtra = Join-Path $OutDir "bizstate-$name.txt"
    Invoke-MysqlQuery @"
SELECT 'PAID_ORDERS' AS metric, COUNT(*) AS value FROM tb_ticket_order WHERE sku_id='perf_sku_stock20' AND status='PAID';
SELECT 'ISSUED_ORDERS' AS metric, COUNT(*) AS value FROM tb_ticket_order WHERE sku_id='perf_sku_stock20' AND status='ISSUED';
SELECT 'WAITING_PAYMENT' AS metric, COUNT(*) AS value FROM tb_ticket_order WHERE sku_id='perf_sku_stock20' AND status='WAITING_PAYMENT';
SELECT 'PAYMENT_SUCCESS' AS metric, COUNT(*) AS value FROM tb_payment_transaction p JOIN tb_ticket_order o ON o.id=p.order_id WHERE o.sku_id='perf_sku_stock20' AND p.status='SUCCESS';
"@ $bizExtra

    $sum = Summarize-Jtl $jtl
    if ($null -eq $sum) {
      Write-Warning "Missing JTL $name"
      continue
    }
    $toA = PromVal (Join-Path $OutDir "prometheus-after-$name.txt") 'hikaricp_connections_timeout_total\{pool="HikariPool-1",\} ([0-9.]+)'
    $acq = PromVal (Join-Path $OutDir "prometheus-after-$name.txt") 'hikaricp_connections_acquire_seconds_max\{pool="HikariPool-1",\} ([0-9.eE+-]+)'
    $usage = PromVal (Join-Path $OutDir "prometheus-after-$name.txt") 'hikaricp_connections_usage_seconds_max\{pool="HikariPool-1",\} ([0-9.eE+-]+)'
    $pendingAfter = PromVal (Join-Path $OutDir "prometheus-after-$name.txt") 'hikaricp_connections_pending\{pool="HikariPool-1",\} ([0-9.]+)'
    $compA = PromVal (Join-Path $OutDir "prometheus-drain-final-$name.txt") 'threadpool_completed\{pool="autopay",\} ([0-9.]+)'
    $rejA = PromVal (Join-Path $OutDir "prometheus-drain-final-$name.txt") 'threadpool_reject\{pool="autopay",\} ([0-9.]+)'
    $rejCa = PromVal (Join-Path $OutDir "prometheus-drain-final-$name.txt") 'payment_auto_rejected_total ([0-9.]+)'
    $succA = PromVal (Join-Path $OutDir "prometheus-drain-final-$name.txt") 'payment_auto_success_total ([0-9.]+)'
    $failA = PromVal (Join-Path $OutDir "prometheus-drain-final-$name.txt") 'payment_auto_failure_total ([0-9.]+)'
    $qFinal = PromVal (Join-Path $OutDir "prometheus-drain-final-$name.txt") 'threadpool_queue\{pool="autopay",\} ([0-9.]+)'
    $poolSize = PromVal (Join-Path $OutDir "prometheus-drain-final-$name.txt") 'threadpool_max\{pool="autopay",\} ([0-9.]+)'

    $lwA = MysqlStatus 'Innodb_row_lock_waits'
    $ltA = MysqlStatus 'Innodb_row_lock_time'
    $ltmA = MysqlStatus 'Innodb_row_lock_time_max'
    $tcA = MysqlStatus 'Threads_connected'
    $trA = MysqlStatus 'Threads_running'

    function DeltaNA($b,$a) {
      if ($b -eq 'NA' -or $a -eq 'NA') { return 'NA' }
      return ([long]$a - [long]$b)
    }
    function DeltaProm($b,$a) {
      $bd = AsDouble $b; $ad = AsDouble $a
      if ($null -eq $bd -or $null -eq $ad) { return 'NA' }
      if ($ad -lt $bd) { return "COUNTER_RESET" }
      return [Math]::Round($ad - $bd, 0)
    }

    $pendingPeak = 0.0
    foreach ($pv in @($pendingDuring, $pendingAfter, $drain.pending_peak_drain)) {
      $d = AsDouble $pv
      if ($null -ne $d -and $d -gt $pendingPeak) { $pendingPeak = $d }
    }
    $qPeak = $drain.queue_peak
    $qd = AsDouble $qDuring
    if ($null -ne $qd -and $qd -gt $qPeak) { $qPeak = $qd }
    $aPeak = $drain.active_peak
    $ad = AsDouble $aDuring
    if ($null -ne $ad -and $ad -gt $aPeak) { $aPeak = $ad }

    $rejDelta = DeltaProm $rejB $rejA
    if ($rejDelta -eq 'NA') { $rejDelta = DeltaProm $rejCb $rejCa }

    $expected409 = [Math]::Max(0, $t - 20)
    $clean = ($sum.system_errors -eq 0 -and $sum.socket_timeout -eq 0 -and $sum.http_503 -eq 0 `
      -and $sum.http_200 -eq 20 -and $sum.business_409 -eq $expected409 `
      -and (Get-BadRows $val) -eq 0)
    # rejected must be 0 or NA-as-zero
    if ($rejDelta -ne 'NA' -and $rejDelta -ne 'COUNTER_RESET' -and [double]$rejDelta -gt 0) { $clean = $false }

    $row = [ordered]@{
      scenario='stock_contention'; concurrency=$t; run=$r; requests=$sum.requests
      http_200=$sum.http_200; business_409=$sum.business_409; http_503=$sum.http_503
      socket_timeout=$sum.socket_timeout; system_errors=$sum.system_errors
      completion_rate=$sum.completion_rate; system_error_rate=$sum.system_error_rate
      throughput_rps=$sum.throughput_rps; avg_ms=$sum.avg_ms
      p50_ms=$sum.p50_ms; p90_ms=$sum.p90_ms; p95_ms=$sum.p95_ms; p99_ms=$sum.p99_ms; max_ms=$sum.max_ms
      avg_200_ms=$sum.avg_200_ms; p95_200_ms=$sum.p95_200_ms; avg_409_ms=$sum.avg_409_ms; p95_409_ms=$sum.p95_409_ms
      orders=(Get-Metric $val 'METRIC_VALID_ORDERS'); remaining_stock=(Get-Metric $val 'METRIC_REMAINING')
      paid_orders=(Get-Metric $bizExtra 'PAID_ORDERS'); issued_orders=(Get-Metric $bizExtra 'ISSUED_ORDERS')
      waiting_payment=(Get-Metric $bizExtra 'WAITING_PAYMENT'); payment_success=(Get-Metric $bizExtra 'PAYMENT_SUCCESS')
      bad_rows=(Get-BadRows $val)
      hikari_timeout_delta=(TimeoutDelta $toB $toA)
      hikari_pending_peak=$pendingPeak
      hikari_acquire_max=(NumOrNA $acq)
      hikari_usage_max=(NumOrNA $usage)
      hikari_active_during=(NumOrNA $activeDuring)
      hikari_idle_during=(NumOrNA $idleDuring)
      hikari_max=(NumOrNA $maxDuring)
      mysql_lock_waits_delta=(DeltaNA $lwB $lwA)
      mysql_lock_time_delta=(DeltaNA $ltB $ltA)
      mysql_lock_time_max_after=(NumOrNA $ltmA)
      mysql_threads_connected_after=(NumOrNA $tcA)
      mysql_threads_running_after=(NumOrNA $trA)
      autopay_pool_size=(NumOrNA $poolSize)
      autopay_active_peak=$aPeak
      autopay_queue_peak=$qPeak
      autopay_queue_final=(NumOrNA $qFinal)
      autopay_completed_delta=(DeltaProm $compB $compA)
      autopay_rejected=(NumOrNA $rejDelta)
      payment_auto_success_delta=(DeltaProm $succB $succA)
      payment_auto_failure_delta=(DeltaProm $failB $failA)
      clean=([int]$clean)
    }
    $rowsOut.Add([pscustomobject]$row)
    ($row.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join "`n" | Set-Content (Join-Path $OutDir "metrics-$name.txt")
    Start-Sleep $GapBetweenRunsSec
  }
}

# --- duplicate_request ---
Write-Host "=== duplicate_request ==="
Reset-Sku 'perf_sku_dup'
$jtl = Join-Path $OutDir "duplicate_request.jtl"
$log = Join-Path $LogDir "duplicate_request.log"
$reqId = "perf-dup-fixed-$Stamp"
$argList = @($JMeterHeap.Split(' ') + @('-jar', $JMeterJar, '-n', '-t', (Join-Path $PerfRoot 'jmeter\duplicate_request_id.jmx'), '-l', $jtl, '-j', $log,
  '-Jhost=127.0.0.1', '-Jport=8082', '-Jthreads=50', '-Jramp=2', "-Jusers=$usersSingle",
  '-JskuId=perf_sku_dup', '-JsessionId=perf_session', "-JrequestId=$reqId"))
$proc = Start-Process -FilePath $JavaExe -ArgumentList $argList -PassThru -NoNewWindow
Wait-Process -Id $proc.Id -Timeout 180 -ErrorAction SilentlyContinue
if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
Start-Sleep 5
$valDup = Join-Path $OutDir "validation-duplicate_request.txt"
Invoke-MysqlQuery @"
SELECT 'DUP_REQUEST_RESERVATION' AS check_name, COUNT(*) AS bad_rows FROM (
  SELECT request_id FROM tb_reservation WHERE request_id='$reqId' GROUP BY request_id HAVING COUNT(*)>1
) t;
SELECT 'RESERVATION_COUNT' AS metric, COUNT(*) AS value FROM tb_reservation WHERE request_id='$reqId';
SELECT 'ORDER_COUNT' AS metric, COUNT(*) AS value FROM tb_ticket_order WHERE reservation_id IN (SELECT id FROM tb_reservation WHERE request_id='$reqId');
"@ $valDup
$sumDup = Summarize-Jtl $jtl
$dupRow = [pscustomobject]@{
  scenario='duplicate_request'; concurrency=50; run=1
  requests=$(if($sumDup){$sumDup.requests}else{'NA'})
  http_200=$(if($sumDup){$sumDup.http_200}else{'NA'})
  business_409=$(if($sumDup){$sumDup.business_409}else{'NA'})
  http_503=$(if($sumDup){$sumDup.http_503}else{'NA'})
  socket_timeout=$(if($sumDup){$sumDup.socket_timeout}else{'NA'})
  system_errors=$(if($sumDup){$sumDup.system_errors}else{'NA'})
  completion_rate=$(if($sumDup){$sumDup.completion_rate}else{'NA'})
  system_error_rate=$(if($sumDup){$sumDup.system_error_rate}else{'NA'})
  throughput_rps=$(if($sumDup){$sumDup.throughput_rps}else{'NA'})
  avg_ms=$(if($sumDup){$sumDup.avg_ms}else{'NA'}); p50_ms=$(if($sumDup){$sumDup.p50_ms}else{'NA'})
  p90_ms=$(if($sumDup){$sumDup.p90_ms}else{'NA'}); p95_ms=$(if($sumDup){$sumDup.p95_ms}else{'NA'})
  p99_ms=$(if($sumDup){$sumDup.p99_ms}else{'NA'}); max_ms=$(if($sumDup){$sumDup.max_ms}else{'NA'})
  orders=(Get-Metric $valDup 'ORDER_COUNT'); remaining_stock='NA'; bad_rows=(Get-BadRows $valDup)
  reservations=(Get-Metric $valDup 'RESERVATION_COUNT')
  hikari_timeout_delta='NA'; hikari_pending_peak='NA'; hikari_acquire_max='NA'
  mysql_lock_waits_delta='NA'; mysql_lock_time_delta='NA'
  autopay_queue_peak='NA'; autopay_rejected='NA'
}
$rowsOut.Add($dupRow)
Start-Sleep $GapBetweenRunsSec

# --- risk_profile_block ---
Write-Host "=== risk_profile_block ==="
Reset-Sku 'perf_sku_block'
docker exec credit_risk-ticket-redis-1 redis-cli DEL "risk:profile:user:$blockUid" 2>$null | Out-Null
$jtl = Join-Path $OutDir "risk_profile_block.jtl"
$log = Join-Path $LogDir "risk_profile_block.log"
$argList = @($JMeterHeap.Split(' ') + @('-jar', $JMeterJar, '-n', '-t', (Join-Path $PerfRoot 'jmeter\risk_profile_block.jmx'), '-l', $jtl, '-j', $log,
  '-Jhost=127.0.0.1', '-Jport=8082', '-Jthreads=50', '-Jramp=2', "-Jusers=$usersBlock",
  '-JskuId=perf_sku_block', '-JsessionId=perf_session'))
$proc = Start-Process -FilePath $JavaExe -ArgumentList $argList -PassThru -NoNewWindow
Wait-Process -Id $proc.Id -Timeout 180 -ErrorAction SilentlyContinue
if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
$valBlock = Join-Path $OutDir "validation-risk_profile_block.txt"
Invoke-MysqlQuery @"
SELECT 'BLOCK_ORDERS' AS check_name, COUNT(*) AS bad_rows FROM tb_ticket_order WHERE sku_id='perf_sku_block' AND status NOT IN ('CANCELLED','PAY_TIMEOUT','RISK_BLOCKED');
SELECT 'BLOCK_RESERVATIONS' AS check_name, COUNT(*) AS bad_rows FROM tb_reservation WHERE sku_id='perf_sku_block';
SELECT 'NEG_STOCK' AS check_name, COUNT(*) AS bad_rows FROM tb_ticket_sku WHERE id='perf_sku_block' AND remaining_stock<0;
SELECT 'METRIC_REMAINING' AS metric, remaining_stock AS value FROM tb_ticket_sku WHERE id='perf_sku_block';
SELECT 'METRIC_TOTAL' AS metric, total_stock AS value FROM tb_ticket_sku WHERE id='perf_sku_block';
"@ $valBlock
$sumBlk = Summarize-Jtl $jtl
$blkRow = [pscustomobject]@{
  scenario='risk_profile_block'; concurrency=50; run=1
  requests=$(if($sumBlk){$sumBlk.requests}else{'NA'})
  http_200=$(if($sumBlk){$sumBlk.http_200}else{'NA'})
  business_409=$(if($sumBlk){$sumBlk.business_409}else{'NA'})
  http_503=$(if($sumBlk){$sumBlk.http_503}else{'NA'})
  socket_timeout=$(if($sumBlk){$sumBlk.socket_timeout}else{'NA'})
  system_errors=$(if($sumBlk){$sumBlk.system_errors}else{'NA'})
  completion_rate=$(if($sumBlk){$sumBlk.completion_rate}else{'NA'})
  system_error_rate=$(if($sumBlk){$sumBlk.system_error_rate}else{'NA'})
  throughput_rps=$(if($sumBlk){$sumBlk.throughput_rps}else{'NA'})
  avg_ms=$(if($sumBlk){$sumBlk.avg_ms}else{'NA'}); p50_ms=$(if($sumBlk){$sumBlk.p50_ms}else{'NA'})
  p90_ms=$(if($sumBlk){$sumBlk.p90_ms}else{'NA'}); p95_ms=$(if($sumBlk){$sumBlk.p95_ms}else{'NA'})
  p99_ms=$(if($sumBlk){$sumBlk.p99_ms}else{'NA'}); max_ms=$(if($sumBlk){$sumBlk.max_ms}else{'NA'})
  orders='0'; remaining_stock=(Get-Metric $valBlock 'METRIC_REMAINING'); bad_rows=(Get-BadRows $valBlock)
  hikari_timeout_delta='NA'; hikari_pending_peak='NA'; hikari_acquire_max='NA'
  mysql_lock_waits_delta='NA'; mysql_lock_time_delta='NA'
  autopay_queue_peak='NA'; autopay_rejected='NA'
}
$rowsOut.Add($blkRow)

Prom 'suite-after'
$rowsOut | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $OutDir 'final_summary.csv')
$rowsOut | Format-Table scenario,concurrency,run,http_200,business_409,http_503,socket_timeout,system_errors,completion_rate,throughput_rps,p95_ms,p99_ms,bad_rows,clean -AutoSize |
  Out-String | Tee-Object (Join-Path $OutDir 'summary_table.txt') | Write-Host
"out_dir=$OutDir" | Set-Content (Join-Path $OutDir 'DONE.txt')
Write-Host "FINAL DONE: $OutDir"
