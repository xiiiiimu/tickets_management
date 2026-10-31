"""Offline ABBAAB analysis. Batch SQL/transactions are never copied or multiplied per task."""
import collections,json,math,pathlib,statistics,sys
root=pathlib.Path(sys.argv[1]).resolve()
def read(p):return json.loads(p.read_text(encoding='utf-8'))
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
def pct(v):
    v=sorted(v)
    return dict(count=len(v),mean=statistics.mean(v),p50=v[math.ceil(len(v)*.50)-1],p95=v[math.ceil(len(v)*.95)-1],p99=v[math.ceil(len(v)*.99)-1]) if v else {}
def duration(s):return (s['end']-s['start'])/1e6
def overlap(a,b,c,d):return max(0,min(b,d)-max(a,c))
results=read(root/'results.json') if (root/'results.json').exists() else [];detailed=[]
for result in results:
    folder=root/f"{result['round']}-{result['mode']}";raw=read(folder/'50-raw.json');full=raw['after'];ts=raw['traces'];first=min(t['enqueue'] for t in ts);last=max(t['locked'] for t in ts);window=last-first
    shared=[c for c in full['claimAttempts'] if overlap(first,last,c['start'],c['end'])]
    success=[c for c in shared if c['size']];assert sum(c['size'] for c in success)==50
    assert len({c['batchId'] for c in shared})==len(shared)
    spans=[s for t in ts for s in t['spans'] if s['start']<=last]+[s for c in shared for s in c['spans']]
    ids=[(s['operation'],s['start'],s['end'],s['thread']) for s in spans];assert len(set(ids))==len(ids),'Shared span duplication'
    groups=collections.defaultdict(list)
    for s in spans:groups[(s['operation'],s.get('parent',''))].append(duration(s))
    ready={}
    for t in ts:
        found=[s for s in t['spans'] if s['operation'].endswith('DataSourceTransactionManager.doCommit') and s.get('parent')=='ReservationDispatch.decided'];assert len(found)==1;ready[t['requestId']]=found[0]['end']
    points=sorted({first,last}|{x for t in ts for x in [ready[t['requestId']],t['claimEnd'],t['saleStart'],t['saleEnd']] if first<=x<=last});idle=0;idle_worker=0
    for a,b in zip(points,points[1:]):
        middle=(a+b)//2;pending=sum(ready[t['requestId']]<=middle<t['claimEnd'] for t in ts);active=sum(t['saleStart']<=middle<t['saleEnd'] for t in ts)
        if pending and active<4:idle+=b-a;idle_worker+=(4-active)*(b-a)
    scheduled=collections.defaultdict(float);threads=collections.defaultdict(float)
    for event in full['scheduled']:
        time_ms=overlap(first,last,event['start'],event['end'])/1e6;scheduled[event['operation']]+=time_ms;threads[event['thread']+' / '+event['operation']]+=time_ms
    if result['mode']=='B':assert not any('RiskHistorySummary' in key and 'sale-dispatch-control' in key for key in threads if threads[key]>0)
    snapshots=[x for x in full['threadSamples'] if first<=x.get('nano',0)<=last];resources=raw['resources'];a=resources[0]['host'];b=resources[-1]['host'];host_cpu=100*(1-(b['host_idle_100ns']-a['host_idle_100ns'])/(b['host_cpu_100ns']-a['host_cpu_100ns']))
    gc0={g[0]:g for g in raw['before']['gc']};gc={g[0]:dict(count=g[1]-gc0[g[0]][1],ms=g[2]-gc0[g[0]][2]) for g in full['gc']}
    by_name=lambda name:[duration(s) for s in spans if s['operation']==name]
    coord_tx=[duration(s) for s in spans if s['operation'].endswith('TransactionTemplate.execute') and s.get('parent')=='ReservationDispatch.create']
    claim_tx=[duration(s) for c in shared for s in c['spans'] if s['operation'].endswith('TransactionTemplate.execute')]
    summary=dict(result,create_ms=pct(by_name('ReservationDispatch.create')),create_tx_ms=pct(coord_tx),claim_tx_ms=pct(claim_tx),durable_queue_ms=pct([max(0,t['saleStart']-ready[t['requestId']])/1e6 for t in ts]),batch_sizes=dict(collections.Counter(str(c['size']) for c in shared)),successful_batch_count=len(success),empty_attempts=len(shared)-len(success),mean_successful_batch_size=50/len(success),claim_supply_tps=50/((max(c['end'] for c in success)-min(c['start'] for c in success))/1e9),sale_idle_with_pending_seconds=idle/1e9,spare_sale_worker_seconds=idle_worker/1e9,scheduled_overlap_ms=dict(scheduled),scheduler_threads=dict(threads),request_sql_calls=sum(s['operation'].startswith('sql ') for t in ts for s in t['spans']),shared_claim_sql_calls=sum(s['operation'].startswith('sql ') for c in shared for s in c['spans']),shared_claim_tx_count=len(claim_tx),host_cpu_percent=host_cpu,host_free_min_mib=min(x['host']['host_memory_available'] for x in resources)/2**20,java_rss_max_mib=max(x['memory']['rss'] for x in resources)/2**20,gc=gc,hikari_active_max=max(x.get('hikariActive',0) for x in snapshots),hikari_wait_max=max(x.get('hikariWaiting',0) for x in snapshots),ingress_queue_max=max(x.get('ingressQueue',0) for x in snapshots),sale_active_max=max(x.get('saleActive',0) for x in snapshots))
    # Lock rows are sampled observations, not independent wait counts or pure wait duration.
    anchor=full;wall=lambda nano:anchor['wallMillis']/1000+(nano-anchor['nano'])/1e9
    samples=[s for s in read(folder/'mysql-observations.json') if wall(first)-.1<=s.get('epoch',0)<=wall(last)+.1];objects=collections.Counter();positive=0
    for sample in samples:
        positive+=bool(sample.get('locks'))
        for row in sample.get('locks',[]):objects[row['table_name']+':'+str(row['index_name'])]+=1
    summary['mysql_lock_sampling']=dict(samples=len(samples),positive=positive,records_by_object=dict(objects))
    save(folder/'comparison-analysis.json',summary);detailed.append(summary)

