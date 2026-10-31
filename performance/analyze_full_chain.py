"""Offline only: exclusive time partitions and evidence from the one 50-request experiment."""
import collections,csv,json,math,pathlib,sys

root=pathlib.Path(sys.argv[1]);perf=pathlib.Path(__file__).parent
def read(name):return json.loads((root/name).read_text(encoding='utf-8'))
def save(name,value):(root/name).write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
def pct(v):
    v=sorted(v)
    return dict(count=len(v),mean=sum(v)/len(v),**{f'p{p}':v[math.ceil(len(v)*p/100)-1] for p in (50,95,99)},max=v[-1]) if v else {}
def overlap(a,b,c,d):return max(0,min(b,d)-max(a,c))
def matching(t,op):return [s for s in t['spans'] if s['operation']==op]
def duration(s):return (s['end']-s['start'])/1e6
raw=read('50-raw.json');r=read('50-result.json');final=read('final-probe.json');traces=raw['traces'];scheduled=final['scheduled'];claims=final['claimAttempts'];samples=final['threadSamples']
start=min(t['enqueue'] for t in traces);end=max(t['locked'] for t in traces);seconds=(end-start)/1e9
def event_end(t,op):
    v=matching(t,op);return v[-1]['end'] if v else 0
rows=[];phase=collections.defaultdict(list);events=collections.defaultdict(list);sqls=collections.defaultdict(list);sql_counts=collections.Counter();redis=collections.defaultdict(list)
for t in traces:
    commits=[s for s in t['spans'] if s['operation'].endswith('DataSourceTransactionManager.doCommit') and s.get('parent')=='ReservationDispatch.decided']
    ready=commits[-1]['end'] if commits else event_end(t,'ReservationDispatch.decided')
    evaluating=[s for s in t['spans'] if s['operation'].endswith('DataSourceTransactionManager.doCommit') and s.get('parent')=='ReservationDispatch.create']
    evaluation_commit=evaluating[-1]['end'] if evaluating else 0
    risk=event_end(t,'RiskDecisionService.classifyReservation');submit=t.get('saleSubmit',t['saleStart']);sale_end=t.get('saleEnd',t['locked']) or t['locked']
    row=dict(requestId=t['requestId'],traceId=t['requestId'],ingress_thread=t['ingressThread'],sale_thread=t['saleThread'],http_ms=(t['httpEnd']-t['httpStart'])/1e6,memory_wait_ms=(t['consumerStart']-t['enqueue'])/1e6,ingress_work_ms=(t['consumerEnd']-t['consumerStart'])/1e6,queued_ready_ns=ready,create_commit_ns=evaluation_commit,risk_end_ns=risk,durable_pre_claim_ms=max(0,t['claimStart']-ready)/1e6,claim_ms=(t['claimEnd']-t['claimStart'])/1e6,sale_handoff_ms=max(0,submit-t['claimEnd'])/1e6,sale_pool_wait_ms=max(0,t['saleStart']-submit)/1e6,sale_work_ms=(sale_end-t['saleStart'])/1e6,sale_to_lock_ms=(t['locked']-t['saleStart'])/1e6,end_to_end_ms=(t['locked']-t['enqueue'])/1e6,durable_to_sale_ms=(t['saleStart']-ready)/1e6,**{k:t[k] for k in ('enqueue','consumerStart','consumerEnd','claimStart','claimEnd','saleStart','redisHeld','orderInserted','locked')},saleEnd=sale_end)
    # A claim may begin before this job's QUEUED commit acknowledgement.
    row['claim_after_ready_ms']=max(0,t['claimEnd']-max(ready,t['claimStart']))/1e6
    row['claim_before_ready_ms']=max(0,ready-t['claimStart'])/1e6
    def selected(op,outer=None):
        found=matching(t,op)
        if outer:
            containers=matching(t,outer)
            found=[s for s in found if any(c['start']<=s['start']<=s['end']<=c['end'] for c in containers)]
        return found[-1] if found else None
    for key,op,outer in [
        ('auth','com.ticketing.common.util.RefreshTokenInterceptor.preHandle',None),
        ('auth_hgetall','org.springframework.data.redis.core.DefaultHashOperations.entries','com.ticketing.common.util.RefreshTokenInterceptor.preHandle'),
        ('auth_expire','org.springframework.data.redis.core.RedisTemplate.expire','com.ticketing.common.util.RefreshTokenInterceptor.preHandle'),
        ('rate_bucket_body','com.ticketing.ticket.ReservationIngress$Bucket.take',None),
        ('controller','com.ticketing.ticket.ReservationV2Controller.create',None),
        ('armory_admission','TicketSaleArmory.runtimePlan',None),
        ('armory_intent','TicketSaleArmory.runtimeSku','ReservationV2Transactions.prepare'),
        ('armory_final','TicketSaleArmory.runtimeSku','ReservationV2Transactions.reserve'),
        ('create','ReservationDispatch.create',None),('features','RiskFeatureService.collect',None),
        ('rules','RiskRuleEngine.evaluate',None),('intent','ReservationV2Transactions.prepare',None),
        ('reserve','ReservationV2Transactions.reserve',None),('stock_lua','redis.script.reserve',None)]:
        span=selected(op,outer);row[key+'_ms']=duration(span) if span else None
        row[key+'_start_ns']=span['start'] if span else None;row[key+'_end_ns']=span['end'] if span else None
    commit=next((s for s in t['spans'] if s['operation'].endswith('DataSourceTransactionManager.doCommit') and s.get('parent')=='ReservationV2Transactions.reserve'),None)
    if commit:row.update(final_commit_start_ns=commit['start'],final_commit_end_ns=commit['end'],final_commit_ms=duration(commit))
    partition=collections.defaultdict(int)
    # These are observed scheduler occupations while this job waited, not causally unique lock time.
    for task in scheduled:
        ns=overlap(ready,t['claimStart'],task['start'],task['end'])
        if ns:partition[task['operation']]+=ns
    observed=sum(partition.values());row['scheduler_overlap_ms']={k:v/1e6 for k,v in partition.items()};row['unassigned_pre_claim_ms']=max(0,t['claimStart']-ready-observed)/1e6
    for k,v in row.items():
        if k.endswith('_ms') and isinstance(v,(int,float)):phase[k].append(v)
    for s in t['spans']:
        if s['start']>t['locked']:continue
        op=s['operation'];phase[op].append(duration(s))
        if op.startswith('sql '):
            key=(s.get('parent',''),op[4:]);sqls[key].append(duration(s));sql_counts[key]+=1
        if op.startswith('org.springframework.data.redis') or op.startswith('redis.script.'):redis[op].append(duration(s))
    for name,at in dict(http_202=t['httpEnd'],accepted=t['enqueue'],memory_dequeued=t['consumerStart'],evaluating_committed=evaluation_commit,risk_completed=risk,queued=ready,claim_succeeded=t['claimEnd'],sale_started=t['saleStart'],redis_held=t['redisHeld'],order_committed=t['locked']).items():
        if at:events[name].append(at)
    rows.append(row)
