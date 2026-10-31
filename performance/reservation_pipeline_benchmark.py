"""Manual isolated A/B/C benchmark. No DROP, DELETE, FLUSH or global SQL-stat resets.
All raw requests, timings, SQL digests and inventory proofs are retained under results/.
Requires explicitly started project MySQL, v2 Redis; C additionally requires bench MQ.
"""
import argparse, collections, concurrent.futures, hashlib, json, math, os
from http.client import HTTPConnection
import pathlib, secrets, shutil, socket, statistics, subprocess, threading, time
from datetime import datetime

ROOT=pathlib.Path(__file__).resolve().parents[1]
JAVA=pathlib.Path(r'C:\Program Files\Java\jdk-21.0.11\bin\java.exe')
ENV=dict(line.split('=',1) for line in (ROOT/'docker/credit_risk/.env').read_text(encoding='utf-8-sig').splitlines() if line and not line.startswith('#') and '=' in line)
STAMP=datetime.now().strftime('%Y%m%d_%H%M%S')
OUT=ROOT/'performance/results'/('reservation-v2-'+STAMP)
OUT.mkdir(parents=True)
OBSERVATION='poll'

def save(name,obj):
    (OUT/name).write_text(json.dumps(obj,ensure_ascii=False,indent=2),encoding='utf-8')
def docker(*args,stdin=None):
    p=subprocess.run(['docker',*args],input=stdin,capture_output=True,encoding='utf-8',errors='replace',timeout=120)
    if p.returncode:raise RuntimeError(p.stderr[-1500:])
    return p.stdout.strip()
def sql(db,statement):
    assert not db or db.startswith('credit_risk_v2_bench_')
    return docker('exec','-i','credit-risk-dev-mysql','sh','-c','exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -N -B '+db,stdin=statement)
def redis(index,commands):
    return docker('exec','-i','credit-risk-v2-redis','sh','-c','export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --no-auth-warning --raw -n '+str(index),stdin=commands+'\n')
def http(port,path,data=None,token=None,source=None,method=None):
    conn=HTTPConnection('127.0.0.1',port,timeout=120,source_address=(source,0) if source else None)
    try:
        headers={'Content-Type':'application/json'}
        if token:headers['authorization']=token
        conn.request(method or ('POST' if data is not None else 'GET'),path,None if data is None else json.dumps(data),headers)
        r=conn.getresponse();body=r.read().decode()
        try:body=json.loads(body)
        except ValueError:pass
        return r.status,body
    finally:conn.close()
def percentiles(values):
    a=sorted(values)
    return {f'p{p}':a[math.ceil(len(a)*p/100)-1] for p in (50,95,99)} if a else {}
def counter(wire,name):
    return sum(float(line.rsplit(' ',1)[1]) for line in wire.splitlines() if line.startswith(name+' ') or line.startswith(name+'{'))
def free_port(port):
    with socket.socket() as s:s.bind(('127.0.0.1',port))
def source(i):return f'127.{1+i//60000}.{(i//250)%240+1}.{i%250+1}'

