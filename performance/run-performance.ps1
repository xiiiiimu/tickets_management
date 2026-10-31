# Formal performance acceptance runner (Windows). Serial JMeter only; no HTML reports.
param(
  [string]$JMeterHome = "$env:USERPROFILE\Downloads\apache-jmeter-5.6.3\apache-jmeter-5.6.3",
  [string]$BaseUrl = "http://127.0.0.1:8082",
  [int[]]$ThreadLevels = @(20, 50, 100),
  [int]$Repeats = 3,
  [string]$JMeterHeap = "-Xms64m -Xmx192m",
  [int]$CooldownBetweenRunsSec = 8
)

$ErrorActionPreference = "Continue"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$PerfRoot = Join-Path $Root "performance"
$ResultsRoot = Join-Path $PerfRoot "results"
$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$OutDir = Join-Path $ResultsRoot $Stamp
$LogDir = Join-Path $OutDir "logs"
New-Item -ItemType Directory -Force -Path $OutDir, $LogDir | Out-Null

$JMeterJar = Join-Path $JMeterHome "bin\ApacheJMeter.jar"
$JavaExe = (Get-Command java -ErrorAction SilentlyContinue).Source
if (-not $JavaExe) { throw "java not found on PATH" }
if (-not (Test-Path $JMeterJar)) { throw "JMeter jar missing: $JMeterJar" }

$env:HEAP = $JMeterHeap
$FreeBefore = [math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1024, 0)

@"
stamp=$Stamp
base_url=$BaseUrl
thread_levels=$($ThreadLevels -join ',')
repeats=$Repeats
jmeter_heap=$JMeterHeap
gap_sec=$GapBetweenRunsSec
mode=non-GUI serial
html_report=disabled
stock_sku=perf_sku_stock20
initial_stock=20
free_mb_before_run=$FreeBefore
"@ | Set-Content -Encoding UTF8 (Join-Path $OutDir "run_config.txt")

function Invoke-Mysql([string]$Sql, [string]$OutFile = $null) {
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    if ($OutFile) {
      $Sql | docker exec -i credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local ticketing --table 2>$null |
        Out-File -Encoding UTF8 $OutFile
    } else {
      $Sql | docker exec -i credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local ticketing 2>$null | Out-Null
    }
  } finally { $ErrorActionPreference = $prev }
}

function Invoke-MysqlFile([string]$Path, [string]$OutFile = $null) {
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    if ($OutFile) {
      Get-Content $Path -Raw | docker exec -i credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local ticketing --table 2>$null |
        Out-File -Encoding UTF8 $OutFile
    } else {
      Get-Content $Path -Raw | docker exec -i credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local ticketing 2>$null | Out-Null
    }
  } finally { $ErrorActionPreference = $prev }
}

function Wait-Healthy {
  for ($i = 0; $i -lt 60; $i++) {
    try {
      $h = Invoke-RestMethod "$BaseUrl/actuator/health" -TimeoutSec 3
      if ($h.status -eq "UP") { return }
    } catch {}
    Start-Sleep 2
  }
  throw "App health not UP at $BaseUrl"
}

function Capture-Prometheus([string]$Name) {
  try {
    Invoke-WebRequest "$BaseUrl/actuator/prometheus" -UseBasicParsing -TimeoutSec 10 -OutFile (Join-Path $OutDir "prometheus-$Name.txt")
  } catch {
    "capture_failed=$($_.Exception.Message)" | Set-Content (Join-Path $OutDir "prometheus-$Name.txt")
  }
}

