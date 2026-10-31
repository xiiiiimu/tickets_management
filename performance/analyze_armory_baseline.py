"""Read-only analysis of raw armory_baseline.py artifacts."""
import json
import pathlib
import re
import statistics
import sys
import math
from datetime import datetime, timezone

def summary(values):
    a=sorted(values)
    return {**{f'p{p}':a[math.ceil(len(a)*p/100)-1] for p in (50,95,99)},'avg':statistics.mean(a),'max':max(a),'count':len(a)} if a else {}

def metrics(path):
    values={}
    for line in path.read_text(encoding='utf-8').splitlines():
        if line.startswith('#'): continue
        m=re.match(r'(\S+)\s+(\S+)',line)
        if m:
            try: values[m[1]]=float(m[2])
            except ValueError: pass
    return values

def analyze(root):
    rounds={};all_timings={}
    for file in sorted(root.glob('*-diagnostics.json')):
        label=file.name.removesuffix('-diagnostics.json')
        diag=json.loads(file.read_text(encoding='utf-8'))
        result=json.loads((root/(label+'-requests.json')).read_text(encoding='utf-8'))
        ids={r['requestId'] for r in result['requests']}
        timings=[];pool=[]
        for line in (root/(label+'.stdout.log')).read_text(encoding='utf-8',errors='replace').splitlines():
            if '[PERF][stages] ' in line:
                try:
                    obj=json.loads(line.split('[PERF][stages] ',1)[1])
                    if obj.get('requestId') in ids: timings.append(obj)
                except ValueError: pass
            if '[PERF][pool]' in line:
                try: ts=datetime.strptime(line[:23],'%Y-%m-%d %H:%M:%S.%f').replace(tzinfo=timezone.utc).timestamp()
                except ValueError: continue
                m=re.search(r'active=(\d+) idle=(\d+) waiting=(\d+) total=(\d+)',line)
                if m and result['start']<=ts<=result['end']:
                    a,i,w,t=map(int,m.groups());pool.append(dict(t=ts,active=a,pending=w,total=t))
        before=metrics(root/(label+'-before.prom'));after=metrics(root/(label+'-after.prom'))
        assert len(timings)==160,(label,'missing timings',len(timings))
        all_timings[label]=timings
        delta={k:v-before.get(k,0) for k,v in after.items() if k.endswith(('_count','_sum','_total')) or any(t in k for t in ('_count{','_sum{','_total{','_bucket{'))}
        operations={}
        for k,v in delta.items():
            if k.startswith('ticketing_operation_duration_seconds_count{'):
                op=re.search(r'operation="([^"]+)"',k)[1];out=re.search(r'outcome="([^"]+)"',k)[1]
                total=delta.get(k.replace('_count{','_sum{'),0)
                operations[op+'_'+out]=dict(count=v,total_ms=1000*total,avg_ms=1000*total/v if v else 0)
                buckets=[]
                for key,n in delta.items():
                    if key.startswith('ticketing_operation_duration_seconds_bucket{') and f'operation="{op}"' in key and f'outcome="{out}"' in key:
                        upper=float(re.search(r'le="([^"]+)"',key)[1]);buckets.append((upper,n))
                previous=0
                for upper,n in sorted(buckets):
                    if v and n>=.95*v:
                        operations[op+'_'+out]['p95_bucket_ms']=[previous*1000,upper*1000];break
                    previous=upper
        acquire={k:v for k,v in delta.items() if k.startswith('hikaricp_connections_acquire_seconds_')}
        usage={k:v for k,v in delta.items() if k.startswith('hikaricp_connections_usage_seconds_')}
        def sql_delta(end,start):
            # MySQL can expose multiple digest rows (including zero-count entries).
            # Aggregate before differencing; a dict overwrite would lose nonzero baselines.
            def aggregate(source):
                target={}
                for item in source:
                    key=item['digest']
                    if key not in target:target[key]=dict(digest=key,sql=item['sql'],count=0,ms=0,lock_ms=0,rows=0)
                    for k in ('count','ms','lock_ms','rows'):target[key][k]+=float(item[k])
                return target
            old=aggregate(start);rows=[]
            for row in aggregate(end).values():
                prev=old.get(row['digest'],{});r={k:row[k] for k in ('digest','sql')}
                for k in ('count','ms','lock_ms','rows'):r[k]=float(row[k])-float(prev.get(k,0))
                if r['count']>0:
                    r['avg_ms']=r['ms']/r['count'];rows.append(r)
            return sorted(rows,key=lambda r:r['ms'],reverse=True)
        stages={k:summary([t['ms'].get(k,0) for t in timings]) for k in sorted({k for t in timings for k in t['ms']})}
        rounds[label]=dict(qps=result['qps'],latency_ms=result['latency_ms'],successes=result['successes'],errors=result['errors'],
            timings_count=len(timings),stages=stages,operations=operations,acquire=acquire,connection_usage=usage,
            counters={k:v for k,v in delta.items() if k.startswith(('risk_','ticket_reserve_','payment_','mq_'))},
            pool={k:summary([p[k] for p in pool]) for k in ('active','pending')},
            pool_max_sample_gap=max([b['t']-a['t'] for a,b in zip(pool,pool[1:])],default=0),
            locks_sync={k:diag['locks_after_sync'][k]-diag['locks_before'][k] for k in diag['locks_before']},
            sql_sync=sql_delta(diag['sql_after_sync'],diag['sql_before']),sql_tail=sql_delta(diag['sql_after_drain'],diag['sql_after_sync']),
            warm_qps=diag['warm_qps'],orders_after_sync=diag['observed_orders_after_sync'],final_orders=diag['final_orders'])
    groups={}
    for mode in 'AB':
        labels=[mode+str(i) for i in range(1,4)]
        if not all(k in rounds for k in labels):continue
        requests=[r for k in labels for r in json.loads((root/(k+'-requests.json')).read_text(encoding='utf-8'))['requests']]
        groups[mode]=dict(qps_median=statistics.median(rounds[k]['qps'] for k in labels),
            latency_ms=summary([r['ms'] for r in requests]),successes=sum(r['success'] for r in requests))
        timing_rows=[t for k in labels for t in all_timings[k]]
        groups[mode]['stages']={k:summary([t['ms'].get(k,0) for t in timing_rows]) for k in sorted({k for t in timing_rows for k in t['ms']})}
        groups[mode]['operations']={}
        for op in rounds[labels[0]]['operations']:
            n=sum(rounds[k]['operations'][op]['count'] for k in labels)
            total=sum(rounds[k]['operations'][op]['total_ms'] for k in labels)
            groups[mode]['operations'][op]=dict(count=n,total_ms=total,avg_ms=total/n if n else 0)
    out=dict(groups=groups,rounds=rounds)
    (root/'analysis.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(dict(groups={k:{n:v[n] for n in ('qps_median','latency_ms','successes')} for k,v in groups.items()},rounds={k:{n:v[n] for n in ('qps','successes','timings_count','warm_qps')} for k,v in rounds.items()}),indent=2))

if __name__=='__main__':analyze(pathlib.Path(sys.argv[1]))