class App:
    def __init__(self,label,port,kind,background=False):
        self.label,self.port,self.kind,self.background=label,port,kind,background
        self.db='credit_risk_v2_bench_'+STAMP+'_'+label
        self.user='crpb_'+STAMP.replace('_','')+label[:3]
        self.password=secrets.token_hex(20)
        free_port(port)
        # Probe inside one owned container invocation: repeated Docker CLI startup can dominate setup.
        # A per-DB Lua claim is atomic and only writes to an entirely empty logical database.
        owner=STAMP+'-'+label
        claim="if redis.call('DBSIZE')==0 then redis.call('SET',KEYS[1],ARGV[1]); return 1 else return 0 end"
        import shlex
        command='export REDISCLI_AUTH="$REDIS_PASSWORD"; for i in $(seq 1 255); do claimed=$(redis-cli --no-auth-warning --raw -n "$i" EVAL '+shlex.quote(claim)+' 1 credit-risk:benchmark-owner '+shlex.quote(owner)+'); if [ "$claimed" = "1" ]; then printf "%s" "$i"; exit 0; fi; done; exit 1'
        self.rdb=int(docker('exec','credit-risk-v2-redis','sh','-c',command))
        sql('',f"CREATE DATABASE {self.db} CHARACTER SET utf8mb4; CREATE USER '{self.user}'@'%' IDENTIFIED BY '{self.password}'; GRANT ALL ON {self.db}.* TO '{self.user}'@'%';")
        # Additive, fresh schema only; no migration is applied to developer databases.
        sql(self.db,(ROOT/'ticket-risk-platform/src/main/resources/schema.sql').read_text(encoding='utf-8'))
        sql(self.db,(ROOT/'ticket-risk-platform/src/main/resources/data.sql').read_text(encoding='utf-8'))
        redis(self.rdb,'HSET login:token:bench-admin id 1 role ADMIN nickName benchmark')
        (OUT/(label+'.env')).write_text(f'DB={self.db}\nDB_USER={self.user}\nDB_PASSWORD={self.password}\nREDIS_DB={self.rdb}\n',encoding='utf-8')
        env=os.environ.copy();env.update(SPRING_DATASOURCE_URL=f'jdbc:mysql://127.0.0.1:13306/{self.db}?useSSL=false&serverTimezone=UTC&useLegacyDatetimeCode=false&characterEncoding=utf8&allowPublicKeyRetrieval=true',SPRING_DATASOURCE_USERNAME=self.user,SPRING_DATASOURCE_PASSWORD=self.password,
            SPRING_REDIS_HOST='127.0.0.1',SPRING_REDIS_PORT='26379',SPRING_REDIS_DATABASE=str(self.rdb),SPRING_REDIS_PASSWORD=ENV['CREDIT_RISK_REDIS_PASSWORD'],ROCKETMQ_NAME_SERVER='127.0.0.1:22976')
        args=[str(JAVA),'-Xms128m','-Xmx384m','-XX:+UseSerialGC','-Dspring.jdbc.getParameterType.ignore=true','-Duser.timezone=UTC']
        if kind=='A':
            cp=(ROOT/'ticket-risk-platform/target/benchmark-classpath.txt').read_text(encoding='utf-8-sig').strip()
            missing=[path for path in cp.split(os.pathsep) if not pathlib.Path(path).is_file()]
            if missing:raise RuntimeError('Benchmark dependency classpath has missing files: '+str(missing[:3]))
            launcher=OUT/'inventory-launcher-classes'
            shutil.copytree(ROOT/'ticket-risk-platform/target/test-classes/benchmark',launcher/'benchmark')
            args+=['-cp',str(launcher)+os.pathsep+str(ROOT/'ticket-risk-platform/target/classes')+os.pathsep+cp,'benchmark.InventoryOnlyApplication']
            artifact=dict(mode='isolated test launcher',launcher_sha256=hashlib.sha256((ROOT/'ticket-risk-platform/target/test-classes/benchmark/InventoryOnlyApplication.class').read_bytes()).hexdigest())
        else:
            jar=ROOT/('.tmp/architecture-rebuild-20261028/baseline-built.jar' if kind=='old' else 'ticket-risk-platform/target/ticket-risk-platform-1.0.0-SNAPSHOT.jar')
            args+=['-jar',str(jar)]
            artifact=dict(jar=str(jar.relative_to(ROOT)),sha256=hashlib.sha256(jar.read_bytes()).hexdigest())
        save(label+'-artifact.json',artifact)
        args+=['--server.address=127.0.0.1',f'--server.port={port}','--spring.datasource.hikari.maximum-pool-size=10','--spring.datasource.initialization-mode=never','--ticketing.inventory.bucket-count=16','--ticketing.sale.armory.enabled=true',
            '--ticketing.jobs.enabled='+str(background).lower(),'--ticketing.mq.enabled='+str(background).lower(),'--ticketing.metrics.snapshot.enabled=false','--ticketing.perf-log.enabled=true','--ticketing.perf-log.pool-interval-ms=200']
        if kind!='old':args+=['--spring.profiles.active=reservations-v2','--ticketing.reservations-v2.queue-timeout-ms=120000']
        if kind=='A':args+=['--ticketing.risk.summary.enabled=false']
        if background:args+=['--ticketing.agent.url=http://127.0.0.1:28090','--ticketing.agent.key=controlled-benchmark-only']
        self.log=(OUT/(label+'.stdout.log')).open('w',encoding='utf-8');self.err=(OUT/(label+'.stderr.log')).open('w',encoding='utf-8')
        self.proc=subprocess.Popen(args,cwd=ROOT/'ticket-risk-platform',env=env,stdout=self.log,stderr=self.err,creationflags=subprocess.CREATE_NO_WINDOW)
        self.counter=0
        try:
            end=time.monotonic()+180
            while time.monotonic()<end:
                if self.proc.poll() is not None:raise RuntimeError('Java exited: '+label)
                try:
                    if http(port,'/actuator/health')[1].get('status')=='UP':break
                except (OSError,ValueError):pass
                time.sleep(1)
            else:raise RuntimeError('Java health timeout: '+label)
        except BaseException:self.close();raise
    def close(self):
        if self.proc.poll() is None:
            self.proc.terminate()
            try:self.proc.wait(timeout=30)
            except subprocess.TimeoutExpired:self.proc.kill();self.proc.wait(timeout=10)
        self.log.close();self.err.close()
    def digest(self):
        return [json.loads(line) for line in sql('',"SELECT JSON_OBJECT('sql',DIGEST_TEXT,'count',COUNT_STAR,'ms',SUM_TIMER_WAIT/1000000000,'lock_ms',SUM_LOCK_TIME/1000000000,'rows',SUM_ROWS_EXAMINED) FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='"+self.db+"';").splitlines() if line]
    def monitoring_probe(self):
        before=self.digest();latencies=[]
        for _ in range(10):
            begin=time.perf_counter();code,body=http(self.port,'/actuator/prometheus');assert code==200
            latencies.append(1000*(time.perf_counter()-begin))
        save(self.label+'-monitoring-probe.json',dict(scrapes=10,http_ms=percentiles(latencies),mean_ms=statistics.mean(latencies),sql_before=before,sql_after=self.digest(),note='Idle endpoint cost; periodic background SQL is recorded separately, not attributed to scrapes. Snapshot sampler disabled equally in all benchmark modes.'))
    def fixture(self,label,mixed):
        # Each round has new users, request IDs, profiles, SKU and 1000 stock. No sold-out fast path.
        self.counter+=1;base=100000+self.counter*1000
        sku=self.label+'-'+label;session=sku+'-s';start=int(time.time())+20
        sql(self.db,f"INSERT INTO tb_event_session VALUES('{session}','event-1','2027-12-31 19:30:00'); INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,sale_start_time,sale_end_time,purchase_limit,status,version) VALUES('{sku}','{session}','benchmark',10,1000,1000,FROM_UNIXTIME({start}),FROM_UNIXTIME({start+7200}),2,'ON_SALE',0);"+
            'INSERT INTO tb_user(id,phone,nick_name,role,create_time) VALUES '+','.join(f"({base+i},'139{base+i:08d}','bench','USER','2025-01-01')" for i in range(160))+';'+
            'INSERT INTO tb_wallet_account(user_id,available_balance) VALUES '+','.join(f'({base+i},10000)' for i in range(160))+';')
        commands=[]
        for i in range(160):
            user=base+i;commands.append(f'HSET login:token:bench-{user} id {user} role USER nickName benchmark')
            if mixed and 96<=i<136:
                commands.append('ZADD risk:deviceAccounts:device-'+str(user)+' '+' '.join(f'{int(time.time()*1000)} seed-{j}' for j in range(6)))
        redis(self.rdb,'\n'.join(commands))
        if mixed:
            sql(self.db,'INSERT INTO tb_risk_profile(id,subject_type,subject_value,risk_level,decision,score,expires_at) VALUES '+','.join(f"('profile-{sku}-{i}','USER','{base+i}','HIGH','BLOCK',100,FROM_UNIXTIME({start+7200}))" for i in range(136,160))+';')
        status,body=http(self.port,'/api/admin/sessions/'+session+'/assembly',{'requestId':'assemble-'+sku,'expectedVersion':0},'bench-admin')
        assert status==200 and body.get('success'),(status,body)
        time.sleep(max(0,start+0.05-time.time()))
        return sku,session,base
    def request(self,i,sku,session,base):
        user=base+i;begin=time.perf_counter();token='bench-'+str(user);rid='request-'+sku+'-'+str(i)
        row={'requestId':rid,'user':user,'device':'device-'+str(user),'begin':begin}
        try:
            code,body=http(self.port,'/api/tickets/reserve' if self.kind=='old' else '/api/tickets/reservations',dict(requestId=rid,userId=user,eventSessionId=session,ticketSkuId=sku,quantity=1,deviceId=row['device']),token,source(user))
            row.update(http_status=code,response=body,http_ms=1000*(time.perf_counter()-begin),queued=code==202)
            data=body.get('data') if isinstance(body,dict) else None
            row.update(result=data,reliable_success=isinstance(data,dict) and bool(data.get('orderId')) and (self.kind=='old' or data.get('reservationSuccess') is True))
        except Exception as error:row.update(error=str(error),reliable_success=False,http_ms=row.get('http_ms',1000*(time.perf_counter()-begin)))
        row['completion_ms']=1000*(time.perf_counter()-begin);return row
    def complete(self,row):
        data=row.get('result');user=row['user']
        try:
            while self.kind!='old' and isinstance(data,dict) and data.get('state') in ('EVALUATING','QUEUED','CLAIMED'):
                time.sleep(.5);row['status_queries']=row.get('status_queries',0)+1
                status,wire=http(self.port,'/api/tickets/reservations/'+data['reservationId'],token='bench-'+str(user),source=source(user))
                if status==200 and isinstance(wire,dict) and isinstance(wire.get('data'),dict):data=wire['data']
                else:row.setdefault('poll_errors',[]).append(dict(status=status,body=wire))
                if time.perf_counter()-row['begin']>150:raise TimeoutError('reservation did not become terminal')
            row.update(result=data,reliable_success=isinstance(data,dict) and bool(data.get('orderId')) and (self.kind=='old' or data.get('reservationSuccess') is True))
        except Exception as error:row.update(error=str(error),reliable_success=False)
        if row.get('queued'):row['completion_ms']=1000*(time.perf_counter()-row['begin'])
        row.pop('begin',None);return row
    def batch(self,label,mixed=False,warm=False):
        sku,session,base=self.fixture(label,mixed);d0=self.digest()
        prom0=http(self.port,'/actuator/prometheus')[1];(OUT/(self.label+'-'+label+'-before.prom')).write_text(prom0,encoding='utf-8')
        begin=time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:rows=list(pool.map(lambda i:self.request(i,sku,session,base),range(160)))
        entry_seconds=time.perf_counter()-begin
        core_seconds=None;core_sql=None;core_prom=None
        if OBSERVATION=='counter':
            name='ticket_reserve_success_total' if self.kind=='old' else 'reservation_v2_confirmed_total'
            target=sum(bool(r.get('reliable_success') or r.get('queued')) for r in rows)
            deadline=time.perf_counter()+150
            while True:
                status,core_prom=http(self.port,'/actuator/prometheus');assert status==200
                if counter(core_prom,name)-counter(prom0,name)>=target or time.perf_counter()>=deadline:break
                time.sleep(1)
            core_seconds=time.perf_counter()-begin;core_sql=self.digest()
            (OUT/(self.label+'-'+label+'-cohort-end.prom')).write_text(core_prom,encoding='utf-8')
        status_begin=time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:rows=list(pool.map(self.complete,rows))
        status_seconds=time.perf_counter()-status_begin
        confirmation_seconds=time.perf_counter()-begin;seconds=core_seconds if core_seconds is not None else confirmation_seconds
        prom1=http(self.port,'/actuator/prometheus')[1];(OUT/(self.label+'-'+label+'-after.prom')).write_text(prom1,encoding='utf-8')
        d1=self.digest();success=sum(r['reliable_success'] for r in rows)
        # HTTP completion throughput uses entry-return times, separately from durable completion.
        result=dict(label=self.label+'-'+label,kind=self.kind,mixed=mixed,warm=warm,seconds=seconds,http_qps=160/entry_seconds,reliable_tps=success/seconds,successes=success,queued=sum(r.get('queued',False) for r in rows),http_latency_ms=percentiles([r['http_ms'] for r in rows]),completion_latency_ms=percentiles([r['completion_ms'] for r in rows]),errors=dict(collections.Counter(str(r.get('http_status'))+':'+str(r.get('response')) for r in rows if not r['reliable_success'])),requests=rows,sql_before=d0,sql_after=d1,db=self.db,redis_db=self.rdb,sku=sku)
        status_queries=sum(row.get('status_queries',0) for row in rows)
        result.update(observation=OBSERVATION,confirmation_seconds=confirmation_seconds,sql_after_core=core_sql,
            status_queries=status_queries,status_query_seconds=status_seconds,status_query_qps=status_queries/status_seconds,
            business_http_qps=(160+status_queries)/confirmation_seconds,
            http_status_counts=dict(collections.Counter(str(row.get('http_status',0)) for row in rows)),
            http_2xx_qps=sum(200<=row.get('http_status',0)<300 for row in rows)/entry_seconds)
        inventory=sql(self.db,f"SELECT bucket_id,total_stock,remaining_stock FROM tb_ticket_stock_bucket WHERE sku_id='{sku}' ORDER BY bucket_id;")
        buckets=[list(map(int,line.split())) for line in inventory.splitlines()]
        rs=list(map(int,redis(self.rdb,'MGET '+' '.join(f'ticket:{{{sku}}}:stock:bucket:{i}' for i in range(16))).splitlines()))
        persisted=int(sql(self.db,f"SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id='{sku}';"))
        result['inventory']=dict(buckets=buckets,redis=rs,persisted_orders=persisted)
        if self.kind!='old':
            stages=sql(self.db,f"SELECT JSON_OBJECT('request',request_id,'decision',decision,'state',state,'code',result_code,'evaluation_ms',TIMESTAMPDIFF(MICROSECOND,created_at,queued_at)/1000,'queue_ms',TIMESTAMPDIFF(MICROSECOND,queued_at,claimed_at)/1000) FROM tb_reservation_job WHERE sku_id='{sku}';")
            result['queue_stages']=[json.loads(line) for line in stages.splitlines() if line]
        save(result['label']+'.json',result)
        assert len(buckets)==16 and [b[2] for b in buckets]==rs and sum(rs)==1000-success and persisted==success,result['inventory']
        # Availability errors are results, not grounds for discarding a slower/errorful round.
        # The conservation check above remains mandatory; a 202 never counts as a reservation.
        assert success<=(136 if mixed else 160),(result['label'],success,result['errors'])
        if self.background:result['fulfillment']=self.fulfill(rows,sku,begin)
        save(result['label']+'.json',result)
        print('RESULT',result['label'],round(result['reliable_tps'],3),result['successes'],flush=True)
        return result
    def fulfill(self,rows,sku,begin):
        successful=[r for r in rows if r['reliable_success']]
        def pay(row):
            start=time.perf_counter();user=row['user'];order=row['result']['orderId']
            status,body=http(self.port,'/api/tickets/orders/'+order+'/pay',dict(paymentRequestId='pay-'+row['requestId'],deviceId=row['device']),'bench-'+str(user),source(user))
            return dict(order=order,status=status,body=body,ms=1000*(time.perf_counter()-start))
        payment_begin=time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:payments=list(pool.map(pay,successful))
        payment_seconds=time.perf_counter()-payment_begin
        save(self.label+'-'+sku+'-payments.json',payments)
        assert all(p['status']==200 and p['body'].get('data',{}).get('status')=='SUCCESS' for p in payments),payments
        deadline=time.monotonic()+240;samples=[]
        while time.monotonic()<deadline:
            counts=sql(self.db,f"SELECT (SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id='{sku}' AND status='ISSUED'),(SELECT COUNT(*) FROM tb_order_notification n JOIN tb_ticket_order o ON o.id=n.order_id WHERE o.sku_id='{sku}' AND n.status='AVAILABLE'),(SELECT COUNT(*) FROM tb_outbox_message WHERE status<>'SENT'),(SELECT COUNT(*) FROM tb_risk_decision d JOIN tb_ticket_order o ON o.id=d.order_id WHERE o.sku_id='{sku}' AND d.phase='POST_ISSUE');")
            issued,notices,pending,investigated=map(int,counts.split());samples.append(dict(at=time.time(),issued=issued,notices=notices,outbox_pending=pending,investigated=investigated))
            expected_review=sum(1 for row in rows if row['user']%1000 in range(96,136))
            if issued==len(successful) and notices==issued and pending==0 and investigated>=expected_review:break
            time.sleep(1)
        else:raise RuntimeError('Fulfillment did not drain: '+counts)
        seconds=time.perf_counter()-begin
        time.sleep(6) # MQ persists offsets periodically; outside the fulfillment throughput interval.
        mq=docker('exec','credit-risk-v2-bench-broker','sh','/home/rocketmq/rocketmq-4.9.7/bin/mqadmin','consumerProgress','-n','namesrv:9876','-g','ticket-events')
        (OUT/(self.label+'-'+sku+'-mq-progress.txt')).write_text(mq,encoding='utf-8')
        # Exercise actual notification delivery/acknowledgement; a toast is not counted as durable delivery.
        for row in successful:
            token='bench-'+str(row['user']);_,body=http(self.port,'/api/notifications?unread=true',token=token)
            for notice in body['data']:
                status,reply=http(self.port,'/api/notifications/'+notice['id']+'/read',{},token)
                assert status==200 and reply.get('success'),reply
        return dict(seconds=seconds,issued_tps=issued/seconds,issued=issued,notices=notices,investigated=investigated,payment_seconds=payment_seconds,payment_tps=len(payments)/payment_seconds,payment_latency_ms=percentiles([p['ms'] for p in payments]),samples=samples,agent='controlled HTTP stub, 600ms; not a live LangGraph/LLM capacity result')
    def unpaid_fixture(self):
        sku,session,base=self.fixture('timeout',False)
        row=self.complete(self.request(0,sku,session,base));assert row['reliable_success'],row
        order=row['result']['orderId'];reservation=row['result']['reservationId']
        db_expiry=int(sql(self.db,f"SELECT CAST(UNIX_TIMESTAMP(expires_at)*1000 AS UNSIGNED) FROM tb_ticket_order WHERE id='{order}';"))
        marker=f'ticket:{{{sku}}}:reservation:{reservation}'
        held=int(redis(self.rdb,'HGET '+marker+' heldAt'));expiry=int(redis(self.rdb,'HGET '+marker+' expiresAt'))
        assert db_expiry==row['result']['expiresAt']==expiry and expiry-held==600000
        save('timeout-created.json',dict(sku=sku,row=row,held_at_ms=held,database_expiry_ms=db_expiry,redis_expiry_ms=expiry))
        return sku,row
    def verify_real_timeout(self,fixture):
        sku,row=fixture;order=row['result']['orderId'];expiry=row['result']['expiresAt'];deadline=expiry/1000+120
        observations=[]
        while time.time()<deadline:
            status=sql(self.db,f"SELECT status FROM tb_ticket_order WHERE id='{order}';")
            remaining=int(sql(self.db,f"SELECT SUM(remaining_stock) FROM tb_ticket_stock_bucket WHERE sku_id='{sku}';"))
            observations.append(dict(at=time.time(),status=status,remaining=remaining))
            if status=='PAY_TIMEOUT' and remaining==1000:break
            time.sleep(5)
        else:raise RuntimeError('Actual ten-minute timeout/release failed: '+str(observations[-1]))
        stocks=list(map(int,redis(self.rdb,'MGET '+' '.join(f'ticket:{{{sku}}}:stock:bucket:{i}' for i in range(16))).splitlines()))
        assert sum(stocks)==1000,stocks
        assert int(sql(self.db,f"SELECT quantity FROM tb_user_purchase WHERE user_id={row['user']} AND sku_id='{sku}';"))==0
        reservation=row['result']['reservationId'];marker=f'ticket:{{{sku}}}:reservation:{reservation}'
        assert redis(self.rdb,'HGET '+marker+' state')=='RELEASED'
        assert redis(self.rdb,f'ZSCORE ticket:{{{sku}}}:pending '+reservation)==''
        assert sql(self.db,f"SELECT status FROM tb_reservation WHERE id='{reservation}';")=='RELEASED'
        assert sql(self.db,f"SELECT released FROM tb_stock_bucket_allocation WHERE reservation_id='{reservation}';")=='1'
        save('timeout-proof.json',dict(expiry_ms=expiry,observations=observations,redis_buckets=stocks,reservation_status='RELEASED',allocation_released=True,pending_removed=True,payment_deadline_unchanged=True,used_real_ten_minutes=True))

