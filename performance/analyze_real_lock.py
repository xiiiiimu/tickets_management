"""Offline lock diagnosis. Inclusive nested spans are never added as disjoint time."""
import collections,json,math,pathlib,sys
root=pathlib.Path(sys.argv[1])
def read(path):return json.loads(path.read_text(encoding='utf-8'))
def pct(values):
    a=sorted(values)
    return {**{f'p{p}':a[math.ceil(len(a)*p/100)-1] for p in (50,95,99)},'mean':sum(a)/len(a),'count':len(a)} if a else {}
result=[]
for n in (1,10,30,50):
    if not (root/f'{n}-result.json').exists():continue
    r=read(root/f'{n}-result.json');raw=read(root/f'{n}-raw.json');groups=collections.defaultdict(list);front=[];durable=[];handoff=[];hikari=[];commit=[]
    for t in raw['traces']:
        front.append((t['consumerEnd']-t['consumerStart'])/1e6);handoff.append((t['saleStart']-t['claimEnd'])/1e6)
        decision=[s for s in t['spans'] if s['operation']=='ReservationDispatch.decided']
        if decision:durable.append((t['saleStart']-decision[-1]['end'])/1e6)
        for s in t['spans']:
            ms=(s['end']-s['start'])/1e6;groups[(s['operation'],s.get('parent',''))].append(ms)
            if s['operation'].endswith('HikariDataSource.getConnection'):hikari.append(ms)
            if s['operation'].endswith('DataSourceTransactionManager.doCommit'):commit.append(ms)
    phases=[dict(operation=k[0],parent=k[1],ms=pct(v),sum_thread_ms=sum(v)) for k,v in groups.items()]
    snapshots=raw['observations'];gauges={k:max(float(x['gauges'].get(k,0)) for x in snapshots) for k in snapshots[0]['gauges']}
    before={x[0]:x for x in raw['before']['gc']};gc={x[0]:dict(count=x[1]-before[x[0]][1],ms=x[2]-before[x[0]][2]) for x in raw['after']['gc']}
    resources=raw['resources'];a=resources[0]['host'];b=resources[-1]['host'];host_cpu=100*(1-(b['host_idle_100ns']-a['host_idle_100ns'])/max(1,b['host_cpu_100ns']-a['host_cpu_100ns']))
    sql_queue_wait=[x['claimed_ms']-x['queued_ms'] for x in r['inventory']['rows'] if x['claimed_ms'] and x['queued_ms']]
    row=dict(n=n,concurrency=r['concurrency'],http_qps=r['http_qps'],lock_tps=r['lock_tps'],batch_seconds=r['batch_seconds'],success=r['success'],failed=r['failed'],pending=r['pending'],http_status=r['status'],T1=r['timings']['http_server'],T2=r['timings']['queue'],T3=r['timings']['backend'],end_to_end=r['timings']['end_to_end'],admission_worker_ms=pct(front),durable_decision_to_sale_ms=pct(durable),durable_queued_to_claimed_db_ms=pct(sql_queue_wait),claim_return_to_sale_ms=pct(handoff),hikari_acquisition_ms=pct(hikari),commit_ms=pct(commit),phases=phases,
             sampled_ingress_queue_max=max(x['ingressQueue'] for x in snapshots),sampled_ingress_active_max=max(x['ingressActive'] for x in snapshots),sampled_hikari_active_max=max(x['hikari']['active'] for x in snapshots),sampled_hikari_waiting_max=max(x['hikari']['waiting'] for x in snapshots),gauges=gauges,gc=gc,java_cpu_cores=r['java_cpu_cores'],host_cpu_percent=host_cpu,java_rss_max=max(x['memory'].get('rss',0) for x in resources),host_available_min=min(x['host']['host_memory_available'] for x in resources),forbidden_calls=raw['after']['forbiddenCalls'],transformed=raw['after']['transformed'])
    result.append(row)
waits=read(root/'mysql-lock-wait-samples.json');tables=collections.Counter();lock_details=[]
for sample in waits:
    for item in sample.get('waits',[]):
        value=json.loads(item);tables[str(value.get('table'))+':'+str(value.get('index'))]+=1;lock_details.append(dict(epoch=sample['epoch'],**value))
output=dict(cohorts=result,lock_wait_sampling=dict(total_samples=len(waits),nonempty_samples=sum(bool(s.get('waits')) for s in waits),by_table_index=tables,details=lock_details,errors=[x for x in waits if 'error' in x]),scope='Sampling maxima are lower bounds; SQL wall time is not pure row-lock wait; nested spans overlap')
(root/'analysis.json').write_text(json.dumps(output,ensure_ascii=False,indent=2),encoding='utf-8')
for r in result:
    print(json.dumps({k:r[k] for k in ('n','http_qps','lock_tps','batch_seconds','T1','T2','T3','end_to_end','admission_worker_ms','durable_decision_to_sale_ms','hikari_acquisition_ms','sampled_ingress_queue_max','sampled_ingress_active_max','sampled_hikari_active_max','sampled_hikari_waiting_max','gauges','gc','java_cpu_cores')},ensure_ascii=False))
print('lock samples',len(waits),'tables',dict(tables))
