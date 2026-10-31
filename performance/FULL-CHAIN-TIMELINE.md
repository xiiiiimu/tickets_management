# 全链路时间线：唯一50请求批次

本轮原始目录：`E:\credit_risk\performance\results\full-chain-20261029_231023_4e66`；HEAD `578005dab2e453e0333c536d4224afca65489382`，有未提交开发成果，以version.json/source-index哈希定位实际版本。

本轮只发送一次50个唯一有效请求，最大发送并发10，另4次相同requestId重试；未进行抢票预热。第一次装配预检查失败发生在任何抢票POST之前，已核实0 job/order/intent并清理，不属于第二个业务批次。真实Armory/规则/Redis预占/MySQL16桶及订单事务；支付、出票、MQ发布和付费Agent不运行。

更完整埋点/JFR/100ms线程采样有未校准开销；历史50档已被前面的1/10/30档预热，本轮单50档是冷启动，不能用1.69对比2.30宣称回归或稳定容量。JDBC连接ID提取失败（测量结束1152次miss），不能把MySQL等待精确分配到每笔请求；Docker stats记录为空，容器CPU/RAM/BlockIO缺失；events_waits consumers未启用，未更改设置，不能证明fsync/磁盘已饱和。

## 时钟与边界

同一诊断JVM所有阶段使用System.nanoTime。快照同时带nano/wallMillis，MySQL采样用Windows epoch；clock-anchors.json及mysql-before/after的server_epoch提供校准。Java wall与nano成对，客户端client_wall在元数据采集后记录，不当作网络到达时间；未同步的毫秒级差不能解释为锁获得时刻。HTTP T1从最外层测试Filter开始到响应返回，不含此前TCP/Tomcat接入等待；客户端http_ms另存原始请求。参数绑定/Filter残余没有单独探针，不能自动归因。

成功入队时间取原execute调用入口（已确认execute成功）；不是内部queue.offer的精确时间，核心线程首任务可直接派发。ready取decided事务doCommit返回（可见性上界），不是SQL设置queued_at的瞬间。锁单终点为最终reserve事务已提交、方法返回；HELD/WAITING_PAYMENT/COMPLETED/RESERVED联合核验，不以order INSERT或Lua返回为成功。

## 阶段分位数（ms；包含关系不能相加）

|阶段|P50|P95|P99|均值|
|---|---:|---:|---:|---:|
|服务端HTTP T1|176.166|568.751|848.870|267.369|
|入口排队T2|8892.469|14918.601|15308.644|8572.197|
|入口worker实际工作|1262.352|3040.653|3309.478|1403.120|
|QUEUED commit→自身claim开始|6099.144|10336.806|10986.020|6046.816|
|自身claim方法/事务|197.989|739.426|811.239|334.131|
|claim返回→sale提交|0.048|0.225|1.478|0.089|
|sale提交→开始|0.343|5.095|21.529|1.338|
|sale开始→可靠锁单|652.210|1357.184|2067.450|687.669|
|成功接纳→可靠锁单|18404.905|27218.687|28127.579|17013.933|

T3（消费者开始→锁单，含持久队列）P95 12640.774ms；整批首接纳→最后锁单 29.500秒；50/窗口=1.695 TPS，不是系统容量。HTTP客户端POST窗口 1.735秒、28.818 QPS，50个202，无429/4xx/5xx。

身份验证preHandle P95 511.60ms；Controller P95 88.55ms；限流桶方法体及HGETALL/PEXPIRE、三次Armory、create/features/rules/intent/reserve均有每笔start_ns/end_ns和耗时列，见CSV。桶synchronized入口等待不包含在方法体Advice内，须结合JFR，不能以方法体短证明无监视器竞争。

## 50笔逐请求

统一traceId=requestId；完整前缀 `r-ld-20261029_231023_4e66-50-`，下表尾号补到前缀后即完整ID。精确纳秒边界及全部阶段见 `full-chain-20261029_231023_4e66/request-timeline.csv`、request-timeline.json、50-raw.json。