def main():
    global OBSERVATION
    parser=argparse.ArgumentParser();parser.add_argument('--suite',choices=['A','B','C'],default='B');parser.add_argument('--rounds',type=int,default=3);parser.add_argument('--observation',choices=['poll','counter'],default='poll');parser.add_argument('--workload',choices=['both','valid'],default='both');args=parser.parse_args();OBSERVATION=args.observation
    assert args.rounds>=3,'At least three interleaved rounds required'
    with socket.socket() as s:s.bind((source(100001),0))
    resources=docker('ps','-a','--format','{{.Names}} {{.Status}}');save('initial-resources.json',resources)
    save('method.json',dict(suite=args.suite,observation=OBSERVATION,workload=args.workload,concurrency=32,requests=160,stock=1000,rounds=args.rounds,jvm='JDK21 Xms128m Xmx384m SerialGC',pool=10,queue_timeout_ms=120000,source_ip='unique loopback per user; no threshold changes',rules_sha256=hashlib.sha256((ROOT/'ticket-risk-platform/src/main/resources/data.sql').read_bytes()).hexdigest()))
    apps=[];results=[]
    try:
        if args.suite=='B':
            apps.append(App('old',18083,'old'));apps.append(App('new',18084,'new'))
            for app in apps:
                for w in range(1):app.batch('warm'+str(w),warm=True)
            for mixed in ((False,True) if args.workload=='both' else (False,)):
                for r in range(args.rounds):
                    for app in (apps if r%2==0 else list(reversed(apps))):results.append(app.batch(('mixed' if mixed else 'valid')+str(r),mixed))
        elif args.suite=='A':
            apps=[App('inventory',18085,'A')]
            apps[0].monitoring_probe()
            for w in range(2):apps[0].batch('warm'+str(w),warm=True)
            for r in range(args.rounds):results.append(apps[0].batch('valid'+str(r)))
        else:
            from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
            class Agent(BaseHTTPRequestHandler):
                def do_POST(self):
                    payload=json.loads(self.rfile.read(int(self.headers['Content-Length'])));time.sleep(.6)
                    response=json.dumps(dict(aiAvailable=True,riskLevel='MEDIUM',scoreAdjustment=0,confidence=.9,reasons=['Controlled benchmark evidence'],suggestion='REVIEW')).encode()
                    self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(response)));self.end_headers();self.wfile.write(response)
                    with (OUT/'agent-calls.jsonl').open('a',encoding='utf-8') as log:log.write(json.dumps(dict(time=time.time(),order=payload.get('order')))+'\n')
                def log_message(self,*args):pass
            free_port(28090);server=ThreadingHTTPServer(('127.0.0.1',28090),Agent)
            thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            apps.append(App('full',18084,'new',True))
            apps[0].monitoring_probe();unpaid=apps[0].unpaid_fixture()
            for w in range(2):apps[0].batch('warm'+str(w),mixed=True,warm=True)
            for r in range(args.rounds):results.append(apps[0].batch('mixed'+str(r),mixed=True))
            apps[0].verify_real_timeout(unpaid)
        save('summary.json',[{k:v for k,v in r.items() if k not in ('requests','sql_before','sql_after','errors')} for r in results])
    finally:
        for app in reversed(apps):app.close()
        print('Artifacts:',OUT,flush=True)

if __name__=='__main__':main()