save('request-timeline.json',rows)
with (root/'request-timeline.csv').open('w',newline='',encoding='utf-8-sig') as f:
    simple=[k for k in rows[0] if k!='scheduler_overlap_ms'];writer=csv.DictWriter(f,simple,extrasaction='ignore');writer.writeheader();writer.writerows(rows)

tasks=[]
for t in traces:
    tasks.extend([dict(thread=t['ingressThread'],kind='ingress_work',requestId=t['requestId'],start=t['consumerStart'],end=t['consumerEnd']),dict(thread=t['saleThread'],kind='sale_work',requestId=t['requestId'],start=t['saleStart'],end=t.get('saleEnd',t['locked']) or t['locked'])])
tasks.extend(dict(thread=x['thread'],kind=x['operation'],requestId='',start=x['start'],end=x['end']) for x in scheduled)
save('thread-task-timeline.json',tasks)
busy=collections.defaultdict(int)
for x in tasks:busy[(x['thread'],x['kind'])]+=overlap(start,end,x['start'],x['end'])
thread_busy=[dict(thread=k[0],kind=k[1],busy_ms=v/1e6,window_utilization=v/(end-start)) for k,v in sorted(busy.items())]
thread_states=collections.defaultdict(collections.Counter);stack_counts=collections.defaultdict(collections.Counter)
for sample in samples:
    if not start<=sample.get('nano',0)<=end:continue
    for t in sample.get('threads',[]):
        thread_states[t['name']][t['state']]+=1;stack_counts[t['name']][t['stack'][0] if t['stack'] else 'empty']+=1