|尾号|HTTP|内存等候|入口工作|持久至sale|自身claim|sale池等候|sale至锁单|端到端|入口/sale线程|
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
|5|152.5|355.5|3040.7|263.8|170.5|2.3|813.7|4435.8|sale-ingress-1/sale-2|
|0|165.8|348.9|3152.4|872.0|723.8|2.3|335.9|4680.3|sale-ingress-2/sale-3|
|1|162.7|348.5|2980.3|143.8|183.2|21.5|891.9|4339.2|sale-ingress-3/sale-1|
|9|122.8|340.3|3309.5|858.2|144.0|1.8|799.3|5282.4|sale-ingress-4/sale-4|
|3|178.3|3317.8|1086.6|1166.2|507.5|0.7|271.6|5831.7|sale-ingress-3/sale-4|
|7|166.1|3381.3|1047.1|1301.9|158.9|0.2|704.5|6423.1|sale-ingress-1/sale-3|
|6|173.6|3481.2|1140.6|1287.3|151.5|0.9|759.7|6623.4|sale-ingress-2/sale-4|
|4|169.6|3629.6|1452.7|1532.9|738.5|0.5|652.2|7249.2|sale-ingress-4/sale-3|
|8|172.2|4389.2|934.7|2662.7|603.5|0.2|690.4|8625.2|sale-ingress-3/sale-3|
|2|211.1|4402.9|1262.4|3036.7|767.5|0.6|758.2|9450.4|sale-ingress-1/sale-3|
|16|350.3|4249.4|1150.2|3687.5|739.4|0.5|772.6|9850.6|sale-ingress-2/sale-4|
|11|370.5|4714.2|920.6|4028.5|561.7|0.2|657.9|10296.4|sale-ingress-4/sale-3|
|14|367.6|4957.0|1222.1|4447.0|224.7|7.9|590.6|11198.3|sale-ingress-3/sale-3|
|15|339.6|5308.5|1457.3|4454.2|603.4|4.4|742.9|11950.2|sale-ingress-1/sale-3|
|13|127.3|5300.1|1456.1|4972.1|609.7|0.3|697.6|12413.9|sale-ingress-2/sale-4|
|23|62.2|5530.2|1368.6|5361.9|535.1|0.3|671.0|12915.9|sale-ingress-4/sale-3|
|22|69.3|6074.7|1031.2|6102.0|126.1|0.3|587.0|13766.6|sale-ingress-3/sale-3|
|20|104.1|6654.6|871.6|6140.3|452.9|0.2|368.0|13993.1|sale-ingress-1/sale-4|
|26|27.8|6704.7|1276.3|6091.8|383.9|0.4|555.7|14528.0|sale-ingress-2/sale-4|
|24|27.0|6853.6|1854.8|5711.6|397.9|0.2|598.6|14968.6|sale-ingress-4/sale-3|
|25|25.6|7048.7|1902.3|6190.8|91.4|0.2|630.9|15752.2|sale-ingress-3/sale-3|
|10|583.5|7458.4|1957.4|5890.3|182.2|0.5|963.5|16249.5|sale-ingress-1/sale-4|
|12|561.9|7947.7|1641.5|6238.4|530.8|0.3|623.3|16437.7|sale-ingress-2/sale-3|
|17|525.7|8673.5|1447.5|6334.4|627.4|0.5|720.0|17160.2|sale-ingress-4/sale-3|
|18|561.0|8892.5|1249.8|7850.2|198.0|0.3|559.3|18541.8|sale-ingress-3/sale-3|
|27|348.7|9328.9|860.2|7909.8|148.0|0.3|485.5|18569.9|sale-ingress-1/sale-4|
|28|430.7|9290.7|1140.0|7739.7|244.7|0.4|336.8|18452.0|sale-ingress-2/sale-2|
|21|538.0|9715.3|926.2|7544.0|132.4|0.6|266.0|18404.9|sale-ingress-4/sale-1|
|19|848.9|9770.8|1407.5|7113.2|87.6|0.2|359.6|18615.5|sale-ingress-3/sale-4|
|31|433.2|9797.1|1422.1|7082.7|102.1|0.2|696.4|18987.7|sale-ingress-1/sale-2|
|29|568.8|10252.9|1488.6|6680.8|89.4|0.6|685.6|19066.7|sale-ingress-2/sale-1|
|30|506.4|10544.3|1868.9|6208.0|241.6|0.4|587.8|19182.1|sale-ingress-4/sale-4|
|32|540.1|11054.2|1361.6|7320.1|88.4|0.3|1507.4|21232.8|sale-ingress-3/sale-4|
|34|490.7|11158.3|1354.6|7331.2|110.3|0.5|2067.4|21899.9|sale-ingress-1/sale-1|
|33|516.5|11652.9|905.1|8068.1|811.2|0.2|1357.2|21971.0|sale-ingress-2/sale-2|
|35|199.9|12311.9|747.7|8225.6|698.8|0.2|1279.8|22553.5|sale-ingress-4/sale-4|
|37|186.2|12340.5|851.6|10064.1|656.2|0.2|292.9|23536.2|sale-ingress-3/sale-4|
|36|208.0|12426.9|1271.8|9703.1|141.2|3.0|226.1|23599.1|sale-ingress-1/sale-2|
|38|177.1|12499.4|1261.3|9710.5|89.7|0.3|705.5|24163.2|sale-ingress-2/sale-1|
|40|101.3|13013.3|1127.8|9532.0|72.7|0.3|773.3|24277.2|sale-ingress-4/sale-4|
|39|131.5|13147.3|1208.1|9322.8|123.9|0.5|1134.7|24802.1|sale-ingress-3/sale-2|
|41|95.2|13647.8|833.9|9719.8|541.8|3.8|650.2|24842.6|sale-ingress-1/sale-1|
|42|106.4|13702.0|811.8|9781.6|93.8|0.2|600.7|24873.4|sale-ingress-2/sale-4|
|43|93.1|14105.1|917.6|9757.9|498.9|0.2|342.8|25110.6|sale-ingress-4/sale-2|
|47|89.0|14279.9|977.1|10673.9|141.2|5.1|534.0|26370.3|sale-ingress-3/sale-2|
|44|175.1|14380.0|1108.4|10628.1|318.9|0.3|697.7|26801.4|sale-ingress-1/sale-4|
|46|172.8|14407.3|1502.7|10351.9|114.7|1.0|921.0|27125.2|sale-ingress-2/sale-1|
|48|157.5|14918.6|1409.6|10480.6|589.0|0.2|427.6|27218.7|sale-ingress-4/sale-4|
|45|176.2|15192.8|1372.6|11070.7|84.4|0.2|560.1|28127.6|sale-ingress-3/sale-4|
|49|299.2|15308.6|1805.1|10409.7|72.7|0.2|471.2|27949.4|sale-ingress-1/sale-1|

