"""Same-SKU vs 16-SKU experiment. ONLY disposable credit-risk-ab containers are modified.

32 clients, 160 unique users/request IDs per measured run. Alternating A/B and B/A pairs.
Optional server instrumentation must be enabled with ticketing.perf-log.enabled=true.
"""
import concurrent.futures
import json
import math
import pathlib
import re
import statistics
import time
import urllib.request
from datetime import datetime, timezone
from compare_post_issue_risk import docker, sql, prepare, http

BASE = 'http://127.0.0.1:18082'
CLIENTS = 32
N = 160
SKUS = [f'SKU-{i:03d}' for i in range(1,17)]
OUT = pathlib.Path(__file__).resolve().parent / 'results' / 'sku-ab'

def setup():
    prepare('PASS')
    sql("DELETE FROM tb_ticket_sku WHERE id='ab-sku';")
    values = ','.join(f"('{sku}','ab-session','{sku}',10,1000,1000,'2020-01-01','2030-12-31',100,'ON_SALE',0)" for sku in SKUS)
    sql('INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,sale_start_time,sale_end_time,purchase_limit,status,version) VALUES '+values+';')

def request(i, sku, prefix):
    begin = time.perf_counter()
    request_id = f'sku-ab-{prefix}-{i}'
    status, body = http('/api/tickets/reserve', dict(requestId=request_id,userId=90000+i,
        eventSessionId='ab-session',ticketSkuId=sku,quantity=1,deviceId=f'ab-device-{i}'),f'ab-token-{i}')
    return dict(requestId=request_id,sku=sku,status=status,success=isinstance(body,dict) and body.get('success') is True,
                ms=(time.perf_counter()-begin)*1000,body=body)

def drain(expected):
    end = time.monotonic()+180
    while time.monotonic()<end:
        count = int(sql("SELECT COUNT(*) FROM tb_ticket_order WHERE status='ISSUED';"))
        if count == expected:
            return
        time.sleep(.3)
    raise RuntimeError(f'Could not drain {expected} orders')

def batch(prefix, mode):
    begin = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=CLIENTS) as pool:
        results = list(pool.map(lambda i: request(i,SKUS[0] if mode=='A' else SKUS[(i-1)%16],prefix),range(1,N+1)))
    elapsed = time.perf_counter()-begin
    assert all(r['success'] for r in results), [r for r in results if not r['success']][:5]
    return results, elapsed

def summary(values):
    a=sorted(values)
    return dict(avg=statistics.mean(a),p95=a[math.ceil(len(a)*.95)-1],p99=a[math.ceil(len(a)*.99)-1],max=max(a))

def prometheus():
    with urllib.request.urlopen(BASE+'/actuator/prometheus',timeout=5) as response:
        return response.read().decode()

def pool_values(body):
    values={}
    for line in body.splitlines():
        m=re.match(r'hikaricp_connections_(active|pending|idle|max|timeout_total)\{[^}]*\}\s+(\S+)',line)
        if m: values[m[1]]=float(m[2])
    return values

def lock_status():
    output=sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_time','Innodb_row_lock_waits');")
    return {line.split()[0]:int(line.split()[1]) for line in output.splitlines()}

def warm():
    setup()
    batches=[]
    stable=False
    for n in range(1,9):
        results,elapsed=batch(f'prewarm-{n}','B')
        drain(n*N)
        entry=dict(batch=n,requests=N,qps=N/elapsed,**summary([r['ms'] for r in results]))
        batches.append(entry)
        print('PREWARM '+json.dumps(entry),flush=True)
        if n>=4:
            a,b=batches[-2:]
            stable=abs(b['qps']/a['qps']-1)<=.15 and abs(b['avg']/a['avg']-1)<=.15
            if stable: break
    OUT.joinpath('warmup.json').write_text(json.dumps(dict(stable=stable,batches=batches),indent=2),encoding='utf-8')
    if not stable:
        raise RuntimeError('Warmup has not stabilized; measured A/B was not started')

