# Steady-state capacity ladder. Continuous-loop JMeter; wall-clock QPS over 60s measurement window only.
param(
  [string]$JMeterHome = "$env:USERPROFILE\Downloads\apache-jmeter-5.6.3\apache-jmeter-5.6.3",
  [string]$BaseUrl = "http://127.0.0.1:8082",
  [string]$JMeterHeap = "-Xms64m -Xmx256m",
  [string]$SkuId = "perf_sku_steady",
  [int]$InitialStock = 50000,
  [int]$PurchaseLimit = 100000,
  [int]$RampSec = 10,
  [int]$WarmupSec = 15,
  [int]$MeasureSec = 60,
  [int]$CooldownSec = 10,
  [int]$DrainMaxSec = 60,
  [int[]]$ThreadLevels = @(20, 50, 100, 150, 200),
  [switch]$RepeatStableOnly,
  [int[]]$RepeatLevels = @(),
  [int]$Repeats = 1
)

$ErrorActionPreference = "Continue"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$PerfRoot = Join-Path $Root "performance"
$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$OutDir = Join-Path $PerfRoot "results\steady-state-$Stamp"
$LogDir = Join-Path $OutDir "logs"
New-Item -ItemType Directory -Force -Path $OutDir, $LogDir | Out-Null

$JMeterJar = Join-Path $JMeterHome "bin\ApacheJMeter.jar"
$JavaExe = (Get-Command java -ErrorAction SilentlyContinue).Source
$DurationSec = $RampSec + $WarmupSec + $MeasureSec + $CooldownSec
$Plan = Join-Path $PerfRoot "jmeter\steady_state_reserve.jmx"

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
  $tmp=Join-Path $env:TEMP ("ssn-"+[guid]::NewGuid().ToString('N')+".sql")
  Set-Content -Path $tmp -Value $Sql -Encoding ASCII
  $prev=$ErrorActionPreference; $ErrorActionPreference='Continue'
  try {
    $out=Get-Content $tmp -Raw | docker exec -i credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local ticketing -N 2>$null
    $line=(@($out)|Where-Object{$_ -and $_ -notmatch 'Warning'}|Select-Object -First 1)
    if(-not $line){return 'NA'}; return ([string]$line).Trim()
  } finally { $ErrorActionPreference=$prev; Remove-Item $tmp -Force -EA SilentlyContinue }
}
function MysqlStatus([string]$Name) {
  $v=MysqlN "SHOW GLOBAL STATUS LIKE '$Name';"
  if($v -eq 'NA'-or -not $v){return 'NA'}
  if($v -match '(\d+)\s*$'){return [long]$Matches[1]}
  $p=$v -split '\s+'; if($p.Count -ge 2 -and $p[-1] -match '^\d+$'){return [long]$p[-1]}; return 'NA'
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
  if($ad -lt $bd){return "COUNTER_RESET(before=$bd,after=$ad)"}
  return [Math]::Round($ad-$bd,0)
}

function Login-Users([int]$FromId,[int]$ToId,[string]$CsvPath) {
  $lines=@("userId,token,deviceId")
  for($id=$FromId;$id -le $ToId;$id++){
    if($id -eq 90100){continue}
    $phone="1399{0:D7}" -f ($id-90000)
    $code=(Invoke-RestMethod -Method Post "$BaseUrl/api/user/code?phone=$phone").data
    $token=(Invoke-RestMethod -Method Post "$BaseUrl/api/user/login" -ContentType "application/json" -Body (@{phone=$phone;code=$code}|ConvertTo-Json)).data
    $me=Invoke-RestMethod "$BaseUrl/api/user/me" -Headers @{authorization=$token}
    $uid=[int]$me.data.id
    $lines += "$uid,$token,ss-device-$uid"
  }
  $lines | Set-Content -Encoding UTF8 $CsvPath
}