group_stats={}
for mode in ['A','B']:
    values=[x for x in detailed if x['mode']==mode];stats={}
    for metric in ['lock_tps','batch_seconds','sale_idle_with_pending_seconds']:
        numbers=[x[metric] for x in values]
        if numbers:stats[metric]=dict(n=len(numbers),median=statistics.median(numbers),mean=statistics.mean(numbers),min=min(numbers),max=max(numbers),sample_cv_percent=statistics.stdev(numbers)/statistics.mean(numbers)*100 if len(numbers)>1 else None)
    latencies=[x['timings']['end_to_end']['p95'] for x in values]
    if latencies:stats['e2e_p95_ms']=dict(n=len(latencies),median=statistics.median(latencies),min=min(latencies),max=max(latencies),sample_cv_percent=statistics.stdev(latencies)/statistics.mean(latencies)*100 if len(latencies)>1 else None)
    group_stats[mode]=stats
complete=len(detailed)==6 and [x['mode'] for x in detailed]==['A','B','B','A','A','B'];improvements=None;pairwise=[]
if complete:
    ma=group_stats['A'];mb=group_stats['B'];improvements=dict(tps_percent=(mb['lock_tps']['median']/ma['lock_tps']['median']-1)*100,e2e_p95_reduction_percent=(1-mb['e2e_p95_ms']['median']/ma['e2e_p95_ms']['median'])*100)
    for left,right in [(0,1),(2,3),(4,5)]:
        a,b=sorted([detailed[left],detailed[right]],key=lambda x:x['mode']);pairwise.append(dict(a_round=a['round'],b_round=b['round'],tps_b_better=b['lock_tps']>a['lock_tps'],p95_b_better=b['timings']['end_to_end']['p95']<a['timings']['end_to_end']['p95']))
stable=bool(complete and improvements['tps_percent']>0 and improvements['e2e_p95_reduction_percent']>0 and all(x['tps_b_better'] and x['p95_b_better'] for x in pairwise))
save(root/'comparison-summary.json',dict(complete=complete,rounds=detailed,groups=group_stats,median_improvements=improvements,adjacent_pairs=pairwise,stable_in_this_matrix=stable,production_defaults_remain_off=True,not_capacity_limit=True))

