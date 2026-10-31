# 瓶颈排序与统一判断

本轮原始目录：`E:\credit_risk\performance\results\full-chain-20261029_231023_4e66`；HEAD `578005dab2e453e0333c536d4224afca65489382`，有未提交开发成果，以version.json/source-index哈希定位实际版本。

本轮只发送一次50个唯一有效请求，最大发送并发10，另4次相同requestId重试；未进行抢票预热。第一次装配预检查失败发生在任何抢票POST之前，已核实0 job/order/intent并清理，不属于第二个业务批次。真实Armory/规则/Redis预占/MySQL16桶及订单事务；支付、出票、MQ发布和付费Agent不运行。

更完整埋点/JFR/100ms线程采样有未校准开销；历史50档已被前面的1/10/30档预热，本轮单50档是冷启动，不能用1.69对比2.30宣称回归或稳定容量。JDBC连接ID提取失败（测量结束1152次miss），不能把MySQL等待精确分配到每笔请求；Docker stats记录为空，容器CPU/RAM/BlockIO缺失；events_waits consumers未启用，未更改设置，不能证明fsync/磁盘已饱和。

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
