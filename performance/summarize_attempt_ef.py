"""Offline summary only; never starts a JVM, touches Docker or sends a request."""
from pathlib import Path
import json,statistics,sys

root=Path(sys.argv[1]).resolve()
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
rounds=[]
for index,mode in enumerate('ABBAAB',1):
    path=root/f'{index}-{mode}';batch=read(path/'batch.json');cleanup=read(path/'cleanup.json')
    assert batch['all_invariants_passed'] and not batch['resource_pressure']
    assert cleanup['passed'] and cleanup['schema_and_account_absent'] and cleanup['redis_keys_left']==0 and cleanup['owned_test_process_exited']
    assert batch['vector']==(mode=='B')
    batch.update(round=index,mode=mode)
    rejections=batch.get('admission_rejections')
    batch['db_busy_count']=sum(n for reason,n in rejections.items() if reason.startswith('DB_BUSY/')) if isinstance(rejections,dict) else None
    batch['waiting_budget_count']=rejections.get('CAPACITY_REJECT/waiting_budget',0) if isinstance(rejections,dict) else None
    batch['other_capacity_reject_count']=sum(n for reason,n in rejections.items() if reason.startswith('CAPACITY_REJECT/') and reason!='CAPACITY_REJECT/waiting_budget') if isinstance(rejections,dict) else None
    batch['db_busy_observer_overlap_count']=batch.get('db_busy_observer_overlap',{}).get('overlapping_observer_query')
    for operation in ['register','claim']:
        for phase in ['total','begin','body','completion']:
            values=batch.get('transaction_ms',{}).get(operation,{}).get('success',{}).get(phase,{})
            batch[f'{operation}_success_{phase}_p95_ms']=values.get('p95') if values.get('count',0)>0 else None
    samples=read(path/'samples.json');db=read(path/'db-observations.json')
    batch['max_hikari_waiters']=max((x.get('hikariWaiting',0) for x in samples),default=0)
    batch['max_hikari_active']=max((x.get('hikariActive',0) for x in samples),default=0)
    batch['min_free_memory_mib']=min((x['freePhysicalBytes']/1024**2 for x in samples if 'freePhysicalBytes' in x),default=None)
    batch['peak_heap_mib']=max((x.get('heapUsed',x.get('heap',0))/1024**2 for x in samples),default=0)
    activity=[x['saleActive'] for x in samples if 'saleActive' in x]
    batch['sale_active_sample_mean']=statistics.mean(activity) if activity else None
    batch['sale_active_sample_max']=max(activity) if activity else None
    batch['max_sampled_processing']=max((sum(int(c['n']) for c in x.get('counts',[]) if c['state']=='PROCESSING') for x in db),default=0)
    batch['max_sampled_waiting']=max((sum(int(c['n']) for c in x.get('counts',[]) if c['state']=='WAITING') for x in db),default=0)
    assert batch['max_sampled_processing']<=4 and batch['max_sampled_waiting']<=512,'Observed global capacity violation'
    batch['lock_sample_rows']=sum(len(x.get('locks',[])) for x in db)
    batch['lock_sample_count']=len(db)
    tables={}
    for sample in db:
        for lock in sample.get('locks',[]):tables[lock.get('table_name','unknown')]=tables.get(lock.get('table_name','unknown'),0)+1
    batch['lock_sample_tables']=tables
    batch['observer_errors']=[x for x in db if 'error' in x]
    rounds.append(batch)

def metric(row,key):
    value=row
    for component in key.split('/'):value=value.get(component) if isinstance(value,dict) else None
    return value

diagnostic_keys=['db_busy_count','waiting_budget_count','other_capacity_reject_count','db_busy_event_count','db_busy_observer_overlap_count']+[f'{operation}_success_{phase}_p95_ms' for operation in ['register','claim'] for phase in ['total','begin','body','completion']]
keys=['http_qps','accepted_qps','held_tps','held','post_ms/p95','held_e2e_ms/p95','waiting_ms/p95','processing_ms/p95','gc_collection_ms']+diagnostic_keys
groups={}
for mode in 'AB':
    groups[mode]={}
    for key in keys:
        values=[metric(r,key) for r in rounds if r['mode']==mode and metric(r,key) is not None]
        groups[mode][key]={'median':statistics.median(values),'min':min(values),'max':max(values)} if values else None