lines=['# 受控调度统一对照结果','',f'原始目录：`{root}`。固定顺序ABBAAB；同一候选构建，仅三个启动开关不同。', '', '完整预检、任务级选择边界、清理证据和验收限制见[DETAILS.md](DETAILS.md)。本轮整体TPS/P95方向改善不等于全部目标通过：create尾延迟和协调锁等待仍未改善；三个开关继续默认关闭。', '', '|轮次|模式|锁单TPS|端到端P50/P95/P99 ms|内存等待P95 ms|持久等待P95 ms|create TX P95 ms|claim TX P95 ms|sale空闲且待claim 秒|','|---|---|---:|---|---:|---:|---:|---:|---:|']
for n,mode in enumerate(['A','B','B','A','A','B'],1):
    found=[x for x in detailed if x['round']==n]
    if not found:lines.append(f'|{n}|{mode}|未执行|—|—|—|—|—|—|');continue
    x=found[0];e=x['timings']['end_to_end'];lines.append(f"|{n}|{mode}|{x['lock_tps']:.3f}|{e['p50']:.1f}/{e['p95']:.1f}/{e['p99']:.1f}|{x['timings']['memory_wait']['p95']:.1f}|{x['durable_queue_ms']['p95']:.1f}|{x['create_tx_ms']['p95']:.1f}|{x['claim_tx_ms']['p95']:.1f}|{x['sale_idle_with_pending_seconds']:.3f}|")
lines += ['', '持久等待口径为QUEUED事务提交返回至sale开始，包含claim与交接，不能当成纯轮询等待；更细任务选择边界见DETAILS.md。claim TX为去重批次级计时，不复制到每个任务后累计；A每批1笔、B通常多笔，不是相同工作量的单任务事务比较。不能把包含关系的P95相加。sale空闲指存在待claim任务且至少一个sale工作线程空闲的区间，含正常领取/交接，不能全当可消除浪费；它不是四线程全部空闲的时间。','', '## 组内波动与相对改善','', '```json',json.dumps(dict(groups=group_stats,median_improvements=improvements,adjacent_pairs=pairwise),ensure_ascii=False,indent=2),'```','',f'完整矩阵：{complete}；本轮稳定改善判据（3对相邻A/B均TPS上升且P95下降，中位数同方向）：{stable}。仅3次/组，不能当稳定容量或统计显著性保证。三个生产开关保持默认关闭。']
if not complete:lines += ['', '矩阵未完成，不计算完整A/B结论；未执行不是0 TPS，不能填造波动或提升幅度。停止原因见stopped.json。']
lines += ['', '## 供给、批次与环境','', '|轮次|成功批次数/平均大小|空claim|claim供给任务/s|摘要刷新秒|CPU Java核/宿主%|最少可用MiB|YoungGC ms|锁等待正采样/总采样|','|---|---|---:|---:|---:|---|---:|---:|---|']
for x in detailed:
    lock=x['mysql_lock_sampling'];lines.append(f"|{x['round']}-{x['mode']}|{x['successful_batch_count']}/{x['mean_successful_batch_size']:.2f}|{x['empty_attempts']}|{x['claim_supply_tps']:.3f}|{x['scheduled_overlap_ms'].get('RiskHistorySummary.refreshPending',0)/1000:.3f}|{x['java_cpu_cores']:.2f}/{x['host_cpu_percent']:.1f}|{x['host_free_min_mib']:.1f}|{sum(g['ms'] for k,g in x['gc'].items() if k!='MarkSweepCompact')}|{lock['positive']}/{lock['samples']}|")
lines += ['', 'claim供给窗口从首个成功领取开始到最后成功领取返回，与锁单TPS窗口不同；不是容量。数据库等待是采样记录，不是独立等待事件。JVM冷启动相同但OS缓存/宿主其他任务不受控制；资源差异、逐轮原始数据必须并列解释，不挑最快一轮。', '', '每轮原始数据包含configuration、JFR、所有批次与任务spans、内存/线程快照、数据库锁观察、库存/订单/租约及幂等核验、owner清理记录。没有调整任何业务池/限流/资源配置，没有部署云端；本报告只离线生成，不会发起负载。']
(root/'REPORT.md').write_text('\n'.join(lines)+'\n',encoding='utf-8');print(root/'REPORT.md')
