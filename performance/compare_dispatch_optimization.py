"""Offline comparison; identify coordinator TX by containing its SQL, not by pooling both create TXs."""
import json,math,pathlib,sys

def read(p):return json.loads(p.read_text(encoding='utf-8'))
def pct(v):
    v=sorted(v)
    return {f'p{p}':v[math.ceil(len(v)*p/100)-1] for p in (50,95,99)} if v else {}
def inspect(root):
    result=[]
    for n in (1,10,30,50):
        if not (root/f'{n}-result.json').exists():continue
        raw=read(root/f'{n}-raw.json');r=read(root/f'{n}-result.json');groups={k:[] for k in ['create_method','create_coordinator_tx','create_guard_tx','claim_tx','create_lock_sql','claim_lock_sql','durable_queue']}
        for trace in raw['traces']:
            spans=trace['spans']
            for s in spans:
                op=s['operation'];parent=s.get('parent');ms=(s['end']-s['start'])/1e6
                if op=='ReservationDispatch.create':groups['create_method'].append(ms)
                if op=='sql SELECT * FROM tb_reservation_dispatch WHERE id=1 FOR UPDATE':
                    groups['create_lock_sql' if parent=='ReservationDispatch.create' else 'claim_lock_sql'].append(ms)
                if op.endswith('TransactionTemplate.execute') and parent=='ReservationDispatch.create':
                    coord=any(x['operation']=='sql SELECT * FROM tb_reservation_dispatch WHERE id=1 FOR UPDATE' and s['start']<=x['start']<=x['end']<=s['end'] for x in spans)
                    groups['create_coordinator_tx' if coord else 'create_guard_tx'].append(ms)
                if op.endswith('TransactionTemplate.execute') and parent=='ReservationDispatch.claim':groups['claim_tx'].append(ms)
        for row in r['inventory']['rows']:
            if row['claimed_ms'] is not None and row['queued_ms'] is not None:groups['durable_queue'].append(row['claimed_ms']-row['queued_ms'])
        result.append(dict(n=n,concurrency=r['concurrency'],http_qps=r['http_qps'],lock_tps=r['lock_tps'],batch_seconds=r['batch_seconds'],ingress_queue=r['timings']['queue'],end_to_end=r['timings']['end_to_end'],success=r['success'],failed=r['failed'],pending=r['pending'],status=r['status'],phases={k:pct(v) for k,v in groups.items()}))
    samples=read(root/'mysql-lock-wait-samples.json');tables={};nonempty=0
    for sample in samples:
        rows=sample.get('waits',[]);nonempty+=bool(rows)
        for row in rows:
            value=json.loads(row);key=value['table']+':'+str(value['index']);tables[key]=tables.get(key,0)+1
    return dict(root=str(root),cohorts=result,lock_samples=len(samples),nonempty_samples=nonempty,wait_records=tables,observer_errors=[x for x in samples if 'error' in x])

if __name__=='__main__':
    before,after=map(pathlib.Path,sys.argv[1:3]);result=dict(baseline=inspect(before),candidate=inspect(after))
    out=pathlib.Path(__file__).parent/'results/dispatch-optimization/comparison.json';out.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');print(out)