change={key:100*(groups['B'][key]['median']/groups['A'][key]['median']-1) for key in keys if groups['A'][key] and groups['B'][key] and groups['A'][key]['median']}
(root/'comparison-summary.json').write_text(json.dumps(dict(rounds=rounds,groups=groups,median_change_percent=change),ensure_ascii=False,indent=2),encoding='utf-8')
fmt=lambda value:'尚无数据' if value is None else f'{value:.2f}'
diagnostic_headers=['DB_BUSY次数','waiting_budget次数','其他CAPACITY_REJECT次数','DB_BUSY事件数','与observer重叠数']+[f'{operation} success {phase} P95 ms' for operation in ['register','claim'] for phase in ['total','begin','body','completion']]
lines=['# E/F统一六轮对照结果','','同一候选代码，顺序ABBAAB；A单桶协议，B向量协议。可靠attempt、ordered-Lua、快速REVIEW均开启；不是旧易失入口与新可靠入口的等价吞吐比较。每轮50唯一POST、并发10、新JVM/库/Redis DB；3预热及4幂等重试另列。',
       '', '|轮次|模式|HTTP QPS|202 QPS|HELD TPS|HELD数|WAITING P95 ms|PROCESSING P95 ms|HELD端到端P95 ms|429/503|终态|'+'|'.join(diagnostic_headers)+'|', '|---|---|---:|---:|---:|---:|---:|---:|---:|---|---|'+'|'.join(['---:']*len(diagnostic_headers))+'|']
for row in rounds:
    lines.append('|'+ '|'.join([str(row['round']),row['mode'],fmt(row['http_qps']),fmt(row['accepted_qps']),fmt(row['held_tps']),str(row['held']),fmt(metric(row,'waiting_ms/p95')),fmt(metric(row,'processing_ms/p95')),fmt(metric(row,'held_e2e_ms/p95')),str(row['rejected'])+'/'+str(row['unavailable']),json.dumps(row['states'],ensure_ascii=False)]+[fmt(metric(row,key)) for key in diagnostic_keys])+'|')
lines+=['','分段begin/body/completion的P95不能相加，也不等于total的P95。上述事务列仅取success；count=0或历史结果未导出该指标时显示“尚无数据”，不伪装成0ms。',
        '拒绝子分类核对仅展示，不影响原有正确性断言：']
for row in rounds:
    check=row.get('admission_rejections_check')
    lines.append(f"- {row['round']}-{row['mode']}: "+(json.dumps(check,ensure_ascii=False) if check is not None else '历史结果未导出核对数据'))
    export_check=row.get('transaction_export_check');busy_check=row.get('db_busy_event_check')
    lines.append('  transaction_export_check.ok='+json.dumps(export_check.get('ok') if isinstance(export_check,dict) else None,ensure_ascii=False)+'; db_busy_event_check='+(json.dumps(busy_check,ensure_ascii=False) if busy_check is not None else '尚无数据'))
lines+=['DB_BUSY时间戳落入observer查询区间仅表示时间重叠，不构成连接占用或拒绝原因的因果证据。']
lines+=['','|指标|A中位数（范围）|B中位数（范围）|B相对A中位数变化|','|---|---:|---:|---:|']
for key in keys:
    values=[groups[mode][key] for mode in 'AB']
    render=lambda v:'尚无数据' if v is None else f"{v['median']:.2f} ({v['min']:.2f}—{v['max']:.2f})"
    relative=fmt(change[key])+'%' if key in change else '尚无数据'
    lines.append(f"|{key}|{render(values[0])}|{render(values[1])}|{relative}|")
lines+=['','## 方法与边界','',
 'HTTP QPS按50个初始POST从首次发送至最后响应计算；202成功提交另列，429/503不计成功。GET、预热及幂等重试不进入POST QPS。HELD TPS分母为第一笔可靠登记至最后一笔成功终态被观察到的窗口，失败/超时数另列，不能将低负载批次吞吐当作容量上限。',
 '端到端以数据库可靠登记时间为起点，后台期间并发采样终态，目标间隔200ms；数据库查询和调度会造成额外观察延迟，不能承诺200ms硬误差上限。DB/主机时钟校准RTT保存在每轮batch.json。WAITING取SQL processing_started_at-created_at；PROCESSING终点为观察时刻，包含异步调度等待，不等于CPU执行时间。',
 'stages.lua是Java调用（含Redis往返及marker读取），不是Redis服务端纯执行时间。reserve_method包含顺序等待及MySQL确认，不能与子阶段P95相加。timer_ms的reservation.v2.transaction为确认事务计时；Micrometer百分位为HDR近似。Hikari计时包括更广的连接池活动，未声称逐请求锁等待精确归因。',
 '同机同测试JVM内HTTP客户端与服务端共享CPU、堆及GC；Mockito测试计时、JFR、SQL采样、资源采样对两组相同，但仍有诊断开销和环境漂移。锁等待采样行不是独立等待事件数，未捕获不证明绝对无等待。',
 '所有终态、幂等、库存、订单、预占、租约及清理断言已通过；原始状态、计时、资源、JFR和SQL采样均在各轮目录。REVIEW缓存命中及超时计数在counter_delta，独立用户负载不代表重复用户缓存命中场景。',
 '', '新功能默认关闭。该对照只测E协议的代价/影响，不据此更改线程池、队列、限流、数据库配置或继续追加优化。']
(root/'COMPARISON.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print('\n'.join(lines[:17]))
