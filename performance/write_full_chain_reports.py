"""Generate delivery documents from saved data only. Never sends business requests."""
import html,json,math,os,pathlib,sys
root=pathlib.Path(sys.argv[1]).resolve();perf=pathlib.Path(__file__).parent
def read(name):return json.loads((root/name).read_text(encoding='utf-8'))
def write(name,text):(perf/name).write_text(text.strip()+'\n',encoding='utf-8')
a=read('full-chain-analysis.json');r=read('50-result.json');raw=read('50-raw.json');rows=read('request-timeline.json');resources=read('resource-summary.json');sql=read('sql-summary.json');funnel=read('throughput-funnel.json');jfr=read('jfr-analysis.json')['single-50'];version=read('version.json')
phase=a['phases'];ms=lambda k:phase[k]['p95'];prefix=os.path.commonprefix([x['requestId'] for x in rows]);ordered=sorted(rows,key=lambda x:x['enqueue']);t0=min(x['enqueue'] for x in rows);tend=max(x['locked'] for x in rows);window=(tend-t0)/1e9
evidence=f"本轮原始目录：`{root}`；HEAD `{version['head']}`，有未提交开发成果，以version.json/source-index哈希定位实际版本。"
scope="本轮只发送一次50个唯一有效请求，最大发送并发10，另4次相同requestId重试；未进行抢票预热。第一次装配预检查失败发生在任何抢票POST之前，已核实0 job/order/intent并清理，不属于第二个业务批次。真实Armory/规则/Redis预占/MySQL16桶及订单事务；支付、出票、MQ发布和付费Agent不运行。"
limits="更完整埋点/JFR/100ms线程采样有未校准开销；历史50档已被前面的1/10/30档预热，本轮单50档是冷启动，不能用1.69对比2.30宣称回归或稳定容量。JDBC连接ID提取失败（测量结束1152次miss），不能把MySQL等待精确分配到每笔请求；Docker stats记录为空，容器CPU/RAM/BlockIO缺失；events_waits consumers未启用，未更改设置，不能证明fsync/磁盘已饱和。"
timeline=['# 全链路时间线：唯一50请求批次','',evidence,'',scope,'',limits,'','## 时钟与边界','',
'同一诊断JVM所有阶段使用System.nanoTime。快照同时带nano/wallMillis，MySQL采样用Windows epoch；clock-anchors.json及mysql-before/after的server_epoch提供校准。Java wall与nano成对，客户端client_wall在元数据采集后记录，不当作网络到达时间；未同步的毫秒级差不能解释为锁获得时刻。HTTP T1从最外层测试Filter开始到响应返回，不含此前TCP/Tomcat接入等待；客户端http_ms另存原始请求。参数绑定/Filter残余没有单独探针，不能自动归因。',
'', '成功入队时间取原execute调用入口（已确认execute成功）；不是内部queue.offer的精确时间，核心线程首任务可直接派发。ready取decided事务doCommit返回（可见性上界），不是SQL设置queued_at的瞬间。锁单终点为最终reserve事务已提交、方法返回；HELD/WAITING_PAYMENT/COMPLETED/RESERVED联合核验，不以order INSERT或Lua返回为成功。','',
'## 阶段分位数（ms；包含关系不能相加）','', '|阶段|P50|P95|P99|均值|','|---|---:|---:|---:|---:|']
labels=[('http_ms','服务端HTTP T1'),('memory_wait_ms','入口排队T2'),('ingress_work_ms','入口worker实际工作'),('durable_pre_claim_ms','QUEUED commit→自身claim开始'),('claim_ms','自身claim方法/事务'),('sale_handoff_ms','claim返回→sale提交'),('sale_pool_wait_ms','sale提交→开始'),('sale_to_lock_ms','sale开始→可靠锁单'),('end_to_end_ms','成功接纳→可靠锁单')]
for key,label in labels:
    v=phase[key];timeline.append(f"|{label}|{v['p50']:.3f}|{v['p95']:.3f}|{v['p99']:.3f}|{v['mean']:.3f}|")