function Ensure-Sku {
  Invoke-Mysql @"
INSERT INTO tb_event(id,name,description,venue,status) VALUES('perf_event','PERF','fixture','Arena','ON_SALE')
 ON DUPLICATE KEY UPDATE status='ON_SALE';
INSERT INTO tb_event_session(id,event_id,starts_at) VALUES('perf_session','perf_event','2027-12-31 19:30:00')
 ON DUPLICATE KEY UPDATE event_id='perf_event';
INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,sale_start_time,sale_end_time,purchase_limit,status,version)
VALUES('$SkuId','perf_session','STEADY',10.00,$InitialStock,$InitialStock,'2020-01-01 00:00:00','2030-12-31 23:59:59',$PurchaseLimit,'ON_SALE',0)
ON DUPLICATE KEY UPDATE total_stock=$InitialStock, remaining_stock=$InitialStock, purchase_limit=$PurchaseLimit, status='ON_SALE', version=version+1;
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
  $u=Import-Csv $UsersCsv | Select-Object -First 1
  $body=@{requestId="ss-warm-$Stamp-$(Get-Random)";userId=[int]$u.userId;eventSessionId='perf_session';ticketSkuId=$SkuId;quantity=1;deviceId=$u.deviceId}|ConvertTo-Json
  try{ Invoke-RestMethod -Method Post "$BaseUrl/api/tickets/reserve" -Headers @{authorization=$u.token;'Content-Type'='application/json'} -Body $body -TimeoutSec 60 | Out-Null } catch {}
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

function Analyze-Window([string]$JtlPath, [long]$WindowStartMs, [long]$WindowEndMs) {
  if(-not (Test-Path $JtlPath) -or (Get-Item $JtlPath).Length -eq 0){ return $null }
  $rows=@(Import-Csv $JtlPath)
  $win=@()
  foreach($r in $rows){
    $ts=[long]$r.timeStamp
    if($ts -ge $WindowStartMs -and $ts -lt $WindowEndMs){ $win += $r }
  }
  $http200=0;$biz409=0;$http503=0;$sock=0;$other=0;$sys=0
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
    $other++;$sys++
  }
  function Pct($list,[double]$p){
    if($list.Count -eq 0){return 'NA'}
    $s=@($list|Sort-Object); $i=[Math]::Min($s.Count-1,[Math]::Ceiling($s.Count*$p)-1); if($i -lt 0){$i=0}; return $s[$i]
  }
  function Avg($list){ if($list.Count -eq 0){return 'NA'}; return [Math]::Round(($list|Measure-Object -Average).Average,2) }
  $measSec = ($WindowEndMs - $WindowStartMs) / 1000.0
  if($measSec -le 0){$measSec=0.001}
  $soldOutRatio = if($win.Count -eq 0){0}else{$biz409 / [double]$win.Count}
  return [pscustomobject]@{
    window_requests=$win.Count; total_jtl=$rows.Count
    http_200=$http200; business_409=$biz409; http_503=$http503; socket_timeout=$sock
    other_system=$other; system_errors=$sys
    system_error_rate=[Math]::Round(100.0*$sys/[Math]::Max(1,$win.Count),3)
    sold_out_ratio=[Math]::Round($soldOutRatio,4)
    measure_seconds=[Math]::Round($measSec,3)
    success_qps=[Math]::Round($http200/$measSec,2)
    completion_qps=[Math]::Round(($http200+$biz409)/$measSec,2)
    avg_ms=(Avg $lats); p50_ms=(Pct $lats 0.5); p90_ms=(Pct $lats 0.9)
    p95_ms=(Pct $lats 0.95); p99_ms=(Pct $lats 0.99)
    max_ms=if($lats.Count -eq 0){'NA'}else{(@($lats|Sort-Object)[-1])}
  }
}

