"""Offline only: existing task timelines, no HTTP/JDBC/container operations."""
import collections,json,math,pathlib,statistics,sys
root=pathlib.Path(sys.argv[1]).resolve();out=root/'ingress-occupancy';out.mkdir(exist_ok=True)
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
def stats(v):
 v=sorted(v);return dict(n=len(v),mean=statistics.mean(v),p50=v[math.ceil(.5*len(v))-1],p95=v[math.ceil(.95*len(v))-1],p99=v[math.ceil(.99*len(v))-1],max=v[-1]) if v else None
def merged(intervals):
 result=[]
 for a,b in sorted(intervals):
  if b<=a:continue
  if result and a<=result[-1][1]:result[-1]=(result[-1][0],max(b,result[-1][1]))
  else:result.append((a,b))
 return result
def millis(intervals):return sum(b-a for a,b in merged(intervals))/1e6
all_rounds=[]
for r in read(root/'results.json'):
 folder=root/f"{r['round']}-{r['mode']}";raw=read(folder/'50-raw.json');traces=raw['traces'];per=[];sqls=collections.defaultdict(list);by_thread=collections.defaultdict(list);connection_calls=[]
 for t in traces:
  start,end=t['consumerStart'],t['consumerEnd'];spans=[s for s in t['spans'] if s['thread']==t['ingressThread'] and s['start']<end and s['end']>start]
  intervals=lambda test:[(max(start,s['start']),min(end,s['end'])) for s in spans if test(s)]
  isredis=lambda s:s['operation'].startswith('redis.script.') or s['operation'].startswith('org.springframework.data.redis.core.Default') or s['operation'].startswith('org.springframework.data.redis.core.RedisTemplate.')
  mysql=lambda s:s['operation'].startswith('sql ') or s['operation'].endswith(('DataSourceTransactionManager.doBegin','DataSourceTransactionManager.doCommit','DataSourceTransactionManager.doRollback','HikariDataSource.getConnection'))
  metrics=dict(requestId=t['requestId'],thread=t['ingressThread'],task_ms=(end-start)/1e6,memory_wait_ms=(start-t['enqueue'])/1e6)
  tests={'feature_collect_ms':lambda s:s['operation']=='RiskFeatureService.collect','risk_all_ms':lambda s:s['operation']=='RiskDecisionService.classifyReservation','rule_engine_ms':lambda s:s['operation']=='RiskRuleEngine.evaluate','profile_sql_ms':lambda s:s['operation'].startswith('sql ') and 'tb_risk_profile' in s['operation'],'redis_ms':isredis,'mysql_ms':mysql,'create_ms':lambda s:s['operation']=='ReservationDispatch.create','decided_ms':lambda s:s['operation']=='ReservationDispatch.decided','armory_ms':lambda s:s['operation']=='TicketSaleArmory.runtimePlan','summary_read_ms':lambda s:s['operation'].endswith('RiskHistorySummary.read'),'connection_ms':lambda s:s['operation'].endswith('HikariDataSource.getConnection')}
  for key,test in tests.items():metrics[key]=millis(intervals(test))
  metrics['feature_redis_ms']=millis([(max(a,s['start']),min(b,s['end'])) for a,b in intervals(tests['feature_collect_ms']) for s in spans if isredis(s)])
  metrics['feature_mysql_ms']=millis([(max(a,s['start']),min(b,s['end'])) for a,b in intervals(tests['feature_collect_ms']) for s in spans if mysql(s)])
  metrics['other_ms']=metrics['task_ms']-millis(intervals(lambda s:mysql(s) or isredis(s) or tests['rule_engine_ms'](s)))
  locks=[s for s in spans if s['operation']=='sql SELECT * FROM tb_reservation_dispatch WHERE id=1 FOR UPDATE' and s.get('parent')=='ReservationDispatch.create']
  commits=[s for s in spans if s['operation'].endswith('DataSourceTransactionManager.doCommit') and s.get('parent')=='ReservationDispatch.create']
  assert len(locks)==len(commits)==1
  metrics['create_coordinator_select_ms']=(locks[0]['end']-locks[0]['start'])/1e6;metrics['create_after_lock_to_commit_return_ms']=(commits[0]['end']-locks[0]['end'])/1e6
  for s in spans:
   if s['operation'].startswith('sql '):sqls[s['operation']].append((s['end']-s['start'])/1e6)
   if s['operation'].endswith('HikariDataSource.getConnection'):connection_calls.append((s['end']-s['start'])/1e6)
  metrics['sql_calls']=sum(s['operation'].startswith('sql ') for s in spans);metrics['redis_observed_spans']=sum(isredis(s) for s in spans)
  by_thread[t['ingressThread']].append((start,end));per.append(metrics)
 first=min(t['enqueue'] for t in traces);last=max(t['consumerEnd'] for t in traces)
 points=sorted({first,last}|{x for t in traces for x in [t['enqueue'],t['consumerStart'],t['consumerEnd']]})
 gaps=[];capacity_ns=0;all_busy_queued=0;queued_total=0
 for a,b in zip(points,points[1:]):
  mid=(a+b)//2;q=sum(t['enqueue']<=mid<t['consumerStart'] for t in traces);busy=sum(t['consumerStart']<=mid<t['consumerEnd'] for t in traces)
  if q:
   queued_total+=b-a
   if busy==4:all_busy_queued+=b-a
   else:gaps.append(dict(start=a,end=b,queued=q,busy=busy,free=4-busy));capacity_ns+=(4-busy)*(b-a)
 merged_gaps=merged([(g['start'],g['end']) for g in gaps]);states=collections.Counter();stacks=collections.Counter();samples=[];thread_cpu={}
 for sample in raw['after']['threadSamples']:
  now=sample.get('nano',0)
  if not first<=now<=last:continue
  samples.append(sample)
  for thread in sample['threads']:
   name=thread['name']
   if name not in by_thread:continue
   active=any(a<=now<b for a,b in by_thread[name]);states[('task_' if active else 'outside_task_')+thread['state']]+=1
   if active:stacks[' / '.join(thread['stack'][:5])]+=1
   if thread['cpuNs']>=0:thread_cpu.setdefault(name,[]).append((now,thread['cpuNs']))
 cpu_covered={name:dict(sample_window_ms=(v[-1][0]-v[0][0])/1e6,cpu_ms=(v[-1][1]-v[0][1])/1e6,samples=len(v)) for name,v in thread_cpu.items() if len(v)>1}
 fields=[k for k in per[0] if k not in ['requestId','thread']]
 result=dict(round=r['round'],mode=r['mode'],tasks=per,metrics={k:stats([t[k] for t in per]) for k in fields},ingress_window_ms=(last-first)/1e6,worker_occupied_ms=sum(t['task_ms'] for t in per),worker_task_utilization=sum(t['task_ms'] for t in per)/(4*(last-first)/1e6),queued_duration_ms=queued_total/1e6,all_four_busy_with_queue_ms=all_busy_queued/1e6,any_free_with_queue_ms=millis([(g['start'],g['end']) for g in gaps]),free_with_queue_max_continuous_ms=max(((b-a)/1e6 for a,b in merged_gaps),default=0),unused_worker_ms_with_queue=capacity_ns/1e6,free_with_queue_intervals=gaps,thread_task_ms={k:dict(n=len(v),sum_ms=millis(v)) for k,v in by_thread.items()},sampled_thread_states=dict(states),top_active_stacks=stacks.most_common(12),sampled_thread_cpu=cpu_covered,hikari_peak_active=max(s.get('hikariActive',0) for s in samples),hikari_peak_waiting=max(s.get('hikariWaiting',0) for s in samples),top_sql=[dict(sql=k,**stats(v),total_ms=sum(v)) for k,v in sorted(sqls.items(),key=lambda x:sum(x[1]),reverse=True)[:15]])
 result['connection_call_ms']=stats(connection_calls)
 (out/f"{r['round']}-{r['mode']}.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');all_rounds.append({k:v for k,v in result.items() if k not in ['tasks','free_with_queue_intervals','top_active_stacks']})
(out/'summary.json').write_text(json.dumps(all_rounds,ensure_ascii=False,indent=2),encoding='utf-8')
for r in all_rounds:
 print(r['round'],r['mode'],json.dumps({k:{x:round(r['metrics'][k][x],3) for x in ['mean','p95']} for k in ['task_ms','feature_collect_ms','profile_sql_ms','redis_ms','mysql_ms','rule_engine_ms','risk_all_ms','create_ms','decided_ms','other_ms','create_coordinator_select_ms']},ensure_ascii=False))
 print('QUEUED+FREE ms',r['any_free_with_queue_ms'],'max_continuous',r['free_with_queue_max_continuous_ms'],'threadUtil',r['worker_task_utilization'])