# Trace-derived intervals: pending jobs here means Java has not yet observed successful claim return.
points=sorted(set([start,end]+[v for x in rows for v in (x['queued_ready_ns'],x['claimEnd'],x['saleStart'],x['saleEnd']) if start<=v<=end]));underfed=0;spare_worker_ns=0;underfed_intervals=[]
for left,right in zip(points,points[1:]):
    middle=(left+right)//2;pending=sum(x['queued_ready_ns']<=middle<x['claimEnd'] for x in rows);active=sum(x['saleStart']<=middle<x['saleEnd'] for x in rows)
    if pending and active<4:
        underfed+=right-left;spare_worker_ns+=(4-active)*(right-left);underfed_intervals.append(dict(start=left,end=right,pending=pending,sale_active=active))
save('pending-with-spare-sale.json',underfed_intervals)

funnel=[]
for second in range(math.ceil(seconds)):
    left=start+second*10**9;right=min(end,start+(second+1)*10**9)
    counts={k:sum(left<=v<right or (second==math.ceil(seconds)-1 and v==right) for v in values) for k,values in events.items()}
    cumulative={k:sum(v<=right for v in values) for k,values in events.items()}
    counts.update(second=second,memory_pending=cumulative['accepted']-cumulative['memory_dequeued'],entry_processing=cumulative['memory_dequeued']-cumulative['queued'],durable_pending=cumulative['queued']-cumulative['claim_succeeded'],claimed_not_committed=cumulative['claim_succeeded']-cumulative['order_committed'],accepted_not_committed=cumulative['accepted']-cumulative['order_committed']);funnel.append(counts)
save('throughput-funnel.json',funnel)

# Observer records are mixed lock/active-connection rows. Keep sample records separate from episodes.
anchor=raw['after'];wall=lambda nano:anchor['wallMillis']/1000+(nano-anchor['nano'])/1e9
waits=read('mysql-lock-wait-samples.json');episodes={};lock_tables=collections.Counter();positive=0;sample_count=0
for sample in waits:
    if not wall(start)-.1<=sample.get('epoch',0)<=wall(end)+.1:continue
    sample_count+=1;lock_rows=[]
    for encoded in sample.get('waits',[]):
        row=json.loads(encoded)
        if row.get('kind')!='lock':continue
        lock_rows.append(row);lock_tables[(row['table'],row['index'])]+=1
        key=(str(row['requesting']),str(row['blocking']),row['lock_id']);episode=episodes.setdefault(key,dict(first=sample['epoch'],last=sample['epoch'],samples=0,**row));episode['last']=sample['epoch'];episode['samples']+=1
    positive+=bool(lock_rows)
for episode in episodes.values():
    episode['observed_span_ms']=(episode['last']-episode['first'])*1000
    conn=episode.get('requesting_connection');episode['request_sql_matches']=[]
    for t in traces:
        for s in t['spans']:
            if s.get('connectionId')==conn and s['operation'].startswith('sql ') and wall(s['start'])<=episode['last']+.6 and wall(s['end'])>=episode['first']-.6:
                episode['request_sql_matches'].append(dict(requestId=t['requestId'],operation=s['operation'],parent=s.get('parent'),ms=duration(s)))
save('mysql-lock-episodes.json',list(episodes.values()))

scheduled_metrics=[]
for op in sorted(set(x['operation'] for x in scheduled)):
    group=[x for x in scheduled if x['operation']==op and overlap(start,end,x['start'],x['end'])]
    spans=[s for x in group for s in x.get('spans',[]) if start<=s['start']<=end]
    scheduled_metrics.append(dict(operation=op,count=len(group),overlap_ms=sum(overlap(start,end,x['start'],x['end']) for x in group)/1e6,durations_ms=pct([duration(x) for x in group]),sql_calls=sum(s['operation'].startswith('sql ') for s in spans),summary_rebuilds=sum(s['operation'].endswith('RiskHistorySummary.rebuild') for s in spans)))
claim_in=[x for x in claims if overlap(start,end,x['start'],x['end'])];null=[]
for attempt in claim_in:
    if attempt.get('requestId'):continue
    counts=[s.get('value') for s in attempt.get('spans',[]) if s['operation'].startswith("sql SELECT COUNT(*) FROM tb_reservation_job WHERE state='CLAIMED'")]
    null.append('active_limit' if counts and counts[-1]>=4 else 'no_eligible_or_skipped')