def run(mode,repeat):
    setup()
    # Initialize all 16 SKU caches identically in both experiments; warm user is excluded.
    for sku in SKUS:
        result=request(0,sku,f'catalog-{mode}-{repeat}-{sku}')
        assert result['success'],result
    drain(16)
    before_locks=lock_status()
    before_pool=pool_values(prometheus())
    wall_begin=time.time()
    results,elapsed=batch(f'{mode}-{repeat}',mode)
    wall_end=time.time()
    drain(N+16)
    after_locks=lock_status()
    after_body=prometheus()
    after_pool=pool_values(after_body)
    logs=docker('logs','credit-risk-ab-platform')
    # Hikari MXBean is sampled by the app every 200 ms without querying the database.
    # Scraping full Prometheus during load would execute the app's six SQL-backed gauges.
    samples=[]
    for line in logs.splitlines():
        match=re.search(r'\[PERF\]\[pool\] active=(\d+) idle=(\d+) waiting=(\d+) total=(\d+)',line)
        if match:
            ts=datetime.strptime(line[:23],'%Y-%m-%d %H:%M:%S.%f').replace(tzinfo=timezone.utc).timestamp()
            if wall_begin<=ts<=wall_end:
                active,idle,pending,total=map(int,match.groups())
                samples.append(dict(seconds=ts-wall_begin,active=active,idle=idle,pending=pending,total=total))
    assert samples,'No in-load pool samples captured'
    ids={r['requestId'] for r in results}
    timings={}
    for line in logs.splitlines():
        if '[PERF][stages] ' in line:
            item=json.loads(line.split('[PERF][stages] ',1)[1])
            if item.get('requestId') in ids: timings[item['requestId']]=item
    assert len(timings)==N,(len(timings),N)
    for item in timings.values():
        assert item['calls'].get('intents.create.commit')==1,item
        assert item['calls'].get('transactions.reserve.commit')==1,item
        assert item['calls'].get('sku.for_update',0)==0,item
    distributions=sql("SELECT sku_id,COUNT(*) FROM tb_ticket_order WHERE user_id>90000 GROUP BY sku_id ORDER BY sku_id;")
    expected_dist={SKUS[0]:N} if mode=='A' else {sku:10 for sku in SKUS}
    actual_dist={line.split()[0]:int(line.split()[1]) for line in distributions.splitlines()}
    assert actual_dist==expected_dist,actual_dist
    rows=sql("SELECT s.id,s.remaining_stock,COUNT(o.id),COUNT(t.id) FROM tb_ticket_sku s LEFT JOIN tb_ticket_order o ON o.sku_id=s.id LEFT JOIN tb_issued_ticket t ON t.order_id=o.id GROUP BY s.id,s.remaining_stock ORDER BY s.id;")
    for line in rows.splitlines():
        sku,remaining,orders,tickets=line.split()
        expected=1+expected_dist.get(sku,0)
        assert [int(remaining),int(orders),int(tickets)]==[1000-expected,expected,expected],line
    assert int(sql("SELECT COUNT(*) FROM tb_outbox_message WHERE message_key LIKE 'RISK_CHECK:%';"))==0
    stage_keys=sorted(set(k for item in timings.values() for k in item['ms']))
    stages={k:summary([item['ms'].get(k,0) for item in timings.values()]) for k in stage_keys}
    pool_stats={k:summary([s[k] for s in samples if k in s]) for k in ('active','pending','idle')}
    payload=dict(mode=mode,repeat=repeat,clients=CLIENTS,requests=N,seconds=elapsed,qps=N/elapsed,
                 latency_ms=summary([r['ms'] for r in results]),stages_ms=stages,
                 pool=pool_stats,pool_before=before_pool,pool_after=after_pool,pool_samples=samples,
                 pool_sampling_interval_ms=200,
                 locks_delta={k:after_locks[k]-before_locks[k] for k in before_locks},
                 distribution=actual_dist,requests_detail=results,request_timings=list(timings.values()))
    OUT.joinpath(f'{mode}-{repeat}.json').write_text(json.dumps(payload,indent=2),encoding='utf-8')
    OUT.joinpath(f'{mode}-{repeat}-prometheus.txt').write_text(after_body,encoding='utf-8')
    OUT.joinpath(f'{mode}-{repeat}-platform.log').write_text(logs,encoding='utf-8')
    print('RESULT '+json.dumps({k:payload[k] for k in ('mode','repeat','qps','latency_ms','pool','locks_delta')}),flush=True)

if __name__=='__main__':
    OUT.mkdir(parents=True,exist_ok=True)
    for _ in range(600):
        try:
            if http('/actuator/health')[0]==200:break
        except Exception:pass
        time.sleep(1)
    else:raise RuntimeError('Instrumented application did not become healthy')
    warm()
    for mode,repeat in [('A',1),('B',1),('B',2),('A',2),('A',3),('B',3)]:
        run(mode,repeat)