function Get-BadRows([string]$f){
  if(-not (Test-Path $f)){return 'NA'}; $sum=0
  foreach($m in [regex]::Matches((Get-Content $f -Raw),'(?ms)\|\s*(NEG_STOCK|ORDERS_OVER_STOCK|DUP_REQUEST_RESERVATION|DUP_RESERVATION_ORDER|DUP_PAYMENT_SUCCESS|DUP_ISSUED_SEQ|PURCHASE_OVER_LIMIT|INVALID_ORDER_STATE)\s*\|\s*(\d+)\s*\|')){ $sum+=[int]$m.Groups[2].Value }
  return $sum
}
function Get-Metric([string]$f,[string]$n){
  if(-not (Test-Path $f)){return 'NA'}
  $m=[regex]::Match((Get-Content $f -Raw),"(?ms)\|\s*$n\s*\|\s*(\d+)\s*\|"); if($m.Success){return $m.Groups[1].Value}; return 'NA'
}

function Wait-Drain([string]$Tag) {
  $peakQ=0.0; $t0=Get-Date; $deadline=$t0.AddSeconds($DrainMaxSec); $stable=0
  while((Get-Date) -lt $deadline){
    Prom "drain-$Tag-$(Get-Date -Format HHmmssfff)"
    $latest=Get-ChildItem $OutDir -Filter "prometheus-drain-$Tag-*.txt" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $q=AsDouble (PromVal $latest.FullName 'threadpool_queue\{pool="autopay",\} ([0-9.]+)')
    $a=AsDouble (PromVal $latest.FullName 'threadpool_active\{pool="autopay",\} ([0-9.]+)')
    if($null -ne $q -and $q -gt $peakQ){$peakQ=$q}
    $w=MysqlN "SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id='$SkuId' AND status='WAITING_PAYMENT';"
    $p=MysqlN "SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id='$SkuId' AND status='PAYING';"
    if(($null -eq $q -or $q -eq 0) -and ($null -eq $a -or $a -eq 0) -and $w -eq '0' -and $p -eq '0'){ $stable++; if($stable -ge 2){break} } else { $stable=0 }
    Start-Sleep 2
  }
  Prom "drain-final-$Tag"
  $drainSec=[Math]::Round(((Get-Date)-$t0).TotalSeconds,1)
  return [pscustomobject]@{queue_peak=$peakQ; drain_seconds=$drainSec
    waiting=(MysqlN "SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id='$SkuId' AND status='WAITING_PAYMENT';")
    paid=(MysqlN "SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id='$SkuId' AND status='PAID';")
    issued=(MysqlN "SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id='$SkuId' AND status='ISSUED';")
    rej=(PromVal (Join-Path $OutDir "prometheus-drain-final-$Tag.txt") 'threadpool_reject\{pool="autopay",\} ([0-9.]+)')
    fail=(PromVal (Join-Path $OutDir "prometheus-drain-final-$Tag.txt") 'payment_auto_failure_total ([0-9.]+)')
    qfinal=(PromVal (Join-Path $OutDir "prometheus-drain-final-$Tag.txt") 'threadpool_queue\{pool="autopay",\} ([0-9.]+)')
  }
}

function Classify-Row($row) {
  if($row.sold_out_ratio -gt 0.01){ return 'INVALID' }
  if($row.system_error_rate -gt 1.0){ return 'SATURATED' }
  if($row.http_503 -gt 0 -or $row.socket_timeout -gt 0){ return 'SATURATED' }
  if($row.hikari_timeout_delta -ne '0' -and $row.hikari_timeout_delta -ne 0 -and "$($row.hikari_timeout_delta)" -notmatch '^0'){
    if("$($row.hikari_timeout_delta)" -match 'COUNTER_RESET'){ } else {
      $td=AsDouble $row.hikari_timeout_delta
      if($null -ne $td -and $td -gt 0){ return 'SATURATED' }
    }
  }
  if($row.drain_waiting -ne '0' -or ($null -ne (AsDouble $row.autopay_queue_final) -and (AsDouble $row.autopay_queue_final) -gt 0)){
    return 'SATURATED'
  }
  if($row.bad_rows -ne 0 -and $row.bad_rows -ne '0'){ return 'FAIL' }
  return 'STABLE'
}

