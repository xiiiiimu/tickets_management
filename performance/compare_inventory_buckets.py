"""A/B/C in the same optional-bucket server; only credit-risk-ab fixtures are modified.
A: 1 SKU/1 row. B: 16 SKUs/16 rows. C: 1 SKU/16 real stock buckets.
32 clients /160 requests, PASS, no AI, original fulfillment/transaction scope preserved.
"""
import concurrent.futures
import json
import pathlib
import re
import time
from datetime import datetime,timezone
import compare_sku_contention as common
from compare_post_issue_risk import docker,sql,prepare

OUT=pathlib.Path(__file__).resolve().parent/'results'/'sku-abc'
SKUS=common.SKUS
CLIENTS=32;N=160

def setup(mode,stock=1000):
    sql('DELETE FROM tb_stock_bucket_allocation; DELETE FROM tb_ticket_stock_bucket;')
    prepare('PASS')
    sql("DELETE FROM tb_ticket_sku WHERE id='ab-sku';")
    values=','.join(f"('{sku}','ab-session','{sku}',10,{stock},{stock},'2020-01-01','2030-12-31',100,'ON_SALE',0)" for sku in SKUS)
    fixture='START TRANSACTION; INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,sale_start_time,sale_end_time,purchase_limit,status,version) VALUES '+values+';'
    if mode=='C':
        values=','.join(f"('SKU-001',{i},{stock//16+(i<stock%16)},{stock//16+(i<stock%16)},0)" for i in range(16))
        fixture+='INSERT INTO tb_ticket_stock_bucket VALUES '+values+';'
    sql(fixture+'COMMIT;')

def batch(prefix,mode,allow_reject=False):
    begin=time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=CLIENTS) as pool:
        results=list(pool.map(lambda i:common.request(i,SKUS[(i-1)%16] if mode=='B' else SKUS[0],prefix),range(1,N+1)))
    elapsed=time.perf_counter()-begin
    if not allow_reject:assert all(x['success'] for x in results),[x for x in results if not x['success']][:5]
    return results,elapsed

def warm(mode):
    setup(mode,10000)
    records=[];stable=False
    for n in range(1,9):
        requests,seconds=batch(f'abc-prewarm-{mode}-{n}',mode)
        common.drain(n*N)
        record=dict(batch=n,qps=N/seconds,**common.summary([x['ms'] for x in requests]));records.append(record)
        print('WARM '+mode+' '+json.dumps(record),flush=True)
        if n>=4:
            a,b=records[-2:]
            stable=abs(b['qps']/a['qps']-1)<=.15 and abs(b['avg']/a['avg']-1)<=.15
            if stable:break
    OUT.joinpath(f'warm-{mode}.json').write_text(json.dumps(dict(stable=stable,batches=records),indent=2),encoding='utf-8')
    assert stable,f'{mode} warmup not stable'

def catalog(mode,n):
    for sku in SKUS:
        r=common.request(0,sku,f'abc-catalog-{mode}-{n}-{sku}');assert r['success'],r
    common.drain(16)

def validate_c(stock,measured):
    raw=sql("SELECT b.bucket_id,b.total_stock,b.remaining_stock,COALESCE(SUM(CASE WHEN a.released=0 THEN a.quantity ELSE 0 END),0),"
            "COALESCE(SUM(CASE WHEN a.released=0 AND r.user_id>90000 THEN a.quantity ELSE 0 END),0) "
            "FROM tb_ticket_stock_bucket b LEFT JOIN tb_stock_bucket_allocation a ON a.sku_id=b.sku_id AND a.bucket_id=b.bucket_id "
            "LEFT JOIN tb_reserve_request r ON r.request_id=a.request_id WHERE b.sku_id='SKU-001' GROUP BY b.bucket_id,b.total_stock,b.remaining_stock ORDER BY b.bucket_id;")
    keys=[f'ticket:{{SKU-001}}:stock:bucket:{i}' for i in range(16)]
    redis_values=list(map(int,docker('exec','credit-risk-ab-redis','redis-cli','--raw','MGET',*keys).splitlines()))
    buckets=[]
    for line,redis_stock in zip(raw.splitlines(),redis_values):
        i,total,remaining,allocated,measured_qty=map(int,line.split())
        assert 0<=remaining<=total and remaining+allocated==total,line
        assert remaining==redis_stock,(line,redis_stock)
        buckets.append(dict(bucket=i,total=total,remaining=remaining,redis_remaining=redis_stock,allocated=allocated,measured=measured_qty))
    assert len(buckets)==16
    assert sum(x['total'] for x in buckets)==stock
    assert sum(x['measured'] for x in buckets)==measured
    assert sum(x['remaining'] for x in buckets)==stock-measured-1
    assert int(sql("SELECT COALESCE(SUM(version),0) FROM tb_ticket_stock_bucket WHERE sku_id='SKU-001';"))==measured+1
    assert int(sql("SELECT version FROM tb_ticket_sku WHERE id='SKU-001';"))==0
    # Wait for the normal 30-second background aggregate projection, never add a synchronous hot-row write.
    remaining=sum(x['remaining'] for x in buckets)
    end=time.monotonic()+60
    while time.monotonic()<end:
        parent=int(sql("SELECT remaining_stock FROM tb_ticket_sku WHERE id='SKU-001';"))
        if parent==remaining:break
        time.sleep(.5)
    else:raise RuntimeError(f'Projection did not converge: parent={parent}, buckets={remaining}')
    return dict(total=stock,remaining=remaining,parent_remaining=parent,redis_remaining=sum(redis_values),measured=measured,warm=1,buckets=buckets)