function Write-Env {
  $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
  $ram = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory
  $javaVer = (cmd /c "java -version 2>&1" | Out-String).Trim() -replace "`r?`n", "; "
  $jmVer = (cmd /c "`"$JavaExe`" $JMeterHeap -jar `"$JMeterJar`" --version 2>&1" | Select-Object -First 8 | Out-String).Trim() -replace "`r?`n", "; "
  $lines = @(
    "timestamp=$Stamp",
    "os=$([System.Environment]::OSVersion.VersionString)",
    "cpu=$($cpu.Name)",
    "cores=$($cpu.NumberOfCores)",
    "logical=$($cpu.NumberOfLogicalProcessors)",
    ("ram_gb={0:N1}" -f ($ram / 1GB)),
    "free_mb_at_env=$([math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024,0))",
    "java=$javaVer",
    "jmeter_jar=$JMeterJar",
    "jmeter=$jmVer",
    "jmeter_heap=$JMeterHeap",
    "base_url=$BaseUrl",
    "db=docker:ticket-mysql/ticketing host_port=3307",
    "redis=docker:ticket-redis",
    "mq=docker:rocketmq 4.9.7",
    "platform=docker credit_risk-ticket-platform:perf mem_limit=768m heap=384m",
    "openai_key_present=$([bool]$env:OPENAI_API_KEY)",
    "docker=$(cmd /c 'docker version --format {{.Server.Version}} 2>nul')"
  )
  $lines | Set-Content -Encoding UTF8 (Join-Path $OutDir "environment.txt")
}

function Summarize-Jtl([string]$JtlPath, [string]$Scenario, $Concurrency, $RunIndex) {
  if (-not (Test-Path $JtlPath)) {
    return [pscustomobject]@{
      scenario = $Scenario; concurrency = $Concurrency; run_index = $RunIndex
      requests = ''; success = ''; business_rejected = ''; system_errors = ''; error_rate = ''
      throughput_rps = ''; avg_ms = ''; p50_ms = ''; p90_ms = ''; p95_ms = ''; p99_ms = ''; max_ms = ''
      bad_rows = ''; host_status = 'MISSING_JTL'; note = 'jtl missing'
    }
  }
  $rows = @(Import-Csv $JtlPath)
  if ($rows.Count -eq 0) {
    return [pscustomobject]@{
      scenario = $Scenario; concurrency = $Concurrency; run_index = $RunIndex
      requests = 0; success = 0; business_rejected = 0; system_errors = 0; error_rate = ''
      throughput_rps = ''; avg_ms = ''; p50_ms = ''; p90_ms = ''; p95_ms = ''; p99_ms = ''; max_ms = ''
      bad_rows = ''; host_status = 'EMPTY_JTL'; note = 'empty jtl'
    }
  }
  $elapsed = @($rows | ForEach-Object { [int]$_.elapsed })
  $sorted = $elapsed | Sort-Object
  function Pct([double]$p) {
    if ($sorted.Count -eq 0) { return '' }
    $idx = [Math]::Min($sorted.Count - 1, [Math]::Ceiling($sorted.Count * $p) - 1)
    if ($idx -lt 0) { $idx = 0 }
    return $sorted[$idx]
  }
  $biz = 0; $sys = 0; $ok = 0
  foreach ($row in $rows) {
    $code = 0
    [void][int]::TryParse([string]$row.responseCode, [ref]$code)
    $succ = ($row.success -eq 'true')
    if ($succ) { $ok++ }
    elseif ($code -in 400, 403, 409, 429) { $biz++ }
    else { $sys++ }
  }
  $times = @($rows | ForEach-Object { [long]$_.timeStamp } | Sort-Object)
  $durationSec = [Math]::Max(0.001, ($times[-1] - $times[0]) / 1000.0)
  # For single-sample or same-timestamp bursts, use wall elapsed from min to max response end approx
  if ($rows.Count -gt 1 -and $durationSec -lt 0.05) {
    $durationSec = [Math]::Max(0.05, (($times[-1] - $times[0]) + ($sorted | Measure-Object -Average).Average) / 1000.0)
  }
  $tps = [Math]::Round($rows.Count / $durationSec, 2)
  $avg = [Math]::Round(($elapsed | Measure-Object -Average).Average, 2)
  $errRate = [Math]::Round(100.0 * ($biz + $sys) / [Math]::Max(1, $rows.Count), 2)
  return [pscustomobject]@{
    scenario           = $Scenario
    concurrency        = $Concurrency
    run_index          = $RunIndex
    requests           = $rows.Count
    success            = $ok
    business_rejected  = $biz
    system_errors      = $sys
    error_rate         = $errRate
    throughput_rps     = $tps
    avg_ms             = $avg
    p50_ms             = (Pct 0.5)
    p90_ms             = (Pct 0.9)
    p95_ms             = (Pct 0.95)
    p99_ms             = (Pct 0.99)
    max_ms             = ($sorted | Select-Object -Last 1)
    bad_rows           = ''
    host_status        = 'OK'
    note               = ''
  }
}

function Run-JMeter([string]$Plan, [hashtable]$Props, [string]$Name) {
  $jtl = Join-Path $OutDir "$Name.jtl"
  $log = Join-Path $LogDir "$Name.log"
  $argList = @($JMeterHeap.Split(' ') + @('-jar', $JMeterJar, '-n', '-t', $Plan, '-l', $jtl, '-j', $log))
  foreach ($k in $Props.Keys) { $argList += "-J$k=$($Props[$k])" }
  Write-Host "JMeter $Name ..."
  $p = Start-Process -FilePath $JavaExe -ArgumentList $argList -Wait -PassThru -NoNewWindow
  if ($p.ExitCode -ne 0) {
    Write-Warning "JMeter exit=$($p.ExitCode) for $Name"
    $oom = $false
    if (Test-Path $log) {
      $tail = Get-Content $log -Tail 40 -ErrorAction SilentlyContinue | Out-String
      if ($tail -match 'OutOfMemory|insufficient memory|Native memory allocation') { $oom = $true }
    }
    return @{ exitCode = $p.ExitCode; oom = $oom; jtl = $jtl }
  }
  return @{ exitCode = 0; oom = $false; jtl = $jtl }
}

function Login-Users([int]$FromId, [int]$ToId, [string]$CsvPath) {
  $lines = @("userId,token,deviceId")
  $map = @{}
  for ($id = $FromId; $id -le $ToId; $id++) {
    $phone = "1399{0:D7}" -f ($id - 90000)
    $codeResp = Invoke-RestMethod -Method Post "$BaseUrl/api/user/code?phone=$phone"
    $code = $codeResp.data
    if (-not $code) { throw "login code missing for $phone" }
    $login = Invoke-RestMethod -Method Post "$BaseUrl/api/user/login" -ContentType "application/json" -Body (@{ phone = $phone; code = $code } | ConvertTo-Json)
    $token = $login.data
    if (-not $token) { throw "token missing for $phone" }
    $me = Invoke-RestMethod "$BaseUrl/api/user/me" -Headers @{ authorization = $token }
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

function Get-BadRows([string]$ValFile) {
  if (-not (Test-Path $ValFile)) { return '' }
  $text = Get-Content $ValFile -Raw
  $sum = 0
  $matches = [regex]::Matches($text, '(?ms)\|\s*(NEG_STOCK|ORDERS_OVER_STOCK|DUP_REQUEST_RESERVATION|DUP_RESERVATION_ORDER|DUP_PAYMENT_SUCCESS|DUP_ISSUED_SEQ|PURCHASE_OVER_LIMIT|STOCK_CONSISTENCY|HELD_EXCEEDS_TOTAL|OUTBOX_FAILED|BLOCK_ORDERS|BLOCK_RESERVATIONS)\s*\|\s*(\d+)\s*\|')
  foreach ($m in $matches) { $sum += [int]$m.Groups[2].Value }
  return $sum
}

function Get-Metric([string]$ValFile, [string]$Name) {
  if (-not (Test-Path $ValFile)) { return '' }
  $m = [regex]::Match((Get-Content $ValFile -Raw), "(?ms)\|\s*$Name\s*\|\s*(\d+)\s*\|")
  if ($m.Success) { return [int]$m.Groups[1].Value }
  return ''
}

# -------- main --------
Write-Env
Wait-Healthy
Capture-Prometheus "before"
Write-Host "Init SQL..."
Invoke-MysqlFile (Join-Path $PerfRoot "sql\init_perf_data.sql")
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
docker exec credit_risk-ticket-redis-1 redis-cli EVAL "local k=redis.call('keys','*perf_sku*'); for i=1,#k do redis.call('del',k[i]) end; return #k" 0 2>$null | Out-Null

$summaries = New-Object System.Collections.Generic.List[object]
$planStock = Join-Path $PerfRoot "jmeter\stock_contention.jmx"
$oomEvents = New-Object System.Collections.Generic.List[string]
$hostLimit = $false

foreach ($t in $ThreadLevels) {
  for ($r = 1; $r -le $Repeats; $r++) {
    $name = "stock20_t${t}_r$r"
    Write-Host "=== $name ==="
    Reset-Stock20
    Capture-Prometheus "during-$name"
    $run = Run-JMeter $planStock @{
      host = "127.0.0.1"; port = "8082"; threads = "$t"; ramp = "2"; loops = "1"
      users = $usersCsv; skuId = "perf_sku_stock20"; sessionId = "perf_session"
    } $name
    $valFile = Join-Path $OutDir "validation-$name.txt"
    Invoke-MysqlFile (Join-Path $PerfRoot "sql\validate_stock20_round.sql") $valFile
    $sum = Summarize-Jtl $run.jtl "stock_contention" $t $r
    $sum.bad_rows = Get-BadRows $valFile
    $remain = Get-Metric $valFile 'METRIC_REMAINING'
    $orders = Get-Metric $valFile 'METRIC_VALID_ORDERS'
    $bizExtra = "initial_stock=20;remaining=$remain;valid_orders=$orders"
    if ($run.oom -or $run.exitCode -ne 0 -and -not (Test-Path $run.jtl)) {
      $sum.host_status = 'HOST_RESOURCE_LIMIT'
      $sum.note = "jmeter_exit=$($run.exitCode);oom=$($run.oom);$bizExtra"
      $oomEvents.Add($name)
      $hostLimit = $true
      $summaries.Add($sum)
      "HOST_RESOURCE_LIMIT at $name" | Add-Content (Join-Path $OutDir "host_limits.txt")
      # skip remaining higher concurrency if this level OOM'd completely
      if ($run.oom) { break }
    } else {
      $sum.note = $bizExtra
      if ($run.oom) { $sum.host_status = 'HOST_RESOURCE_LIMIT'; $oomEvents.Add($name); $hostLimit = $true }
      $summaries.Add($sum)
    }
    [GC]::Collect()
    Start-Sleep -Seconds $GapBetweenRunsSec
  }
  if ($hostLimit -and $oomEvents[-1] -like "stock20_t${t}_*") {
    Write-Warning "Host limit at concurrency $t; skipping higher levels if configured"
  }
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

# duplicate_request
Reset-Sku 'perf_sku_dup'
$run = Run-JMeter (Join-Path $PerfRoot "jmeter\duplicate_request_id.jmx") @{
  host = "127.0.0.1"; port = "8082"; threads = "50"; ramp = "2"; users = $usersSingle
  skuId = "perf_sku_dup"; sessionId = "perf_session"; requestId = "perf-dup-fixed-$Stamp"
} "duplicate_request"
$valDup = Join-Path $OutDir "validation-duplicate_request.txt"
Invoke-Mysql @"
SELECT 'DUP_REQUEST_RESERVATION' AS check_name, COUNT(*) AS bad_rows FROM (
  SELECT request_id FROM tb_reservation WHERE request_id='perf-dup-fixed-$Stamp' GROUP BY request_id HAVING COUNT(*)>1
) t;
SELECT 'RESERVATION_COUNT' AS metric, COUNT(*) AS value FROM tb_reservation WHERE request_id='perf-dup-fixed-$Stamp';
SELECT 'ORDER_COUNT' AS metric, COUNT(*) AS value FROM tb_ticket_order WHERE reservation_id IN (SELECT id FROM tb_reservation WHERE request_id='perf-dup-fixed-$Stamp');
SELECT 'OUTBOX_COUNT' AS metric, COUNT(*) AS value FROM tb_outbox_message WHERE aggregate_id IN (SELECT id FROM tb_ticket_order WHERE reservation_id IN (SELECT id FROM tb_reservation WHERE request_id='perf-dup-fixed-$Stamp'));
"@ $valDup
$sum = Summarize-Jtl $run.jtl "duplicate_request" 50 1
$sum.bad_rows = Get-BadRows $valDup
if ($run.oom) { $sum.host_status = 'HOST_RESOURCE_LIMIT'; $oomEvents.Add('duplicate_request'); $hostLimit = $true }
$summaries.Add($sum)
Start-Sleep -Seconds $GapBetweenRunsSec

# risk_profile_block
Reset-Sku 'perf_sku_block'
docker exec credit_risk-ticket-redis-1 redis-cli DEL "risk:profile:user:$blockUid" 2>$null | Out-Null
$run = Run-JMeter (Join-Path $PerfRoot "jmeter\risk_profile_block.jmx") @{
  host = "127.0.0.1"; port = "8082"; threads = "50"; ramp = "2"; users = $usersBlock
  skuId = "perf_sku_block"; sessionId = "perf_session"
} "risk_profile_block"
$valBlock = Join-Path $OutDir "validation-risk_profile_block.txt"
Invoke-Mysql @"
SELECT 'BLOCK_ORDERS' AS check_name, COUNT(*) AS bad_rows FROM tb_ticket_order WHERE sku_id='perf_sku_block' AND status NOT IN ('CANCELLED','PAY_TIMEOUT','RISK_BLOCKED');
SELECT 'BLOCK_RESERVATIONS' AS check_name, COUNT(*) AS bad_rows FROM tb_reservation WHERE sku_id='perf_sku_block';
SELECT 'NEG_STOCK' AS check_name, COUNT(*) AS bad_rows FROM tb_ticket_sku WHERE id='perf_sku_block' AND remaining_stock<0;
SELECT 'METRIC_REMAINING' AS metric, remaining_stock AS value FROM tb_ticket_sku WHERE id='perf_sku_block';
"@ $valBlock
$sum = Summarize-Jtl $run.jtl "risk_profile_block" 50 1
$sum.bad_rows = Get-BadRows $valBlock
# For BLOCK scenario, HTTP 409 is expected success of test assertion path - jmeter assertion marks success
if ($run.oom) { $sum.host_status = 'HOST_RESOURCE_LIMIT'; $oomEvents.Add('risk_profile_block'); $hostLimit = $true }
$summaries.Add($sum)
Start-Sleep -Seconds $GapBetweenRunsSec

# risk_pressure_reserve
Reset-Sku 'perf_sku_risk'
$run = Run-JMeter $planStock @{
  host = "127.0.0.1"; port = "8082"; threads = "50"; ramp = "3"; loops = "1"
  users = $usersCsv; skuId = "perf_sku_risk"; sessionId = "perf_session"
} "risk_pressure_reserve"
Start-Sleep -Seconds 20
$valRisk = Join-Path $OutDir "validation-risk_pressure_reserve.txt"
Invoke-Mysql @"
SELECT 'NEG_STOCK' AS check_name, COUNT(*) AS bad_rows FROM tb_ticket_sku WHERE id='perf_sku_risk' AND remaining_stock<0;
SELECT 'ORDERS_OVER_STOCK' AS check_name, COUNT(*) AS bad_rows FROM (
  SELECT s.id FROM tb_ticket_sku s
  LEFT JOIN tb_ticket_order o ON o.sku_id=s.id AND o.status NOT IN ('CANCELLED','PAY_TIMEOUT','RISK_BLOCKED')
  WHERE s.id='perf_sku_risk' GROUP BY s.id,s.total_stock
  HAVING COALESCE(SUM(o.quantity),0)>s.total_stock
) t;
SELECT 'METRIC_REMAINING' AS metric, remaining_stock AS value FROM tb_ticket_sku WHERE id='perf_sku_risk';
SELECT 'METRIC_VALID_ORDERS' AS metric, COUNT(*) AS value FROM tb_ticket_order WHERE sku_id='perf_sku_risk' AND status NOT IN ('CANCELLED','PAY_TIMEOUT','RISK_BLOCKED');
"@ $valRisk
$sum = Summarize-Jtl $run.jtl "risk_pressure_reserve" 50 1
$sum.bad_rows = Get-BadRows $valRisk
if ($run.oom) { $sum.host_status = 'HOST_RESOURCE_LIMIT'; $oomEvents.Add('risk_pressure_reserve'); $hostLimit = $true }
$summaries.Add($sum)

Capture-Prometheus "after"
Invoke-MysqlFile (Join-Path $PerfRoot "sql\validate_perf.sql") (Join-Path $OutDir "validation-final.txt")

$csv = Join-Path $OutDir "summary.csv"
$summaries | Export-Csv -NoTypeInformation -Encoding UTF8 $csv
$summaries | Format-Table scenario, concurrency, run_index, requests, success, business_rejected, system_errors, error_rate, throughput_rps, avg_ms, p95_ms, p99_ms, bad_rows, host_status -AutoSize | Out-String | Tee-Object -FilePath (Join-Path $OutDir "summary_table.txt") | Write-Host

if ($oomEvents.Count -gt 0) {
  $oomEvents | Set-Content (Join-Path $OutDir "oom_events.txt")
}
"host_limit=$hostLimit" | Set-Content (Join-Path $OutDir "acceptance_flags.txt")
Write-Host "Results: $OutDir"