# ---- env gate ----
Prom 'env-check'
$hikari=PromVal (Join-Path $OutDir 'prometheus-env-check.txt') 'hikaricp_connections_max\{pool="HikariPool-1",\} ([0-9.]+)'
$ac=PromVal (Join-Path $OutDir 'prometheus-env-check.txt') 'threadpool_core\{pool="autopay",\} ([0-9.]+)'
$am=PromVal (Join-Path $OutDir 'prometheus-env-check.txt') 'threadpool_max\{pool="autopay",\} ([0-9.]+)'
$mem=docker inspect credit_risk-ticket-risk-platform-1 --format '{{.HostConfig.Memory}}' 2>$null
$jvm=(docker inspect credit_risk-ticket-risk-platform-1 --format '{{range .Config.Env}}{{println .}}{{end}}' 2>$null | Select-String 'JAVA_TOOL' | ForEach-Object{$_.Line})
if($hikari -ne '10.0' -or $ac -ne '2.0' -or $am -ne '4.0' -or $mem -ne '805306368' -or "$jvm" -notmatch 'Xmx384m'){
  "DIFFERENT_ENVIRONMENT" | Tee-Object (Join-Path $OutDir 'DIFFERENT_ENVIRONMENT.txt'); throw 'DIFFERENT_ENVIRONMENT'
}

$os=Get-CimInstance Win32_OperatingSystem
$cpu=Get-CimInstance Win32_Processor | Select-Object -First 1
@"
stamp=$Stamp
git_head=$(git -C $Root rev-parse HEAD)
git_describe=$(git -C $Root describe --always --dirty 2>$null)
cpu=$($cpu.Name)
cpu_logical=$($cpu.NumberOfLogicalProcessors)
ram_total_mb=$([math]::Round($os.TotalVisibleMemorySize/1024,0))
ram_free_mb=$([math]::Round($os.FreePhysicalMemory/1024,0))
docker=$(docker version --format '{{.Server.Version}}' 2>$null)
jvm=$jvm
platform_mem=$mem
hikari_max=$hikari
autopay=$ac/$am
jmeter=5.6.3
sku=$SkuId
initial_stock=$InitialStock
purchase_limit=$PurchaseLimit
ramp_s=$RampSec
warmup_s=$WarmupSec
measure_s=$MeasureSec
cooldown_s=$CooldownSec
duration_s=$DurationSec
qps_method=HTTP200_in_[t0+ramp+warmup, t0+ramp+warmup+measure) / measure_seconds
"@ | Set-Content -Encoding UTF8 (Join-Path $OutDir 'environment.txt')

for($i=0;$i -lt 30;$i++){ try{ if((Invoke-RestMethod "$BaseUrl/actuator/health" -TimeoutSec 3).status -eq 'UP'){break} }catch{}; Start-Sleep 2 }

Get-Content (Join-Path $PerfRoot 'sql\init_perf_data.sql') -Raw | docker exec -i credit_risk-ticket-mysql-1 mysql -uticketing -pticketing-local ticketing 2>$null | Out-Null
# ensure enough users exist (extend fixture phones if needed)
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
"@
Ensure-Sku
Invoke-Mysql "UPDATE tb_risk_rule SET config=JSON_SET(config,'$.threshold',100000) WHERE rule_code IN ('IP_FREQUENCY','DEVICE_FREQUENCY','USER_FREQUENCY');"
Invoke-Mysql "DELETE FROM tb_risk_profile WHERE subject_value IN (SELECT CAST(id AS CHAR) FROM tb_user WHERE id BETWEEN 90001 AND 90200) AND decision='BLOCK' AND id<>'perf_profile_block';"

$usersCsv=Join-Path $OutDir 'users.csv'
Write-Host 'Login users 90001-90200 skip 90100...'
Login-Users 90001 90200 $usersCsv
Invoke-Mysql "INSERT INTO tb_wallet_account(user_id,available_balance,frozen_balance,version) SELECT id,100000,0,0 FROM tb_user WHERE id BETWEEN 90001 AND 90200 ON DUPLICATE KEY UPDATE available_balance=GREATEST(available_balance,100000);"