def collect(mode,n,requests,seconds,start,end,before_locks,before_pool):
    after_locks=common.lock_status();after_body=common.prometheus()
    logs=docker('logs','credit-risk-ab-platform');samples=[];timings={};ids={r['requestId'] for r in requests}
    for line in logs.splitlines():
        m=re.search(r'\[PERF\]\[pool\] active=(\d+) idle=(\d+) waiting=(\d+) total=(\d+)',line)
        if m:
            ts=datetime.strptime(line[:23],'%Y-%m-%d %H:%M:%S.%f').replace(tzinfo=timezone.utc).timestamp()
            if start<=ts<=end:
                a,i,p,t=map(int,m.groups());samples.append(dict(seconds=ts-start,active=a,idle=i,pending=p,total=t))
        if '[PERF][stages] ' in line:
            obj=json.loads(line.split('[PERF][stages] ',1)[1])
            if obj.get('requestId') in ids:timings[obj['requestId']]=obj
    assert len(timings)==N and samples
    for obj in timings.values():
        assert obj['calls'].get('intents.create.commit')==1
        assert obj['calls'].get('transactions.reserve.commit')==1
        assert obj['calls'].get('sku.for_update',0)==0
    keys=sorted(set(k for x in timings.values() for k in x['ms']))
    payload=dict(mode=mode,repeat=n,clients=CLIENTS,requests=N,seconds=seconds,qps=N/seconds,
                 latency_ms=common.summary([x['ms'] for x in requests]),
                 stages_ms={k:common.summary([x['ms'].get(k,0) for x in timings.values()]) for k in keys},
                 pool={k:common.summary([x[k] for x in samples]) for k in ('active','pending','idle')},pool_samples=samples,
                 pool_before=before_pool,pool_after=common.pool_values(after_body),
                 locks_delta={k:after_locks[k]-before_locks[k] for k in before_locks},
                 requests_detail=requests,request_timings=list(timings.values()))
    if mode=='C':payload['inventory']=validate_c(1000,N)
    else:
        raw=sql("SELECT s.id,s.remaining_stock,COUNT(o.id),COUNT(t.id) FROM tb_ticket_sku s LEFT JOIN tb_ticket_order o ON o.sku_id=s.id LEFT JOIN tb_issued_ticket t ON t.order_id=o.id GROUP BY s.id,s.remaining_stock ORDER BY s.id;")
        for line in raw.splitlines():
            sku,stock,orders,tickets=line.split();expected=1+(N if mode=='A' and sku==SKUS[0] else 10 if mode=='B' else 0)
            assert [int(stock),int(orders),int(tickets)]==[1000-expected,expected,expected],line
    actual={line.split()[0]:int(line.split()[1]) for line in sql("SELECT sku_id,COUNT(*) FROM tb_ticket_order WHERE user_id>90000 GROUP BY sku_id;").splitlines()}
    assert actual==({s:10 for s in SKUS} if mode=='B' else {SKUS[0]:N}),actual
    assert int(sql("SELECT COUNT(*) FROM tb_issued_ticket;"))==N+16
    OUT.joinpath(f'{mode}-{n}.json').write_text(json.dumps(payload,indent=2),encoding='utf-8')
    OUT.joinpath(f'{mode}-{n}-platform.log').write_text(logs,encoding='utf-8')
    OUT.joinpath(f'{mode}-{n}-prometheus.txt').write_text(after_body,encoding='utf-8')
    print('RESULT '+json.dumps({k:payload[k] for k in ('mode','repeat','qps','latency_ms','pool','locks_delta')}),flush=True)

def run(mode,n):
    setup(mode);catalog(mode,n)
    locks=common.lock_status();pool=common.pool_values(common.prometheus());start=time.time()
    requests,seconds=batch(f'abc-{mode}-{n}',mode);end=time.time()
    common.drain(N+16)
    collect(mode,n,requests,seconds,start,end,locks,pool)

def exhaustion():
    setup('C',16);catalog('C','exhaustion')
    requests,_=batch('abc-exhaustion','C',True)
    successes=sum(x['success'] for x in requests)
    assert successes==15,successes
    assert all(x['status']==409 for x in requests if not x['success'])
    common.drain(16+successes)
    inventory=validate_c(16,successes)
    assert inventory['remaining']==0
    # Replaying a completed request must not deduct another bucket or issue another ticket.
    first=next(x for x in requests if x['success'])
    i=next(i for i in range(1,N+1) if first['requestId']==f'sku-ab-abc-exhaustion-{i}')
    replay=common.request(i,SKUS[0],'abc-exhaustion');assert replay['success']
    assert int(sql("SELECT COUNT(*) FROM tb_issued_ticket;"))==31
    OUT.joinpath('exhaustion.json').write_text(json.dumps(dict(successes=successes,rejected=N-successes,inventory=inventory,replay=replay,requests=requests),indent=2),encoding='utf-8')
    print('EXHAUSTION '+json.dumps(dict(successes=successes,rejected=N-successes,remaining=inventory['remaining'])),flush=True)

if __name__=='__main__':
    OUT.mkdir(parents=True,exist_ok=True)
    for _ in range(600):
        try:
            if common.http('/actuator/health')[0]==200:break
        except Exception:pass
        time.sleep(1)
    else:raise RuntimeError('Application not healthy')
    warm('B');warm('C')
    for mode,n in [('A',1),('B',1),('C',1),('B',2),('C',2),('A',2),('C',3),('A',3),('B',3)]:run(mode,n)
    exhaustion()