timeline += ['',f"T3（消费者开始→锁单，含持久队列）P95 {r['timings']['backend']['p95']:.3f}ms；整批首接纳→最后锁单 {window:.3f}秒；50/窗口={r['lock_tps']:.3f} TPS，不是系统容量。HTTP客户端POST窗口 {r['http_seconds']:.3f}秒、{r['http_qps']:.3f} QPS，50个202，无429/4xx/5xx。",'', '身份验证preHandle P95 511.60ms；Controller P95 88.55ms；限流桶方法体及HGETALL/PEXPIRE、三次Armory、create/features/rules/intent/reserve均有每笔start_ns/end_ns和耗时列，见CSV。桶synchronized入口等待不包含在方法体Advice内，须结合JFR，不能以方法体短证明无监视器竞争。', '', '## 50笔逐请求','',f"统一traceId=requestId；完整前缀 `{prefix}`，下表尾号补到前缀后即完整ID。精确纳秒边界及全部阶段见 `{root.name}/request-timeline.csv`、request-timeline.json、50-raw.json。",'', '|尾号|HTTP|内存等候|入口工作|持久至sale|自身claim|sale池等候|sale至锁单|端到端|入口/sale线程|','|---|---:|---:|---:|---:|---:|---:|---:|---:|---|']
for x in ordered:
    vals=[x[k] for k in ['http_ms','memory_wait_ms','ingress_work_ms','durable_to_sale_ms','claim_ms','sale_pool_wait_ms','sale_to_lock_ms','end_to_end_ms']]
    timeline.append('|'+x['requestId'][len(prefix):]+'|'+'|'.join(f'{v:.1f}' for v in vals)+'|'+x['ingress_thread']+'/'+x['sale_thread']+'|')