$levels = if($RepeatLevels.Count -gt 0){ $RepeatLevels } else { $ThreadLevels }
$repCount = if($RepeatLevels.Count -gt 0){ $Repeats } else { 1 }
$rowsOut=New-Object System.Collections.Generic.List[object]
$stopHigher=$false
$prevQps=$null

foreach($t in $levels){
  if($stopHigher){ Write-Host "SKIP concurrency $t (prior saturation)"; break }
  for($run=1; $run -le $repCount; $run++){
    $name="ss_t${t}_r$run"
    Write-Host "=== $name duration=${DurationSec}s ==="
    Reset-Sku; Warm-Sku $usersCsv

    $lwB=MysqlStatus 'Innodb_row_lock_waits'; $ltB=MysqlStatus 'Innodb_row_lock_time'; $ltmB=MysqlStatus 'Innodb_row_lock_time_max'
    Prom "before-$name"
    $toB=PromVal (Join-Path $OutDir "prometheus-before-$name.txt") 'hikaricp_connections_timeout_total\{pool="HikariPool-1",\} ([0-9.]+)'

    $jtl=Join-Path $OutDir "$name.jtl"; $log=Join-Path $LogDir "$name.log"
    $t0=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $argList=@($JMeterHeap.Split(' ')+@('-jar',$JMeterJar,'-n','-t',$Plan,'-l',$jtl,'-j',$log,
      '-Jhost=127.0.0.1','-Jport=8082',"-Jthreads=$t","-Jramp=$RampSec","-Jduration=$DurationSec",
      "-Jusers=$usersCsv","-JskuId=$SkuId",'-JsessionId=perf_session'))
    $proc=Start-Process -FilePath $JavaExe -ArgumentList $argList -PassThru -NoNewWindow

    # sample during measurement window midpoint
    $midWait = $RampSec + $WarmupSec + [int]($MeasureSec/2)
    Start-Sleep $midWait
    Prom "during-$name"
    $pendingDuring=PromVal (Join-Path $OutDir "prometheus-during-$name.txt") 'hikaricp_connections_pending\{pool="HikariPool-1",\} ([0-9.]+)'
    $activeDuring=PromVal (Join-Path $OutDir "prometheus-during-$name.txt") 'hikaricp_connections_active\{pool="HikariPool-1",\} ([0-9.]+)'
    $idleDuring=PromVal (Join-Path $OutDir "prometheus-during-$name.txt") 'hikaricp_connections_idle\{pool="HikariPool-1",\} ([0-9.]+)'
    $qDuring=PromVal (Join-Path $OutDir "prometheus-during-$name.txt") 'threadpool_queue\{pool="autopay",\} ([0-9.]+)'

    $waitLeft = [Math]::Max(5, $DurationSec - $midWait + 30)
    Wait-Process -Id $proc.Id -Timeout $waitLeft -ErrorAction SilentlyContinue
    if(-not $proc.HasExited){ Stop-Process -Id $proc.Id -Force -EA SilentlyContinue }

    Prom "after-$name"
    $toA=PromVal (Join-Path $OutDir "prometheus-after-$name.txt") 'hikaricp_connections_timeout_total\{pool="HikariPool-1",\} ([0-9.]+)'
    $acq=PromVal (Join-Path $OutDir "prometheus-after-$name.txt") 'hikaricp_connections_acquire_seconds_max\{pool="HikariPool-1",\} ([0-9.eE+-]+)'
    $usage=PromVal (Join-Path $OutDir "prometheus-after-$name.txt") 'hikaricp_connections_usage_seconds_max\{pool="HikariPool-1",\} ([0-9.eE+-]+)'
    $pendingAfter=PromVal (Join-Path $OutDir "prometheus-after-$name.txt") 'hikaricp_connections_pending\{pool="HikariPool-1",\} ([0-9.]+)'

    $winStart = $t0 + ($RampSec + $WarmupSec)*1000
    $winEnd = $winStart + $MeasureSec*1000
    $sum = Analyze-Window $jtl $winStart $winEnd
    if($null -eq $sum){ Write-Warning "No JTL $name"; continue }

    $drain = Wait-Drain $name
    $val=Join-Path $OutDir "validation-$name.txt"
    Invoke-MysqlQuery @"
SELECT 'NEG_STOCK' AS check_name, COUNT(*) AS bad_rows FROM tb_ticket_sku WHERE id='$SkuId' AND (remaining_stock<0 OR remaining_stock>total_stock);
SELECT 'ORDERS_OVER_STOCK' AS check_name, COUNT(*) AS bad_rows FROM (
 SELECT s.id FROM tb_ticket_sku s LEFT JOIN tb_ticket_order o ON o.sku_id=s.id AND o.status NOT IN ('CANCELLED','PAY_TIMEOUT','RISK_BLOCKED')
 WHERE s.id='$SkuId' GROUP BY s.id,s.total_stock HAVING COALESCE(SUM(o.quantity),0)>s.total_stock) t;
SELECT 'DUP_REQUEST_RESERVATION' AS check_name, COUNT(*) AS bad_rows FROM (SELECT request_id FROM tb_reservation WHERE sku_id='$SkuId' GROUP BY request_id HAVING COUNT(*)>1) t;
SELECT 'DUP_RESERVATION_ORDER' AS check_name, COUNT(*) AS bad_rows FROM (SELECT reservation_id FROM tb_ticket_order WHERE sku_id='$SkuId' GROUP BY reservation_id HAVING COUNT(*)>1) t;
SELECT 'DUP_PAYMENT_SUCCESS' AS check_name, COUNT(*) AS bad_rows FROM (
 SELECT order_id FROM tb_payment_transaction WHERE order_id IN (SELECT id FROM tb_ticket_order WHERE sku_id='$SkuId') AND status='SUCCESS' AND type='PAYMENT' GROUP BY order_id HAVING COUNT(*)>1) t;
SELECT 'PURCHASE_OVER_LIMIT' AS check_name, COUNT(*) AS bad_rows FROM tb_user_purchase p JOIN tb_ticket_sku s ON s.id=p.sku_id WHERE s.id='$SkuId' AND p.quantity>s.purchase_limit;
SELECT 'INVALID_ORDER_STATE' AS check_name, COUNT(*) AS bad_rows FROM tb_ticket_order WHERE sku_id='$SkuId' AND status NOT IN ('WAITING_PAYMENT','PAYING','PAID','ISSUED','CANCELLED','PAY_TIMEOUT','RISK_BLOCKED');
SELECT 'ORDER_COUNT' AS metric, COUNT(*) AS value FROM tb_ticket_order WHERE sku_id='$SkuId';
SELECT 'METRIC_REMAINING' AS metric, remaining_stock AS value FROM tb_ticket_sku WHERE id='$SkuId';
SELECT 'PAID_OR_ISSUED' AS metric, COUNT(*) AS value FROM tb_ticket_order WHERE sku_id='$SkuId' AND status IN ('PAID','ISSUED');
SELECT 'WAITING_PAYMENT' AS metric, COUNT(*) AS value FROM tb_ticket_order WHERE sku_id='$SkuId' AND status='WAITING_PAYMENT';
"@ $val

    $lwA=MysqlStatus 'Innodb_row_lock_waits'; $ltA=MysqlStatus 'Innodb_row_lock_time'; $ltmA=MysqlStatus 'Innodb_row_lock_time_max'
    $tcA=MysqlStatus 'Threads_connected'; $trA=MysqlStatus 'Threads_running'
    $pendingPeak=0.0
    foreach($pv in @($pendingDuring,$pendingAfter)){ $d=AsDouble $pv; if($null -ne $d -and $d -gt $pendingPeak){$pendingPeak=$d} }

    $row=[pscustomobject]@{
      concurrency=$t; run=$run
      measure_seconds=$sum.measure_seconds
      window_requests=$sum.window_requests
      http_200=$sum.http_200; business_409=$sum.business_409; http_503=$sum.http_503
      socket_timeout=$sum.socket_timeout; system_errors=$sum.system_errors
      system_error_rate=$sum.system_error_rate; sold_out_ratio=$sum.sold_out_ratio
      success_qps=$sum.success_qps; completion_qps=$sum.completion_qps
      avg_ms=$sum.avg_ms; p50_ms=$sum.p50_ms; p90_ms=$sum.p90_ms; p95_ms=$sum.p95_ms; p99_ms=$sum.p99_ms; max_ms=$sum.max_ms
      orders=(Get-Metric $val 'ORDER_COUNT'); remaining=(Get-Metric $val 'METRIC_REMAINING')
      paid_or_issued=(Get-Metric $val 'PAID_OR_ISSUED'); waiting_final=(Get-Metric $val 'WAITING_PAYMENT')
      bad_rows=(Get-BadRows $val)
      hikari_timeout_delta=(TimeoutDelta $toB $toA); hikari_pending_peak=$pendingPeak
      hikari_acquire_max=$acq; hikari_usage_max=$usage
      hikari_active_during=$activeDuring; hikari_idle_during=$idleDuring
      mysql_lock_waits_delta=if($lwB -eq 'NA'-or $lwA -eq 'NA'){'NA'}else{([long]$lwA-[long]$lwB)}
      mysql_lock_time_delta=if($ltB -eq 'NA'-or $ltA -eq 'NA'){'NA'}else{([long]$ltA-[long]$ltB)}
      mysql_lock_time_max=$ltmA; mysql_threads_connected=$tcA; mysql_threads_running=$trA
      autopay_queue_during=$qDuring; autopay_queue_peak=$drain.queue_peak
      autopay_queue_final=$drain.qfinal; drain_seconds=$drain.drain_seconds
      drain_waiting=$drain.waiting; drain_paid=$drain.paid; drain_issued=$drain.issued
      autopay_rejected=$drain.rej; autopay_failure=$drain.fail
    }
    $status = Classify-Row $row
    # throughput plateau check vs previous concurrency (first-pass only)
    if($repCount -eq 1 -and $null -ne $prevQps -and $status -eq 'STABLE'){
      $growth = ($row.success_qps - $prevQps) / [Math]::Max(0.01,$prevQps)
      # concurrency step roughly +50% or more in our ladder
      if($growth -lt 0.10 -and ([double]$row.p95_ms -gt 1.5*[double]($rowsOut[-1].p95_ms))){
        $status='SATURATED'
      }
    }
    $row | Add-Member -NotePropertyName status -NotePropertyValue $status -Force
    $rowsOut.Add($row)
    ($row.PSObject.Properties|%{ "$($_.Name)=$($_.Value)" }) -join "`n" | Set-Content (Join-Path $OutDir "metrics-$name.txt")
    Write-Host ("RESULT t={0} r={1} success_qps={2} p95={3} p99={4} sys_err%={5} status={6}" -f $t,$run,$row.success_qps,$row.p95_ms,$row.p99_ms,$row.system_error_rate,$status)

    if($repCount -eq 1){
      $prevQps = $row.success_qps
      if($status -in @('SATURATED','INVALID','FAIL')){
        $stopHigher=$true
        Write-Host "Saturation/invalid detected at concurrency $t — stop raising concurrency."
        break
      }
    }
  }
}

$rowsOut | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $OutDir 'steady_summary.csv')
$rowsOut | Format-Table concurrency,run,success_qps,p95_ms,p99_ms,system_error_rate,hikari_pending_peak,hikari_timeout_delta,status,bad_rows -AutoSize |
  Out-String | Tee-Object (Join-Path $OutDir 'summary_table.txt') | Write-Host
"out_dir=$OutDir" | Set-Content (Join-Path $OutDir 'DONE.txt')
Write-Host "STEADY_STATE DONE: $OutDir"