## 线程时间线

|线程/任务|窗口内工作秒|窗口占比|
|---|---:|---:|
|sale-1 / sale_work|6.659|22.57%|
|sale-2 / sale_work|5.442|18.45%|
|sale-3 / sale_work|9.523|32.28%|
|sale-4 / sale_work|12.761|43.26%|
|sale-ingress-1 / ingress_work|18.292|62.01%|
|sale-ingress-2 / ingress_work|16.927|57.38%|
|sale-ingress-3 / ingress_work|17.585|59.61%|
|sale-ingress-4 / ingress_work|17.352|58.82%|
|scheduling-1 / PlatformMetrics.refreshSnapshot|0.042|0.14%|
|scheduling-1 / ReservationIngress.reap|0.001|0.00%|
|scheduling-1 / ReservationV2Service.dispatch|20.173|68.38%|
|scheduling-1 / RiskHistorySummary.refreshPending|8.944|30.32%|

sale四线程平均任务占用约29.1%；入口四线程在前约18.65秒承担持续IO，之后退出本批工作，不是全29.5秒都满载。

同一个scheduling-1在本窗口：dispatch 20.173秒（68.38%），后台摘要8.944秒（30.32%，46次rebuild/335条SQL），监控42.5ms，reap 0.88ms。dispatch每轮P95 1973.96ms，最大2673.69ms；配置25ms只是轮后延迟。

按逐请求区间重建，有待完成claim且sale活动数<4的时间 25.673秒；其中部分是正常claim执行/交接，不全可消除。精确区间见pending-with-spare-sale.json。采样sale峰值3，轨迹任务占用按4线程分母；没有证据表明增加sale线程能解决供给问题。

## 持久等待的可观测分区

逐笔ready→自身claim开始的均值6046.8ms，其中与scheduler执行其他dispatch轮次重叠3690.2ms、后台summary重叠2324.1ms、监控10.1ms、reap0.14ms、其余未分配22.3ms。以上来自每笔不交叠时间区间；是调度线程当时做什么，**不是删掉这些工作就能等量减少延迟的因果收益预测**。自身claim全方法均值334.1ms另列，sale提交/排队均为毫秒级。1笔claim扫描在该任务QUEUED提交返回前62.39ms已经开始（RC后续语句仍可读到新任务）；排他分区使用claim_after_ready_ms（均值332.9ms），不能重复加入这62.39ms。逐笔核对ready→sale=preclaim+claim_after_ready+handoff+pool_wait。

61次claim尝试：50成功，11无可领取/被跳过；本轮没有active_count>=4拒绝的观察，全部PASS，没有REVIEW加权竞争；pending容量最多50<512，容量满拒绝为0。不能把不可见的global limit/priority等待凭空分配秒数。精确锁等待不可由Java SELECT墙钟推导。

## SQL、锁与资源

2800次去除Db/Jdbc嵌套重复后的业务SQL（56/请求），350次Java可见提交（7/请求，包括为该请求成功claim的提交）。后台摘要SQL另计335，空claim、监控及框架会话SQL另计；业务SQL计数不含驱动自动生成的SET/事务隔离查询。Redis高层逻辑命令1550（31/请求），库存脚本50、特征脚本300；不是350次库存扣减。