timeline += ['', '## 线程时间线','', '|线程/任务|窗口内工作秒|窗口占比|','|---|---:|---:|']
for x in a['thread_busy']:timeline.append(f"|{x['thread']} / {x['kind']}|{x['busy_ms']/1000:.3f}|{x['window_utilization']*100:.2f}%|")
timeline += ['',f"sale四线程平均任务占用约{sum(x['window_utilization'] for x in a['thread_busy'] if x['kind']=='sale_work')/4*100:.1f}%；入口四线程在前约18.65秒承担持续IO，之后退出本批工作，不是全29.5秒都满载。",'', '同一个scheduling-1在本窗口：dispatch 20.173秒（68.38%），后台摘要8.944秒（30.32%，46次rebuild/335条SQL），监控42.5ms，reap 0.88ms。dispatch每轮P95 1973.96ms，最大2673.69ms；配置25ms只是轮后延迟。', '',f"按逐请求区间重建，有待完成claim且sale活动数<4的时间 {a['pending_with_spare_sale_seconds']:.3f}秒；其中部分是正常claim执行/交接，不全可消除。精确区间见pending-with-spare-sale.json。采样sale峰值3，轨迹任务占用按4线程分母；没有证据表明增加sale线程能解决供给问题。",'',
'## 持久等待的可观测分区','', '逐笔ready→自身claim开始的均值6046.8ms，其中与scheduler执行其他dispatch轮次重叠3690.2ms、后台summary重叠2324.1ms、监控10.1ms、reap0.14ms、其余未分配22.3ms。以上来自每笔不交叠时间区间；是调度线程当时做什么，**不是删掉这些工作就能等量减少延迟的因果收益预测**。自身claim全方法均值334.1ms另列，sale提交/排队均为毫秒级。1笔claim扫描在该任务QUEUED提交返回前62.39ms已经开始（RC后续语句仍可读到新任务）；排他分区使用claim_after_ready_ms（均值332.9ms），不能重复加入这62.39ms。逐笔核对ready→sale=preclaim+claim_after_ready+handoff+pool_wait。',
'', '61次claim尝试：50成功，11无可领取/被跳过；本轮没有active_count>=4拒绝的观察，全部PASS，没有REVIEW加权竞争；pending容量最多50<512，容量满拒绝为0。不能把不可见的global limit/priority等待凭空分配秒数。精确锁等待不可由Java SELECT墙钟推导。',
'', '## SQL、锁与资源','', '2800次去除Db/Jdbc嵌套重复后的业务SQL（56/请求），350次Java可见提交（7/请求，包括为该请求成功claim的提交）。后台摘要SQL另计335，空claim、监控及框架会话SQL另计；业务SQL计数不含驱动自动生成的SET/事务隔离查询。Redis高层逻辑命令1550（31/请求），库存脚本50、特征脚本300；不是350次库存扣减。',
'', '|最终reserve直接SQL（非纯服务端执行）|次数|均值ms|P95ms|','|---|---:|---:|---:|']
for x in sorted([x for x in sql if x['parent']=='ReservationV2Transactions.reserve'],key=lambda x:x['ms']['p95'],reverse=True):timeline.append(f"|`{x['sql']}`|{x['ms']['count']}|{x['ms']['mean']:.2f}|{x['ms']['p95']:.2f}|")
timeline += ['',f"Hikari getConnection P95 {phase['com.zaxxer.hikari.HikariDataSource.getConnection']['p95']:.2f}ms（包含失败的连接ID探针开销，不等同纯连接池等待），活跃峰值{resources['hikari_active_max']}、等待采样{resources['hikari_waiting_max']}。Java平均{resources['java_cpu_cores']:.2f}核，宿主CPU {resources['host_cpu']:.1f}%，Java RSS峰值{resources['java_rss_max_mib']:.1f}MiB，宿主最少可用{resources['host_free_min_mib']:.1f}MiB。3次Young GC合计104ms，无Full GC；没有证据认为GC解释十秒排队。",'',
'JFR：入口线程753个SocketRead事件，P95 260.73ms；sale 378个，P95 187.02ms；scheduler 379个，P95 278.58ms。入口Lettuce同步Future等待可见；sale空闲栈为SynchronousQueue.take。Netty仅观察到一个13.94ms的selector monitor事件，不构成持续阻塞证据。JFR事件按开始时间筛选，跨窗park时长未裁剪，空闲线程park总和不当作业务CPU或延迟。', '',
'MySQL本窗口49次采样，17次看到等待，共40条记录、按事务/阻塞者/lockId分组38组，全部dispatch PRIMARY。38组不是38个精确独立等待事件；多数只见一次，观测跨度0表示无法测长，不表示没有等。最大重复观测跨度759ms、非实际最大等待。未捕获桶行等待不证明更高负载没有桶热点。连接ID缺失使SQL到MySQL等待精确关联不可用。', '',
'MySQL元数据前后区间（宽于29.5秒）505次COMMIT，服务端digest合计27.22线程秒、均值53.90ms；协调行锁SQL207次、均值90.91ms。不能将505次全算成请求自身提交。强持久化参数只读核对为innodb_flush_log_at_trx_commit=1、sync_binlog=1、log_bin=ON，未修改。存在提交成本，缺少waits history及容器负载，不能证明fsync或Docker磁盘饱和。', '',
'启用的P_S文件聚合还记录全局redo log file等待差34.87线程秒（1569次write、1334次misc），data file约2.39线程秒、doublewrite约2.48线程秒；它们与提交时间有包含关系且统计区间更宽、服务器共享，不可相加或全归因此批。未见Innodb_log_waits增长不能排除fsync延迟。owned schema连接采样峰值11（含测试只读连接）、执行中6；这不是容器CPU/IO利用率。', '',
'## 可视化与原始数据','',f"[请求/线程可视化](results/{root.name}/timeline.html)；[逐笔CSV](results/{root.name}/request-timeline.csv)；完整SQL、轮询（含空轮询）、线程栈及调度事件在final-probe.json；JFR为full-chain.jfr，离线流式摘要jfr-analysis.json。",'',
'生产176份源哈希核验未变；所有50笔RESERVED/HELD/WAITING_PAYMENT/COMPLETED，库存1000→950、16桶Redis=MySQL，4次重放workCount仍1。临时库/账号/Redis键已按owner清理，本轮Java进程退出，Docker生命周期操作0。']
write('FULL-CHAIN-TIMELINE.md','\n'.join(timeline))