sql_summary=[dict(parent=k[0],sql=k[1],ms=pct(v),sum_thread_ms=sum(v)) for k,v in sqls.items()]
sql_summary.sort(key=lambda x:x['sum_thread_ms'],reverse=True)
save('sql-summary.json',sql_summary)
summary=dict(window_seconds=seconds,counts={k:len(v) for k,v in events.items()},phases={k:pct(v) for k,v in phase.items()},scheduled=scheduled_metrics,claim_attempts=len(claim_in),claim_success=sum(bool(x.get('requestId')) for x in claim_in),null_claim_reasons=dict(collections.Counter(null)),claim_service_ms=pct([duration(x) for x in claim_in]),thread_busy=thread_busy,thread_states={k:dict(v) for k,v in thread_states.items()},thread_top_stacks={k:v.most_common(4) for k,v in stack_counts.items()},pending_with_spare_sale_seconds=underfed/1e9,spare_worker_seconds_with_pending=spare_worker_ns/1e9,redis={k:pct(v) for k,v in redis.items()},lock_sampling=dict(samples=sample_count,positive=positive,records={str(k):v for k,v in lock_tables.items()},sampled_episodes=len(episodes),observed_episode_ms=pct([v['observed_span_ms'] for v in episodes.values()])),missing='Precise InnoDB lock acquisition/release, uninstrumented control, network/driver/server breakdown, steady-state capacity, REVIEW fairness delay')
summary['missing']='Precise InnoDB wait/acquisition/release and per-request lock correlation (connection ID getter failed); container load stats empty; waits history disabled; no uninstrumented control or steady-state capacity'
save('full-chain-analysis.json',summary)

resources=raw['resources'];h0=resources[0]['host'];h1=resources[-1]['host'];gc0={x[0]:x for x in raw['before']['gc']};in_window=[x for x in samples if start<=x.get('nano',0)<=end]
resource=dict(host_cpu=100*(1-(h1['host_idle_100ns']-h0['host_idle_100ns'])/(h1['host_cpu_100ns']-h0['host_cpu_100ns'])),java_cpu_cores=r['java_cpu_cores'],host_free_min_mib=min(x['host']['host_memory_available'] for x in resources)/2**20,java_rss_max_mib=max(x['memory']['rss'] for x in resources)/2**20,gc={x[0]:dict(count=x[1]-gc0[x[0]][1],ms=x[2]-gc0[x[0]][2]) for x in raw['after']['gc']},hikari_active_max=max(x.get('hikariActive',0) for x in in_window),hikari_waiting_max=max(x.get('hikariWaiting',0) for x in in_window),ingress_queue_max=max(x.get('ingressQueue',0) for x in in_window),sale_active_max=max(x.get('saleActive',0) for x in in_window),container_stats_available=(root/'container-stats.jsonl').stat().st_size>0,connection_id_misses=raw['after']['connectionIdMisses'])
save('resource-summary.json',resource)
before=read('mysql-before.json');after=read('mysql-after.json');old={x['digest']:x for x in before.get('digest',[]) if isinstance(x,dict)};delta=[]
for x in after.get('digest',[]):
    previous=old.get(x['digest'],{});count=x['count']-previous.get('count',0);ns=x['sum_ps']-previous.get('sum_ps',0)
    if count>0:delta.append(dict(sql=x['sql'],count=count,total_ms=ns/1e9,avg_ms=ns/count/1e9,rows=x['rows_examined']-previous.get('rows_examined',0)))
save('mysql-digest-delta.json',sorted(delta,key=lambda x:x['total_ms'],reverse=True))
old_files={x['event']:x for x in before.get('file_io',[])};file_delta=[]
for x in after.get('file_io',[]):
    previous=old_files.get(x['event'],{});change={k:x[k]-previous.get(k,0) for k in ['count','sum_ps','read','write','misc','bytes_read','bytes_write']}
    if change['count']:file_delta.append(dict(event=x['event'],**change,total_ms=change['sum_ps']/1e9))
connection_samples=[]
for sample in waits:
    connections=[json.loads(value) for value in sample.get('waits',[]) if json.loads(value).get('kind')=='connection']
    connection_samples.append(dict(epoch=sample.get('epoch'),connections=len(connections),executing=sum(bool(x.get('info')) or bool(x.get('state')) for x in connections)))
save('mysql-io-and-connections.json',dict(file_delta=file_delta,connection_samples=connection_samples,scope='Shared-server IO and metadata bracket wider than batch; owned-schema connection snapshots; not precision IO utilization'))
print(json.dumps({k:summary[k] for k in ['window_seconds','counts','scheduled','claim_attempts','claim_success','null_claim_reasons','pending_with_spare_sale_seconds','lock_sampling']},ensure_ascii=False,indent=2))