|最终reserve直接SQL（非纯服务端执行）|次数|均值ms|P95ms|
|---|---:|---:|---:|
|`UPDATE tb_ticket_stock_bucket SET remaining_stock=remaining_stock-?,version=version+1 WHERE sku_id=? AND bucket_id=? AND remaining_stock>=? AND EXISTS(SELECT 1 FROM tb_ticket_sku s WHERE s.id=? AND s.status='ON_SALE' AND s.sale_start_time<=CURRENT_TIMESTAMP AND s.sale_end_time>CURRENT_TIMESTAMP)`|50|18.83|82.35|
|`INSERT INTO tb_outbox_message(id,aggregate_type,aggregate_id,topic,message_key,payload,status) VALUES(?,'ORDER',?,'ticket-events',?,?,'PENDING')`|50|15.15|46.69|
|`UPDATE tb_reservation_job SET state='RESERVED',result_code='RESERVED',lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?`|50|14.85|45.94|
|`UPDATE tb_reserve_request SET status='COMPLETED' WHERE request_id=?`|50|14.18|37.84|
|`INSERT INTO tb_reservation(id,request_id,user_id,sku_id,quantity,status,expires_at) VALUES(?,?,?,?,?,'HELD',?)`|50|10.40|33.47|
|`UPDATE tb_user_purchase SET quantity=quantity+? WHERE user_id=? AND sku_id=? AND quantity+?<=?`|50|13.75|26.54|
|`SELECT * FROM tb_reserve_request WHERE request_id=? FOR UPDATE`|50|7.98|22.98|
|`INSERT INTO tb_stock_bucket_allocation(reservation_id,request_id,sku_id,bucket_id,quantity,released) VALUES(?,?,?,?,?,false)`|50|8.39|22.37|
|`SELECT * FROM tb_reservation_job WHERE id=? FOR UPDATE`|50|8.97|19.92|
|`INSERT INTO tb_user_purchase(user_id,sku_id,quantity) VALUES(?,?,0) ON DUPLICATE KEY UPDATE user_id=user_id`|50|11.24|19.28|

Hikari getConnection P95 4.15ms（包含失败的连接ID探针开销，不等同纯连接池等待），活跃峰值7、等待采样0。Java平均0.99核，宿主CPU 57.7%，Java RSS峰值425.7MiB，宿主最少可用391.3MiB。3次Young GC合计104ms，无Full GC；没有证据认为GC解释十秒排队。

JFR：入口线程753个SocketRead事件，P95 260.73ms；sale 378个，P95 187.02ms；scheduler 379个，P95 278.58ms。入口Lettuce同步Future等待可见；sale空闲栈为SynchronousQueue.take。Netty仅观察到一个13.94ms的selector monitor事件，不构成持续阻塞证据。JFR事件按开始时间筛选，跨窗park时长未裁剪，空闲线程park总和不当作业务CPU或延迟。

MySQL本窗口49次采样，17次看到等待，共40条记录、按事务/阻塞者/lockId分组38组，全部dispatch PRIMARY。38组不是38个精确独立等待事件；多数只见一次，观测跨度0表示无法测长，不表示没有等。最大重复观测跨度759ms、非实际最大等待。未捕获桶行等待不证明更高负载没有桶热点。连接ID缺失使SQL到MySQL等待精确关联不可用。

MySQL元数据前后区间（宽于29.5秒）505次COMMIT，服务端digest合计27.22线程秒、均值53.90ms；协调行锁SQL207次、均值90.91ms。不能将505次全算成请求自身提交。强持久化参数只读核对为innodb_flush_log_at_trx_commit=1、sync_binlog=1、log_bin=ON，未修改。存在提交成本，缺少waits history及容器负载，不能证明fsync或Docker磁盘饱和。

启用的P_S文件聚合还记录全局redo log file等待差34.87线程秒（1569次write、1334次misc），data file约2.39线程秒、doublewrite约2.48线程秒；它们与提交时间有包含关系且统计区间更宽、服务器共享，不可相加或全归因此批。未见Innodb_log_waits增长不能排除fsync延迟。owned schema连接采样峰值11（含测试只读连接）、执行中6；这不是容器CPU/IO利用率。

## 可视化与原始数据

[请求/线程可视化](results/full-chain-20261029_231023_4e66/timeline.html)；[逐笔CSV](results/full-chain-20261029_231023_4e66/request-timeline.csv)；完整SQL、轮询（含空轮询）、线程栈及调度事件在final-probe.json；JFR为full-chain.jfr，离线流式摘要jfr-analysis.json。

生产176份源哈希核验未变；所有50笔RESERVED/HELD/WAITING_PAYMENT/COMPLETED，库存1000→950、16桶Redis=MySQL，4次重放workCount仍1。临时库/账号/Redis键已按owner清理，本轮Java进程退出，Docker生命周期操作0。