fdoc=['# 每阶段吞吐与积压漏斗','',evidence,'',scope,'',limits,'',f"HTTP仅POST：50个202/{r['http_seconds']:.3f}秒={r['http_qps']:.3f} QPS；成功提交同值，拒绝0。四次幂等重放另列，不新增工作或库存。可靠锁单50/{window:.3f}秒={r['lock_tps']:.3f} TPS。HTTP不是可靠接纳完成指标，50请求不是稳定容量测试。",'', '## 每秒计数（相对首个成功接纳）','', '最后一桶仅0.50秒；下表为完成事件计数，不把末桶当完整一秒速率。ready是decided提交返回，Redis成功是Lua后expiry读取返回，订单提交是最终reserve已提交；SQL INSERT不计可靠提交。', '', '|秒|接纳|出内存|风控完|持久入队|claim成功|Redis成功|可靠提交|内存待取|入口处理中|待claim|已claim未提交|总未完|','|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
keys=['second','accepted','memory_dequeued','risk_completed','queued','claim_succeeded','redis_held','order_committed','memory_pending','entry_processing','durable_pending','claimed_not_committed','accepted_not_committed']
for x in funnel:fdoc.append('|'+'|'.join(str(x[k]) for k in keys)+'|')
fdoc += ['', '## 漏斗解释','',
'- 前约1.74秒全部HTTP返回，第二秒末内存队列已积压46笔。入口worker把每笔Armory/create/画像/decided全部同步完成后才继续取下一笔，平均1.403秒，不是快速handoff。最后一笔入口处理约第18.65秒结束。',
'- 持久队列在入口继续供给期间增加：秒7末9笔、秒13末18笔、秒17末25笔（这些是秒末值；原始纳秒事件另存）。即便内存队列第16秒末已取空，仍有入口任务工作，随后持久调度继续排空至29.5秒。',
'- 秒18以后不再新增QUEUED，但claim/提交呈间歇发车；秒23仍14笔待claim、该秒0次claim而发生3笔先前任务提交。结合线程时间线，等待不是sale内部长队：其queue=0、submit→start P95约5ms，调度线程忙于SQL和后台摘要。',
'- 末尾各阶段都恰好50，所以用共同29.5秒窗口算平均会全是1.69/s，不能从相同总数判定谁最慢。约束来自持续积压与服务时间：入口先形成积压；随后“持久队列→claim发车”形成第二约束。调度窗口50成功/29.5秒=1.69/s，仅其工作时间内50成功/20.17秒≈2.48/s也不是系统容量，尚含空claim/等待且排除了其他必需工作。',
'- 同一任务从易失层转为持久层，不得把两层容量简单相加为可接纳总量；EVALUATING/CLAIMED、活动名额、已追踪状态和执行中任务均有重叠。', '',
'## 5000/s HTTP与5000TPS完全不同','',
'当前POST令牌桶为每实例1000/s、初始突发1000；真实鉴权在限流之前，超限请求仍可能消耗鉴权资源。5000 HTTP总响应/s可含大量429，不能宣称5000成功提交。单实例要改成功接纳目标需先另行批准限流/资源预算，本轮未调整。保留AbortPolicy、有界队列、状态容量和每用户保护，成功execute才202；满载明确拒绝，重复requestId不新增任务。',
'202仍只代表易失收到请求，进程重启可丢失尚无库存副作用的工作；查询跨实例不以缓存miss判最终失败，有限等待后明确UNCONFIRMED并同requestId重试。进入库存阶段仍需意图先提交，Lua后失败补偿、版本fence、配额和最终可靠提交不能删。',
'仅作条件算术：若成功接纳1000/s且最终完成仍为本批1.69/s，未完成量会约增加998.31/s；若成功接纳5000/s则约4998.31/s。现实会先遇到队列/限流/期限/拒绝，以上不是容量预测，也不据此推导精确填满时间。',
'5000可靠锁单TPS在当前代码工作量不变时意味着约28万次业务SQL/s、3.5万次Java可见提交/s、15.5万次Redis高层逻辑命令/s，另有摘要/空轮询等开销。这只是56/7/31个操作的条件工作量放大，不是硬件容量要求或可达吞吐预测。需先整治串行调度/职责/往返并做独立容量验证，不能靠扩队列或换MQ宣称达标。', '',
f"原始逐秒数据：[throughput-funnel.json](results/{root.name}/throughput-funnel.json)。"]
write('THROUGHPUT-FUNNEL.md','\n'.join(fdoc))

ranking=f'''# 瓶颈排序与统一判断

{evidence}

{scope}

{limits}

## 前五项（按本轮证据与整治收益机制，不把嵌套耗时相加）

|顺序|问题|实测证据|源码边界|判断|
|---|---|---|---|---|
|1|库存调度与后台摘要共用单线程|scheduling-1后台摘要8.944秒、46次rebuild/335条SQL；占窗口30.32%；有待claim且sale未满载25.67秒|RiskHistorySummary.refreshPending/rebuild；ReservationV2Service.dispatch；默认taskScheduler|确定存在供给耦合；迁移后可节省多少尚未测|
|2|逐笔串行claim和协调行互斥|dispatch20.173秒/68.38%；61次claim、50成功；成功claim P95 739ms；create协调锁SQL P95 576ms，claim同SQL607ms；40条等待样本均PRIMARY|ReservationDispatch.create/claim：SELECT * FROM tb_reservation_dispatch WHERE id=1 FOR UPDATE|确定互斥和SQL等待；不能把全部SELECT耗时当纯行锁等待|
|3|入口4worker承担整套串行IO|worker均值1403ms/P95 3041ms；create均值530ms、风控472ms；46笔内存积压、排队P95 14.92秒|ReservationIngress.Work.run→ReservationV2Service.submit|确定职责与队头阻塞；增线程并未验证收益|
|4|多事务/提交成本与SQL往返|2800业务SQL、350业务可见提交；最终reserve P95 722ms、其commit P95 359ms；元数据区间server COMMIT均值53.9ms|TransactionTemplate/doCommit、prepare/reserve、MySQL digest|提交成本已测，IO饱和、网络、binlog具体分段仍未知|
|5|画像冷建及同步Redis往返|50次同步冷摘要重建、后台又46次；特征P95 746ms而规则P95 0.794ms；31个Redis逻辑命令/请求，库存Lua P95 106ms|RiskFeatureService.collect；RiskHistorySummary.read；Armory.runtimeGate；InventoryService.reserveRuntime|确定IO主导；不应删真实风控/三次gate；分组调用收益未测|

上述项目有父子包含关系：后台摘要包含数据库提交，入口工作包含create/风控，claim等待包含协调锁SQL。表不是可相加的独立百分比收益清单。

## 统一回答18个问题

1. **入口4线程忙什么？** 各处理13/12/13/12笔，任务总工作18.29/16.93/17.59/17.35秒。create和风控平均分别约占worker工作时间37.8%/33.6%，Armory runtimePlan约12.2%；其余是decided/get/view等。JFR显示MySQL SocketRead和Lettuce Future同步等待，不是单纯CPU计算。
2. **十余秒内存排队怎样形成？** 50请求约1.74秒收到，4线程服务平均1.403秒，短时供给远高于取队速度；队列峰值46。P95 14.92秒是本批排队，不是每笔SQL耗时。第一批冷任务更慢，不能把当前均值当固定容量。
3. **画像占入口消费者吗？** 是。classifyReservation同步调用collect；100次ready、50次同步rebuild、300次特征Lua、50次ZCOUNT及快照HSET/EXPIRE；没有Agent。画像之后还执行权威主体查询/黑名单和规则。
4. **create限制入口消费吗？** 是其直接占用部分，均值529.7ms/P95 1098.8ms。协调锁SQL均值245.8ms；锁内还进行幂等、容量、六条guard查询/插入、任务插入/get/commit。不能只移guard再增加独立提交，本轮未复用已回退候选。
5. **协调锁影响多少请求时间？** Java侧50次create锁SQL合计12.29线程秒、50次成功claim锁SQL合计6.02线程秒（均包含网络/执行/等待）；49个窗口内采样17次有等待、40记录38个锁关系组。纯InnoDB等待时间及精确受影响请求数尚无证据，因为连接ID关联缺失；不能把18.31线程秒全部视作可消除锁时间。
6. **持久等待如何分解？** ready→自身claim前均值6046.8ms中，调度线程其他dispatch占用3690.2ms、后台summary2324.1ms、监控10.1ms、reap0.14ms、其余22.3ms；这是可观测时间分区，不是因果收益。自身claim全方法均值334ms；1笔跨ready边界62.39ms，排他分区仅计ready后的332.9ms均值，不重复相加。全PASS未遇REVIEW竞争，未观察active_count>=4空claim，容量未满；固定25ms及调度延迟无法再完全拆开。
7. **sale为何没满载？** 成功claim必须由单scheduler完成SQL后逐个提交，且scheduler被后台summary占用。sale平均任务占用约29.1%，并非4线程全忙；submit→start P95仅5.10ms。
8. **积压时sale空闲吗？** 是，轨迹重建25.67秒存在尚未完成claim任务且sale活动数<4；这包含正常串行claim服务与交接，不能全部算可消除浪费。采样sale峰值3，未见active_limit拒绝。
9. **最终事务最慢SQL？** 直接SQL中桶UPDATE P95 82.35ms，Outbox INSERT46.69ms、job RESERVED UPDATE45.94ms；另有Armory共享屏障/静态SKU读取、guard当前读、order INSERT。最慢步骤还包括commit P95 358.53ms，不能只按SQL字符串优化。
10. **事务内网络调用？** 是，三次Armory门禁各有Redis读取；最终事务持有Armory/job/intent/风险锁时查黑名单、执行库存Lua并HGET期限。它们保护版本、安全限制与补偿，不能直接移到事务外。跨系统往返可设计合并，但必须保持原不变量。
11. **其他MySQL热点/提交瓶颈？** 观察到的行锁等待只在dispatch PRIMARY，未捕获桶等待。元数据区间COMMIT 505次/均值53.9ms，证明提交代价；不证明磁盘饱和。EXPLAIN已保存：小数据走对应索引/范围，不能代表大历史表。events_waits disabled，容器IO缺失，未开启高开销采样。
12. **CPU/GC/IO限制？** Java平均0.99核、宿主57.7%，Young GC104ms/无Full GC；不支持CPU或GC解释全部29.5秒。可用内存最低391MiB，存在资源压力背景。服务端COMMIT慢且强持久化，但没有IO饱和或网络分段证据。不能据此建议关闭持久化或购买云服务器。
13. **哪阶段吞吐最低？** 同窗口各环节最终都50不能直接比总平均。时间序列先在入口积压，再在ready→claim形成持续积压（秒17末约25笔）；此批控制面完成供给约1.69/s，是可见约束，非稳定容量极限。
14. **串行/并行边界？** MySQL协调行、单scheduler逐笔claim、每请求IO序列串行；入口4与sale4已并行。不同用户风险和后台摘要可有界并行，但共享主体、事件水位、全局容量/公平性及同任务lease必须保持。库存最终事务不能为并行拆散其原子性。
15. **两层队列必要吗？** 易失接收与持久优先级/恢复职责不同，当前有两次真实等待。可以重划分职责或更换持久分发实现，不能直接删除一个队列并丢失容量/公平性/恢复；也不能误称未预占请求必须逐条可靠接纳。
16. **各类改造作用？** 大队列只增加缓冲与延迟；增线程可能加重协调/数据库竞争；SQL/批量claim可减少串行往返但不能解决共享scheduler被占据；调度隔离/唤醒能改供给但不消除最终提交；MQ可替代轮询不解决MySQL/画像/全球优先级和强容量预算。
17. **5000/s HTTP如何正确？** 当前每实例POST限流1000/s，且鉴权在限流之前。区分总响应、成功submit和429；维持有界入口、非CallerRuns及易失202语义。若要求5000成功submit/s需独立批准资源/限流预算，过载及时拒绝；原requestId重试，跨实例暂未找到不作最终失败。库存副作用后的可靠意图/事务/补偿不变。
18. **5000可靠TPS差距？** 当前低负载证据指向调度供给、协调互斥、入口IO和多次提交，而非sale线程数或库存Lua单项。按当前工作量放大将有56 SQL/7提交/31 Redis每单，不能用50请求推算机器或分片数量。需要协议整治后的稳定负载实验，不能许诺5000TPS。

## 结论边界与推荐

采用OPTIMIZATION-ROADMAP中的A优先、B分阶段、C后置方案；共享调度职责与claim协议共同设计，分开开关验证。禁止删READY/三个gate/幂等/容量/库存安全，不先扩队列/线程/Hikari、不关持久化、不重写Lua、不把RocketMQ当吞吐保证。

本轮没有优化代码，也没有第二轮业务负载；所有原始数据、异常预检查证据及清理记录保留。
'''
write('BOTTLENECK-RANKING.md',ranking)

# Standalone engineering timeline: no external assets/libraries, hover shows exact intervals.
width=1400;left=145;scale=(width-left-20)/window
def bar(label,begin,finish,y,color,tip):
    x=left+(begin-t0)/1e9*scale;w=max(.3,(finish-begin)/1e9*scale)
    return f'<rect x="{x:.2f}" y="{y}" width="{w:.2f}" height="13" fill="{color}"><title>{html.escape(tip)}</title></rect>'
svg=[f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="1120" viewBox="0 0 {width} 1120"><style>text{{font:11px monospace}}rect{{opacity:.85}}rect:hover{{opacity:1;stroke:#111}}</style>']
for second in range(math.ceil(window)+1):
    x=left+second*scale;svg += [f'<line x1="{x}" x2="{x}" y1="20" y2="1110" stroke="#ddd"/>',f'<text x="{x}" y="14">{second}s</text>']
for i,x in enumerate(ordered):
    y=30+i*16;svg.append(f'<text x="2" y="{y+11}">req {x["requestId"][len(prefix):]}</text>')
    for begin,finish,color,kind in [(x['enqueue'],x['consumerStart'],'#aaa','memory wait'),(x['consumerStart'],x['queued_ready_ns'],'#3182bd','entry to QUEUED'),(x['queued_ready_ns'],x['saleStart'],'#fd8d3c','durable/claim/dispatch'),(x['saleStart'],x['locked'],'#31a354','sale reliable lock')]:
        svg.append(bar('',begin,finish,y,color,x['requestId']+' '+kind+f' {(finish-begin)/1e6:.3f}ms'))
tasks=read('thread-task-timeline.json');names=sorted(set(x['thread'] for x in tasks));ybase=860
for i,name in enumerate(names):
    y=ybase+i*25;svg.append(f'<text x="2" y="{y+11}">{html.escape(name)}</text>')
    for x in tasks:
        if x['thread']!=name or x['end']<=t0 or x['start']>=tend:continue
        color='#fd8d3c' if 'refreshPending' in x['kind'] else '#9e9ac8' if 'Snapshot' in x['kind'] else '#31a354' if x['kind']=='sale_work' else '#3182bd'
        svg.append(bar('',max(t0,x['start']),min(tend,x['end']),y,color,x['kind']+' '+x['requestId']+f' {(x["end"]-x["start"])/1e6:.2f}ms'))
svg.append('</svg>');image=''.join(svg);(root/'timeline.svg').write_text(image,encoding='utf-8');(root/'timeline.html').write_text('<!doctype html><meta charset="utf-8"><title>Full chain single batch</title><style>body{font:15px system-ui;margin:24px}svg{max-width:100%;height:auto}</style><h1>50 requests / one batch</h1><p>灰：内存等待；蓝：入口工作；橙：持久等待/claim；绿：sale→可靠提交。下方线程：蓝dispatch，橙后台摘要。悬停显示原始区间；单位秒。包含诊断开销，不是容量测试。</p>'+image,encoding='utf-8')
print('Reports and standalone timeline generated from saved data only')
