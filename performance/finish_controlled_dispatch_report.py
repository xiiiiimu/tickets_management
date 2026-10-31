"""Offline supplementary report and provenance. Never starts a service or sends load."""
import collections,hashlib,json,pathlib,re,shutil,statistics,subprocess,sys
root=pathlib.Path(sys.argv[1]).resolve();workspace=pathlib.Path(__file__).resolve().parents[1]
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
summary=read(root/'comparison-summary.json');assert summary['complete']
def pct(values):
    v=sorted(values);return dict(n=len(v),p50=v[(len(v)*50+99)//100-1],p95=v[(len(v)*95+99)//100-1],p99=v[(len(v)*99+99)//100-1])
details=[];initial=[]
for folder in sorted(p for p in root.iterdir() if p.is_dir() and (p/'database-initialization.json').exists()):
    phases=read(folder/'database-initialization.json');ph={x['phase']:x['elapsed_ms'] for x in phases};assert all(x<35000 for x in ph.values())
    text=(folder/'java.log').read_text(encoding='utf-8',errors='replace');match=re.search(r'Started FullChainProbe in ([\d.]+) seconds \(JVM running for ([\d.]+)\)',text)
    initial.append(dict(round=folder.name,phases_ms=ph,spring_ready_seconds=float(match[1]) if match else None,jvm_ready_seconds=float(match[2]) if match else None))
for row in summary['rounds']:
    folder=root/f"{row['round']}-{row['mode']}";raw=read(folder/'50-raw.json');traces=raw['traces'];attempts=raw['after']['claimAttempts'];queued={};selected={};leases={}
    for c in attempts:
        for lease in c['leases']:leases[lease['jobId']]=lease['requestId']
    for t in traces:
        commits=[s for s in t['spans'] if s['operation'].endswith('DataSourceTransactionManager.doCommit') and s.get('parent')=='ReservationDispatch.decided'];assert len(commits)==1;queued[t['requestId']]=commits[0]['end']
    for c in attempts:
        for s in c['spans']:
            if s['operation'].startswith('sql SELECT * FROM tb_reservation_job WHERE state=') and s.get('resultState')=='QUEUED' and s.get('resultId') in leases:
                rid=leases[s['resultId']];assert rid not in selected;selected[rid]=s
    assert len(selected)==len(traces)==50
    before_selection=[];through_selection=[];after_selection=[]
    for t in traces:
        rid=t['requestId'];s=selected[rid];before_selection.append(max(0,s['start']-queued[rid])/1e6);through_selection.append(max(0,s['end']-queued[rid])/1e6);after_selection.append((t['claimEnd']-s['end'])/1e6)
    jfr=read(folder/'jfr-analysis.json')['single-50'];gc=raw['after']['gc'];old={g[0]:g for g in raw['before']['gc']}
    item=dict(round=row['round'],mode=row['mode'],http_qps=row['http_qps'],http_ms=pct([r['http_ms'] for r in raw['requests']]),status_counts=dict(collections.Counter(str(r['code']) for r in raw['requests'])),decisions=dict(collections.Counter(t['decision'] for t in traces)),queued_to_selected_sql_submit_ms=pct(before_selection),queued_to_selected_sql_return_ms=pct(through_selection),selected_to_claim_return_ms=pct(after_selection),gc_total_ms=sum(g[2]-old[g[0]][2] for g in gc),jfr_gc_pause_sum_ms=sum(g['duration_ms'] for g in jfr['gc_pauses']),jfr_gc_pause_max_ms=max((g['duration_ms'] for g in jfr['gc_pauses']),default=0),cleanup=read(folder/'cleanup.json'))
    assert item['status_counts']=={'202':50};assert len(read(folder/'50-idempotency.json'))==4
    assert item['cleanup']['owned_schema_removed'] and item['cleanup']['owned_java_exited'] and item['cleanup']['redis_keys_left']==0
    details.append(item)
groups={mode:[r for r in summary['rounds'] if r['mode']==mode] for mode in ['A','B']}
medians={m:{k:statistics.median(r[k]['p95'] for r in rs) for k in ['create_tx_ms','claim_tx_ms','durable_queue_ms']} for m,rs in groups.items()}
mem={m:statistics.median(r['timings']['memory_wait']['p95'] for r in rs) for m,rs in groups.items()}
result=dict(rounds=details,initialization=initial,median_p95_ms=medians,memory_wait_median_p95_ms=mem)
(root/'supplementary-metrics.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
lines=['# 完整预检、细分口径和验收结论','','本轮已完成A/B两次无抢票负载的完整预检，随后只执行ABBAAB六轮。没有追加轮次。','','## 初始化与清理','','所有阶段上限仍为35秒；服务启动70秒、任务终态观察60秒、JVM watchdog300秒不变。内存启动门槛1GiB、负载持续低于256MiB停止，磁盘预留1GiB；未降低。','','|阶段/轮次|helper启动 ms|建库 ms|schema ms|data ms|Spring/JVM就绪 秒|','|---|---:|---:|---:|---:|---|']
for r in initial:
    v=r['phases_ms'];lines.append(f"|{r['round']}|{v['startup']:.1f}|{v['create']:.1f}|{v['initialize-schema']:.1f}|{v['initialize-data']:.1f}|{r['spring_ready_seconds']}/{r['jvm_ready_seconds']}|")
lines+=['','两个预检的任务、订单、意图、租约为0，Armory READY/版本1、16桶库存总数1000，原始线程池/队列/限流/Hikari及实际A/B开关校验通过。每轮新库、新Redis空DB及新JVM；用同一已批准候选生产代码和诊断构建，仅三个开关切换。','','## HTTP与任务选择边界','','HTTP QPS=50次POST完成数/整批POST发送至最后响应的窗口，202成功提交QPS相同；GET、管理装配和4次重试不计入。不是固定到达率容量测试。','','|轮次|HTTP/202 QPS|POST P50/P95/P99 ms|风控结果|QUEUED→选中SQL发起 P95 ms|QUEUED→选中SQL返回 P95 ms|选中→claim返回 P95 ms|GC总/JFR最大暂停 ms|','|---|---:|---|---|---:|---:|---:|---|']
for x in details:
    h=x['http_ms'];lines.append(f"|{x['round']}-{x['mode']}|{x['http_qps']:.2f}|{h['p50']:.2f}/{h['p95']:.2f}/{h['p99']:.2f}|{x['decisions']}|{x['queued_to_selected_sql_submit_ms']['p95']:.2f}|{x['queued_to_selected_sql_return_ms']['p95']:.2f}|{x['selected_to_claim_return_ms']['p95']:.2f}|{x['gc_total_ms']}/{x['jfr_gc_pause_max_ms']:.2f}|")
lines+=['','选中边界由batchId→jobId→requestId关联共享claim SQL的返回行提取；每笔均有且仅有一条返回QUEUED行的候选SELECT。SQL发起前的等待包含调度与协调行竞争，不能仅称轮询等待；SQL返回后至claim返回包含后续批次工作和事务提交。原始提交返回是客户端确认时间，跨连接在提交返回前即观察到提交的细小差值按0截断；不能凭客户端边界声称精确数据库提交瞬间。','','## 整体收益与未达目标','',f"本轮TPS中位数提高{summary['median_improvements']['tps_percent']:.2f}%，端到端P95中位数下降{summary['median_improvements']['e2e_p95_reduction_percent']:.2f}%。三对相邻比较方向均有利于B；A的TPS变异系数29.19%，B为4.86%。每组仅3次，不能承诺生产稳定收益或容量上限。",'',f"create事务P95的组中位数A={medians['A']['create_tx_ms']:.2f}ms，B={medians['B']['create_tx_ms']:.2f}ms，未改善；内存等待P95组中位数A={mem['A']:.2f}ms，B={mem['B']:.2f}ms，同样增加。claim事务P95组中位数A={medians['A']['claim_tx_ms']:.2f}ms，B={medians['B']['claim_tx_ms']:.2f}ms，但A单笔、B多笔批次的工作量不同。",'', 'B成功领取批次数由A每轮50次降至26/27/28次，平均批量1.92/1.85/1.79；控制线程不再执行摘要刷新，摘要仍由scheduling-1运行。供给和持久等待改善支持整体方案有效；协调行锁等待正采样及记录反而更多，不能宣称消除了协调锁热点。锁采样率和次数不是独立等待事件或精确阻塞持续时间。', '', '三个开关组合切换，不能单独把收益归因于隔离、批量或唤醒中的某一项；本轮未增加单开关测试。正确性和本轮整体TPS/P95方向通过，但“create尾延迟不恶化/协调锁等待下降”等目标未全部达成。保留候选代码、三个开关继续默认关闭，不自动上线或追加优化。', '', '## 环境漂移与观测限制','', '宿主CPU逐轮约58.4/58.8/84.4/86.3/76.4/83.5%；A第4轮比第1轮慢、第5轮又恢复，不能排除宿主任务、OS/数据库缓存、调度与IO差异。A第4轮同调度线程摘要占用约11.18秒，第5轮约4.04秒，和性能波动同向，但不是对环境影响的独立因果证明。所有JVM冷启动、JFR/agent/采样设置相同，阶段调用数和埋点事件数随代码分支而变，诊断开销不等于生产无埋点成本。', '', 'Hikari等待线程采样均为0，不能据此排除任意短时连接等待；JFR、原始线程及SQL耗时可复查。本轮没有容器逐时CPU/BlockIO采集，两组均缺失，不以累计IO冒充阶段IO。摘要耗时为已完成记录在测量窗口内的重叠部分，未完成而尚未进入快照的调用可能未计入，不应当作全部摘要成本。', '', '## 正确性与最终清理','', '300/300唯一请求均202且最终可靠预占；各轮订单WAITING_PAYMENT、预占HELD、恢复意图COMPLETED、持久任务RESERVED，lease清空，4次重试无新提交。各轮库存1000→950，Redis与MySQL逐桶一致，50个唯一订单/预占/分配及限购计数一致；50任务均唯一消费、唯一claim、lease关联正确。活动任务≤4、容量≤512为数据库采样核验；租约和容量的更广并发正确性依赖此前通过的针对性测试，本轮不冒充穷尽验证。没有支付、出票、MQ消费或付费Agent调用。', '', '八个preflight/轮次cleanup.json全部记录owned_schema_removed=true、owned_java_exited=true、redis_keys_left=0。最终final-environment再次只读确认无受控库、账号、Redis标记，测试进程与18090监听均为空；两个专属容器保持原先运行状态，无生命周期操作。历史报告和其他业务数据未改。', '', '## 可复现工具与原始数据','', 'runtime代码/类/agent哈希见code-identity.json；分支、HEAD及工具哈希见provenance.json和tool-sources。固定负载配置见每轮configuration.json；原始50-raw.json、mysql-observations.json、inventory.json、50-idempotency.json、full-chain.jfr、jfr-analysis.json与cleanup.json逐轮保存。', '', '以下命令仅离线生成报告，不发请求：','','```powershell',f'python performance/analyze_controlled_dispatch_compare.py {root}',f'python performance/finish_controlled_dispatch_report.py {root}','```','','完整负载驱动为performance/controlled_dispatch_compare.py --execute-approved；本轮已执行完毕，该命令会创建新实验，当前不要再次执行。']
(root/'DETAILS.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
tools=['controlled_dispatch_compare.py','ComparisonDatabase.java','FullChainProbe.java','FullChainAgent.java','ComparisonProbeSelfTest.java','analyze_controlled_dispatch_compare.py','FullChainJfrSummary.java','finish_controlled_dispatch_report.py','test_initialization_control.py'];target=root/'tool-sources';target.mkdir(exist_ok=True);hashes={}
for name in tools:
    src=workspace/'performance'/name;shutil.copyfile(src,target/name);hashes[name]=hashlib.sha256(src.read_bytes()).hexdigest()
git=lambda *args:subprocess.check_output(['git',*args],cwd=workspace,text=True,timeout=10).strip()
provenance=dict(head=git('rev-parse','HEAD'),branch=git('branch','--show-current'),dirty_worktree_protected=True,tools_sha256=hashes,loads_complete=True,rounds=6,unique_requests=300,idempotent_replays=24,production_defaults_off=True,additional_loads=False)
(root/'provenance.json').write_text(json.dumps(provenance,ensure_ascii=False,indent=2),encoding='utf-8')
print(root/'DETAILS.md')
