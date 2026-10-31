"""Offline-only summary of one joint run, including an interrupted run. Never sends requests."""
import argparse, collections, csv, hashlib, json, math, re
from pathlib import Path

def readprom(path):
    result=[]
    for line in path.read_text().splitlines():
        m=re.match(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*?)\})?\s+([^ ]+)$',line)
        if not m:continue
        try:value=float(m[3])
        except ValueError:continue
        result.append((m[1],dict(re.findall(r'(\w+)="([^"]*)"',m[2] or '')),value))
    return result

def main():
    ap=argparse.ArgumentParser();ap.add_argument('directory',type=Path);a=ap.parse_args();p=a.directory.resolve()
    cfg=json.loads((p/'configuration.json').read_text());t0=cfg['t0']
    before=readprom(p/'metrics-before.prom');after=readprom(p/'metrics-recovery.prom')
    def total(rows,name,**tags):return sum(v for n,t,v in rows if n==name and all(t.get(k)==x for k,x in tags.items()))
    def delta(name,**tags):return total(after,name,**tags)-total(before,name,**tags)
    statuses={code:int(delta('http_server_requests_seconds_count',method='POST',uri='/api/tickets/reservations',status=code)) for code in ['202','429','400','401','403','409','500','503']}
    n=sum(statuses.values());buckets=collections.defaultdict(float)
    for sign,rows in [(-1,before),(1,after)]:
        for name,t,value in rows:
            if name=='http_server_requests_seconds_bucket' and t.get('method')=='POST' and t.get('uri')=='/api/tickets/reservations':buckets[float(t['le'])]+=sign*value
    bounds={}
    for q in [.5,.95,.99]:
        prior=0.
        for bound,count in sorted(buckets.items()):
            if count>=q*n:
                bounds[str(q)]={'lower_ms':prior*1000,'upper_ms':bound*1000};break
            prior=bound
    admitted=json.loads((p/'redis-acceptance-events.json').read_text());started=json.loads((p/'worker-sql-start-events.json').read_text());commits=json.loads((p/'binlog-held-commit-events.json').read_text())['events']
    timeline=[]
    for second in range(30):
        lo,hi=t0+second*1000,t0+(second+1)*1000
        timeline.append(dict(interval_start_s=second,redis_admitted=sum(lo<=x['accepted_epoch_ms']<hi for x in admitted),
            mysql_processing_started=sum(lo<=x['sql_processing_epoch_ms']<hi for x in started),
            held_xid_events=sum(x['quantity'] for x in commits if lo<=x['binlog_xid_epoch_ms']<hi),
            cumulative_held_xid=sum(x['quantity'] for x in commits if x['binlog_xid_epoch_ms']<hi),
            stream_length=None,worker_completed=None))
    with (p/'per-second-reconstructed.csv').open('w',newline='',encoding='utf-8-sig') as f:
        w=csv.DictWriter(f,fieldnames=timeline[0].keys());w.writeheader();w.writerows(timeline)
    resources=json.loads((p/'resource-redis-timeline.json').read_text());memory=min(x['memory']['available'] for x in resources)
    summary=dict(valid_full_30s_test=False,actual_load_invocations=1,preload_zero_http_failures=2,
        target_requests=150000,client_actual_sent=None,client_actual_completed=None,client_p99_ms=None,dropped_iterations=None,
        spring_completed_post_delta=n,spring_status_delta=statuses,spring_completed_per_target_30s=n/30,
        spring_histogram_percentile_bounds_ms=bounds,
        server_latency_note='Server completed-request histogram bounds, NOT client percentiles. Tail processing included. Not steady-state QPS.',
        redis_new_admitted=len(admitted),mysql_processing_started=len(started),held_final=len(commits),queue_timeout=112,
        held_at_30s_direct_snapshot=None,held_at_30s_binlog_reconstruction=sum(x['quantity'] for x in commits if x['relative_ms']<30000),
        held_xid_second_range=[min(x['relative_ms'] for x in commits)/1000,max(x['relative_ms'] for x in commits)/1000],
        commit_timestamp_note='XID header has 1-second precision; does not directly measure COMMIT return or fsync completion. No T0+30s SQL snapshot was captured.',
        ticket_5000_held_time=None,remaining_stock=4984,unique_held_buyers=16,
        request_timeout_log_occurrences=sum('level=warning' in x and 'request timeout' in x for x in (p/'k6.stderr.log').read_text().splitlines()),
        minimum_observed_free_memory_mib=round(memory/1024**2,1),
        stop_reason='Prometheus observer HTTP timeout after a successful Redis sample at T0+0.851s. Watchdog aborted k6 around T0+5s. No full client summary survived termination.',
        targets_met=False)
    (p/'RESULT.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))
    report=f'''# V3 单次联合验收：中断，未形成有效30秒结果

- 场次 `{cfg['session']}`，T0 Unix毫秒 `{t0}`，真实Spring直连18083；无Nginx，不报告网关QPS。
- 目标5000/s × 30秒，512固定VU；5000买家第一波不重复，同买家稳定requestId，429后50ms退避。真实鉴权、风险、Armory、库存、MySQL均保留。
- 运行JAR SHA-256：`{cfg['jar_sha256']}`。未修改生产代码、数据库资源或业务保护配置。
- 原始工具启动有两次**0请求**失败（不支持日志参数、错过原T0），已保留原目录。确认库存/任务未使用后，仅为同一场次重新确定T0，实际负载只发起一次，没有追加负载。

## 结果与口径

|项目|本轮实际证据|
|---|---|
|有效完整30秒测试|否，监控超时后约5秒终止发压|
|客户端实际发送/完成、实际rps、P99、dropped iterations|缺失：强制停止未生成k6最终汇总，不能用目标150000代替|
|Spring已完成POST|{n}，202={statuses['202']}，429={statuses['429']}；新增401/403/5xx均0|
|Spring请求开始/接入QPS|未采集；完成计数不能冒充socket接收时间|
|Spring完成数÷30秒|{n/30:.2f}/s，仅固定目标窗口归一化，包含停止后的处理，不代表持续吞吐|
|Redis新受理|{len(admitted)}，已保存每笔Redis时间戳|
|MySQL PROCESSING开始|{len(started)}，与Redis受理区分|
|最终MySQL可靠HELD|16张、16名唯一买家|
|QUEUE_TIMEOUT|112，和463个入场429分开统计|
|T0+30秒SQL直接快照|缺失；不能伪造精确可见性时间|
|30秒HELD重建|binlog记录16笔HELD事务XID位于T0+9～10秒；最终SQL仍16。日志秒级时间不等于提交返回/刷盘完成时间|
|第5000张HELD|没有完成|
|最终库存|MySQL/Redis均4984；无负桶，16笔未释放分配，和16笔HELD相符|
|未完成队列|Stream、PEL、waiting、active、MySQL PROCESSING均0|
|HTTP超时证据|日志有{summary['request_timeout_log_occurrences']}条2秒request timeout；不等同总发送量或服务端失败数|

服务端HTTP直方图差分的分位所在桶（毫秒）：`{json.dumps(bounds,ensure_ascii=False)}`。这不是客户端P50/P95/P99，不能互换。

## 中断根因与性能证据

监控线程最后一次Redis采样在T0+0.851秒成功，此后Prometheus HTTP读取超过4秒超时。工具把该监控超时当作必须停止的故障，在约T0+5秒终止k6；这属于**验收工具保护边界导致的提前中断**，不能据此宣称服务端容量上限，也不能认定Redis服务端超时。该工具没有持续落盘k6逐请求指标，强制停止使客户端完整统计缺失，这是本轮工具的第二项限制。

真实链路同时暴露问题：请求出现大量2秒超时；128笔受理最终只有16笔HELD、112笔5秒排队超时。已受理不代表锁单成功，客户端超时也不代表服务器停止处理。Redis受理发生在T0+1.001～7.214秒，部分发生在客户端停止/超时后。

资源证据：采到的最低可用内存约{summary['minimum_observed_free_memory_mib']}MiB，没有触发384MiB内存保护。Docker原始采样显示MySQL CPU一度约691%（约6.91核），但采样缺少每条时间戳且覆盖准备阶段；不能单凭它判定本轮主因。GC增量、累积SQL摘要和Redis命令统计保留，SQL摘要包含启动/准备历史，不作本轮耗时归因。缺少负载期线程栈/分段计时，尚不能在鉴权、线程调度、数据库提交中确定最大生产瓶颈。

**当前最大验收阻塞是工具提前停止及客户端结果丢失；业务侧已证实的症状是高延迟与排队超时，根因尚未完成隔离。** 不修改生产实现，不自动追加试验。

## 每秒曲线和缺失项

`per-second-reconstructed.csv` 给出30个一秒区间的Redis实际受理、SQL处理开始、HELD XID事件及累计值。HELD XID：T0+9秒有2笔、+10秒有14笔，其后不再增加。Redis受理为服务端Lua时间；SQL开始是持久任务开始时间，不能冒充worker完成。

`resource-redis-timeline.json` 保存中断前实测Stream长度；中断后最终Stream=0。中间秒级队列长度、worker完成曲线**无法恢复，CSV留空，不能填0或推算成实测**。

## 清理和复现

观察JVM、k6、docker stats进程均停止；Spring和两个专属容器保持运行，测试数据和日志保留，没有删除任何目录/卷或操作协议2及其他项目。

入口命令（本轮已经执行，**不要再次执行**）：`powershell -File E:\\credit_risk\\performance\\run-v3-local-k6.ps1 -Run E:\\credit_risk\\docker\\credit_risk_v3_bench\\runs\\54090d45 -Mode joint -Execute`。正常入口检测formal_test_started后拒绝重复正式测试。中途0请求启动修复的具体参数见`preload-recovery.json`。

离线汇总：`python E:\\credit_risk\\performance\\summarize_v3_joint.py "{p}"`，不会访问数据库或发送HTTP。

**两项目标未同时满足；本轮不能提供有效的30秒容量结论。**
'''
    (p/'REPORT.md').write_text(report,encoding='utf-8')
    (p/'artifacts-sha256.json').write_text(json.dumps({x.name:hashlib.sha256(x.read_bytes()).hexdigest() for x in p.iterdir() if x.is_file() and x.name!='artifacts-sha256.json'},indent=2))
    print(json.dumps(summary,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
