"""Exactly one real 50/10 batch plus four idempotent replays. Diagnostic code only."""
import collections,concurrent.futures,hashlib,json,os,pathlib,queue,re,secrets,socket,subprocess,threading,time,uuid
from http.client import HTTPConnection
import fast_ingress_benchmark as f
from jmeter_ingress_crosscheck import fingerprint,process_memory
ROOT=f.ROOT
def sql(db,statement):
    assert not db or db.startswith('credit_risk_lockdiag_')
    return f.docker('exec','-i','credit-risk-dev-mysql','sh','-c','exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -N -B '+db,stdin=statement)
def redis(index,commands):
    return f.docker('exec','-i','credit-risk-v2-redis','sh','-c','export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --no-auth-warning --raw -n '+str(index),stdin=commands+'\n')
def http(port,path,token=None,data=None,source=None,rid=None,timeout=8):
    c=HTTPConnection('127.0.0.1',port,timeout=timeout,source_address=(source,0) if source else None)
    try:
        headers={'Content-Type':'application/json'}
        if token:headers['authorization']=token
        if rid:headers['X-Lock-Diag']=rid
        c.request('POST' if data is not None else 'GET',path,None if data is None else json.dumps(data),headers);r=c.getresponse();body=r.read();return r.status,json.loads(body)
    finally:c.close()
def pct(ns):return f.pct([x/1e6 for x in ns])

class LockObserver:
    def __init__(self,db,out):
        self.db=db;self.rows=[];self.stop=threading.Event();self.log=(out/'mysql-observer.stderr.log').open('w');self.proc=subprocess.Popen(['docker','exec','-i','credit-risk-dev-mysql','sh','-c','exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B --raw --unbuffered --force'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.log,text=True,encoding='utf-8');self.replies=queue.Queue();self.thread=threading.Thread(target=self.run,daemon=True)
        def read():
            for line in self.proc.stdout:self.replies.put(line.rstrip('\n'))
        threading.Thread(target=read,daemon=True).start();self.thread.start()
    def run(self):
        try:
            while not self.stop.is_set():
                end='LD_END_'+uuid.uuid4().hex;begin=time.time();query="SELECT JSON_OBJECT('kind','lock','requesting',w.REQUESTING_ENGINE_TRANSACTION_ID,'blocking',w.BLOCKING_ENGINE_TRANSACTION_ID,'requesting_connection',tr.PROCESSLIST_ID,'blocking_connection',tb.PROCESSLIST_ID,'table',l.OBJECT_NAME,'index',l.INDEX_NAME,'mode',l.LOCK_MODE,'lock_id',l.ENGINE_LOCK_ID,'wait_age_ms',TIMESTAMPDIFF(MICROSECOND,it.trx_wait_started,NOW(6))/1000) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID LEFT JOIN performance_schema.threads tr ON tr.THREAD_ID=w.REQUESTING_THREAD_ID LEFT JOIN performance_schema.threads tb ON tb.THREAD_ID=w.BLOCKING_THREAD_ID LEFT JOIN information_schema.INNODB_TRX it ON it.trx_id=w.REQUESTING_ENGINE_TRANSACTION_ID WHERE l.OBJECT_SCHEMA='"+self.db+"'; SELECT JSON_OBJECT('kind','connection','id',ID,'state',STATE,'seconds',TIME,'info',INFO) FROM information_schema.PROCESSLIST WHERE DB='"+self.db+"';"
                self.proc.stdin.write(query+" SELECT '"+end+"';\n");self.proc.stdin.flush();rows=[]
                while True:
                    line=self.replies.get(timeout=5)
                    if line==end:break
                    rows.append(line)
                self.rows.append(dict(epoch=begin,query_ms=(time.time()-begin)*1000,waits=rows));self.stop.wait(.5)
        except Exception as e:self.rows.append(dict(error=str(e)))
    def close(self):
        self.stop.set();self.thread.join(6)
        if self.proc.poll() is None:self.proc.stdin.close();self.proc.wait(5)
        self.log.close()

class ContainerStats:
    def __init__(self,out):
        self.log=(out/'container-stats.jsonl').open('w',encoding='utf-8');self.err=(out/'container-stats.stderr.log').open('w',encoding='utf-8')
        self.proc=subprocess.Popen(['docker','stats','--format','{{json .}}','credit-risk-dev-mysql','credit-risk-v2-redis'],stdout=subprocess.PIPE,stderr=self.err,text=True,encoding='utf-8')
        def collect():
            for line in self.proc.stdout:
                try:self.log.write(json.dumps(dict(epoch=time.time(),stats=json.loads(line)))+'\n');self.log.flush()
                except Exception:pass
        self.thread=threading.Thread(target=collect,daemon=True);self.thread.start()
    def close(self):
        if self.proc.poll() is None:self.proc.terminate();self.proc.wait(5)
        self.thread.join(2);self.log.close();self.err.close()

def db_snapshot(db,path):
    queries=dict(status="SELECT JSON_OBJECT('name',VARIABLE_NAME,'value',VARIABLE_VALUE) FROM performance_schema.global_status WHERE VARIABLE_NAME IN ('Threads_running','Threads_connected','Innodb_log_waits','Innodb_os_log_fsyncs','Innodb_data_fsyncs','Innodb_data_reads','Innodb_data_writes','Innodb_os_log_written')",consumers="SELECT JSON_OBJECT('name',NAME,'enabled',ENABLED) FROM performance_schema.setup_consumers",instruments="SELECT JSON_OBJECT('name',NAME,'enabled',ENABLED,'timed',TIMED) FROM performance_schema.setup_instruments WHERE NAME IN ('wait/io/file/innodb/innodb_log_file','wait/io/file/innodb/innodb_data_file','wait/lock/metadata/sql/mdl')",digest="SELECT JSON_OBJECT('digest',DIGEST,'sql',DIGEST_TEXT,'count',COUNT_STAR,'sum_ps',SUM_TIMER_WAIT,'max_ps',MAX_TIMER_WAIT,'lock_ps',SUM_LOCK_TIME,'rows_examined',SUM_ROWS_EXAMINED) FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='"+db+"'",file_io="SELECT JSON_OBJECT('event',EVENT_NAME,'count',COUNT_STAR,'sum_ps',SUM_TIMER_WAIT,'read',COUNT_READ,'write',COUNT_WRITE,'misc',COUNT_MISC,'bytes_read',SUM_NUMBER_OF_BYTES_READ,'bytes_write',SUM_NUMBER_OF_BYTES_WRITE) FROM performance_schema.file_summary_by_event_name WHERE EVENT_NAME LIKE '%innodb%'",indexes="SELECT JSON_OBJECT('table',TABLE_NAME,'index',INDEX_NAME,'seq',SEQ_IN_INDEX,'column',COLUMN_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='"+db+"'",clock="SELECT JSON_OBJECT('server_epoch',UNIX_TIMESTAMP(NOW(6)),'time_zone',@@session.time_zone,'system_time_zone',@@system_time_zone)")
    result={}
    for name,query in queries.items():
        try:result[name]=[json.loads(x) for x in sql('',query+';').splitlines()]
        except Exception as e:result[name]=dict(unavailable=str(e))
    f.save(path,result)

def explain(db,out,sku,user):
    queries=["SELECT COUNT(*) FROM tb_reservation_job WHERE state IN ('EVALUATING','QUEUED','CLAIMED')","SELECT * FROM tb_reservation_job WHERE state='QUEUED' AND decision='PASS' ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED","SELECT * FROM tb_reservation_job WHERE state IN ('EVALUATING','CLAIMED') AND lease_until<NOW() ORDER BY lease_until LIMIT 1 FOR UPDATE SKIP LOCKED","SELECT COUNT(*) FROM tb_ticket_order WHERE user_id="+str(user),"SELECT * FROM tb_ticket_stock_bucket WHERE sku_id='"+sku+"' AND bucket_id=0"]
    result=[]
    for query in queries:
        try:result.append(dict(sql=query,plan=sql(db,'EXPLAIN FORMAT=JSON '+query+';')))
        except Exception as e:result.append(dict(sql=query,error=str(e)))
    f.save(out/'explain.json',result)

def run():
    stamp=time.strftime('%Y%m%d_%H%M%S')+'_'+uuid.uuid4().hex[:4];owner='lockdiag_'+stamp;db='credit_risk_lockdiag_'+stamp;user='crld_'+stamp.replace('_','');password=secrets.token_hex(18)
    out=ROOT/'performance/results'/('full-chain-'+stamp);out.mkdir(parents=True);f.save(out/'production-before.json',fingerprint());before=fingerprint();proc=None;observer=None;rdb=None;created=False;safe=False;private=ROOT/'.tmp'/('lockdiag-'+stamp);private.mkdir(parents=True);(private/'.gitignore').write_text('*\n');f.save(private/'credentials.json',dict(db=db,user=user,password=password))
    for container,project in [('credit-risk-dev-mysql','credit-risk-dev'),('credit-risk-v2-redis','credit-risk-v2-tests')]:
        info=json.loads(f.docker('inspect',container))[0]
        assert info['State']['Running'] and info['Config']['Labels'].get('com.docker.compose.project')==project and info['HostConfig']['RestartPolicy']['Name']=='no'
    print('RESULT_DIRECTORY='+str(out),flush=True);f.save(out/'audit-source-index.json',json.loads((ROOT/'performance/results/full-chain-audit/source-index.json').read_text(encoding='utf-8')))
    try:
        assert not sql('',"SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"+db+"';")
        sql('',f"CREATE DATABASE {db} CHARACTER SET utf8mb4; CREATE USER '{user}'@'%' IDENTIFIED BY '{password}'; GRANT ALL ON {db}.* TO '{user}'@'%';");created=True
        sql(db,"CREATE TABLE tb_lockdiag_owner(owner VARCHAR(80) PRIMARY KEY); INSERT INTO tb_lockdiag_owner VALUES('"+owner+"');")
        sql(db,(ROOT/'ticket-risk-platform/src/main/resources/schema.sql').read_text(encoding='utf-8'));sql(db,(ROOT/'ticket-risk-platform/src/main/resources/data.sql').read_text(encoding='utf-8'))
        import shlex
        claim="if redis.call('DBSIZE')==0 then redis.call('SET',KEYS[1],ARGV[1]);return 1 else return 0 end"
        command='export REDISCLI_AUTH="$REDIS_PASSWORD"; for i in $(seq 1 255); do x=$(redis-cli --no-auth-warning --raw -n "$i" EVAL '+shlex.quote(claim)+' 1 credit-risk:lockdiag-owner '+shlex.quote(owner)+'); if [ "$x" = 1 ]; then printf "%s" "$i"; exit 0; fi; done; exit 1'
        rdb=int(f.docker('exec','credit-risk-v2-redis','sh','-c',command));admin='lock-admin-'+stamp;redis(rdb,'HSET login:token:'+admin+' id 1 role ADMIN nickName lockdiag\nEXPIRE login:token:'+admin+' 1800')
        port=18090
        with socket.socket() as sock:sock.bind(('127.0.0.1',port))
        values=dict(x.split('=',1) for x in (ROOT/'docker/credit_risk/.env').read_text(encoding='utf-8-sig').splitlines() if '=' in x and not x.startswith('#'))
        env=os.environ.copy();env.update(SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:13306/'+db+'?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true',SPRING_DATASOURCE_USERNAME=user,SPRING_DATASOURCE_PASSWORD=password,SPRING_REDIS_HOST='127.0.0.1',SPRING_REDIS_PORT='26379',SPRING_REDIS_DATABASE=str(rdb),SPRING_REDIS_PASSWORD=values['CREDIT_RISK_REDIS_PASSWORD'])
        cp=str(ROOT/'.tmp/fullchain-classes')+os.pathsep+str(ROOT/'ticket-risk-platform/target/classes')+os.pathsep+(ROOT/'ticket-risk-platform/target/benchmark-classpath.txt').read_text(encoding='utf-8-sig').strip()
        args=[str(f.JAVA/'java.exe'),'-Xms128m','-Xmx384m','-XX:+UseSerialGC','-XX:StartFlightRecording=name=FullChain,settings=profile,filename='+str(out/'full-chain.jfr')+',maxsize=64m,dumponexit=true','-Duser.timezone=UTC','-Dnet.bytebuddy.experimental=true','-javaagent:'+str(ROOT/'.tmp/fullchain-agent.jar'),'-cp',cp,'fullchain.FullChainProbe','--server.address=127.0.0.1','--server.port='+str(port),'--spring.profiles.active=reservations-v2','--ticketing.jobs.enabled=false','--ticketing.mq.enabled=false','--spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration','--ticketing.agent.key=','--ticketing.agent.url=http://127.0.0.1:1','--spring.main.banner-mode=off']
        f.save(out/'configuration.json',dict(args=args,db=db,db_user=user,redis_db=rdb,no_pool_or_queue_overrides=True,paid_agent_disabled=True,endpoint='WAITING_PAYMENT after final commit',excluded='post-lock jobs/MQ/payment/issue; real locking path retained',jdbc_getParameterType_ignore_override=False))
        log=(out/'java.log').open('w',encoding='utf-8');proc=subprocess.Popen(args,cwd=ROOT,env=env,stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW);watchdog=threading.Timer(300,proc.kill);watchdog.daemon=True;watchdog.start()
        deadline=time.monotonic()+70
        while time.monotonic()<deadline:
            if proc.poll() is not None:raise RuntimeError('Diagnostic startup failed; see java.log')
            try:
                if http(port,'/actuator/health')[1].get('status')=='UP':break
            except Exception:pass
            time.sleep(.3)
        else:raise TimeoutError('Finite startup deadline')
        def probe(full=False):
            code,value=http(port,'/api/admin/lock-diag?full='+str(full).lower(),admin,timeout=8);assert code==200,(code,value);return value
        initial=probe();f.save(out/'initial-probe.json',initial);assert initial['ingressCore']==4 and initial['ingressCapacity']==256 and initial['hikari']['max']==10
        required={'com.ticketing.ticket.ReservationIngress','com.ticketing.risk.RiskHistorySummary','com.ticketing.common.util.RefreshTokenInterceptor','com.ticketing.infrastructure.TaskPools','com.ticketing.ticket.ReservationV2Controller'}
        assert required.issubset(set(initial['transformed'])),('Instrumentation incomplete; no batch sent',initial['transformed'])
        observer=LockObserver(db,out);batch_results=[];container_stats=ContainerStats(out)
        for batch,(n,concurrency) in enumerate([(50,10)],1):
            sku='ld-'+stamp+'-'+str(n);session=sku+'-s';event=sku+'-e';base=900000+batch*1000;start=int(time.time())+10
            sql(db,f"INSERT INTO tb_event(id,name,description,venue,status) VALUES('{event}','lock diagnosis','isolated','test','ON_SALE'); INSERT INTO tb_event_session VALUES('{session}','{event}','2027-12-31 19:30:00'); INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,sale_start_time,sale_end_time,purchase_limit,status,version) VALUES('{sku}','{session}','lockdiag',10,1000,1000,FROM_UNIXTIME({start}),FROM_UNIXTIME({start+3600}),2,'ON_SALE',0); "+'INSERT INTO tb_user(id,phone,nick_name,role,create_time) VALUES '+','.join(f"({base+i},'155{base+i:08d}','lockdiag','USER','2025-01-01')" for i in range(n))+';')
            tokens=['lock-user-'+stamp+'-'+str(base+i) for i in range(n)];redis(rdb,'\n'.join(f'HSET login:token:{token} id {base+i} role USER nickName lockdiag\nEXPIRE login:token:{token} 1800' for i,token in enumerate(tokens)))
            # Fixture-only lead time, after token/user preparation; never change runtime bounds.
            start=int(time.time())+30
            sql(db,f"UPDATE tb_ticket_sku SET sale_start_time=FROM_UNIXTIME({start}),sale_end_time=FROM_UNIXTIME({start+3600}) WHERE id='{sku}';")
            code,assembled=http(port,'/api/admin/sessions/'+session+'/assembly',admin,dict(requestId='assemble-'+sku,expectedVersion=0),timeout=25);assert code==200 and assembled.get('success'),assembled
            time.sleep(max(0,start+.05-time.time()));db_snapshot(db,out/"mysql-before.json");before_probe=probe();cpu_before=f.owned_java_cpu(proc);client_before=f.client_resources();resources=[];stop=threading.Event()
            def monitor():
                while not stop.wait(.25):resources.append(dict(host=f.client_resources(),java=f.owned_java_cpu(proc),memory=process_memory(proc)))
            monitor_thread=threading.Thread(target=monitor,daemon=True);monitor_thread.start();begin=time.perf_counter()
            def request(i):
                rid='r-'+sku+'-'+str(i);body=dict(requestId=rid,userId=base+i,eventSessionId=session,ticketSkuId=sku,quantity=1,deviceId='device-'+rid);at=time.perf_counter();code,wire=http(port,'/api/tickets/reservations',tokens[i],body,'127.2.'+str(batch)+'.'+str(i+1),rid,timeout=8);return dict(requestId=rid,body=body,code=code,response=wire,http_ms=(time.perf_counter()-at)*1000,token=tokens[i],source='127.2.'+str(batch)+'.'+str(i+1))
            try:
                with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as clients:requests=list(clients.map(request,range(n)))
                http_seconds=time.perf_counter()-begin;ids={x['requestId'] for x in requests};deadline=time.monotonic()+60;observations=[]
                while True:
                    snapshot=probe();observations.append(snapshot);traces=[t for t in snapshot['traces'] if t['requestId'] in ids]
                    if len(traces)==n and all(t['locked']>0 for t in traces):break
                    if any(x['code']!=202 for x in requests) or time.monotonic()>deadline:break
                    time.sleep(.5)
                full=probe(True);after_probe=full;cpu_after=f.owned_java_cpu(proc);db_snapshot(db,out/'mysql-after.json');f.save(out/'clock-anchors.json',dict(client_wall=time.time(),java_nano=full['nano'],java_wall_ms=full['wallMillis']))
            finally:stop.set();monitor_thread.join(2)
            traces=[t for t in full['traces'] if t['requestId'] in ids];f.save(out/(str(n)+'-raw.json'),dict(requests=requests,traces=traces,before=before_probe,after=full,observations=observations,resources=resources))
            dbrows=[json.loads(x) for x in sql(db,"SELECT JSON_OBJECT('requestId',j.request_id,'state',j.state,'decision',j.decision,'code',j.result_code,'reservation',j.reservation_id,'order',j.order_id,'orderStatus',o.status,'intentStatus',i.status,'reservationStatus',r.status,'queued_ms',IF(j.queued_at IS NULL,NULL,UNIX_TIMESTAMP(j.queued_at)*1000),'claimed_ms',IF(j.claimed_at IS NULL,NULL,UNIX_TIMESTAMP(j.claimed_at)*1000),'expires_ms',IF(o.expires_at IS NULL,NULL,UNIX_TIMESTAMP(o.expires_at)*1000)) FROM tb_reservation_job j LEFT JOIN tb_ticket_order o ON o.id=j.order_id LEFT JOIN tb_reserve_request i ON i.request_id=j.request_id LEFT JOIN tb_reservation r ON r.id=j.reservation_id WHERE j.sku_id='"+sku+"';").splitlines()]
            buckets=[list(map(int,x.split('\t'))) for x in sql(db,"SELECT bucket_id,total_stock,remaining_stock FROM tb_ticket_stock_bucket WHERE sku_id='"+sku+"' ORDER BY bucket_id;").splitlines()];rs=list(map(int,redis(rdb,'MGET '+' '.join('ticket:{'+sku+'}:stock:bucket:'+str(i) for i in range(16))).splitlines()));success=sum(x['state']=='RESERVED' for x in dbrows)
            count_rows=sql(db,"SELECT (SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id='"+sku+"'),(SELECT COUNT(*) FROM tb_reservation WHERE sku_id='"+sku+"'),(SELECT COUNT(*) FROM tb_stock_bucket_allocation WHERE sku_id='"+sku+"' AND released=false),(SELECT COALESCE(SUM(quantity),0) FROM tb_user_purchase WHERE sku_id='"+sku+"'),(SELECT COUNT(*) FROM tb_reserve_request WHERE sku_id='"+sku+"' AND status='REQUESTED');")
            counts=list(map(int,count_rows.split('\t')));good=len(dbrows)==n and success==n and len(buckets)==16 and rs==[b[2] for b in buckets] and sum(rs)==1000-success and counts[:4]==[success]*4 and counts[4]==0 and all(x['orderStatus']=='WAITING_PAYMENT' and x['reservationStatus']=='HELD' and x['intentStatus']=='COMPLETED' for x in dbrows) and full['forbiddenCalls']==0 and all(t['workCount']==1 and t['submitCount']==1 and t['locked']>0 for t in traces)
            durations=collections.defaultdict(list)
            for t in traces:
                if t['locked']:
                    durations['queue'].append(t['consumerStart']-t['enqueue']);durations['backend'].append(t['locked']-t['consumerStart']);durations['end_to_end'].append(t['locked']-t['enqueue']);durations['http_server'].append(t['httpEnd']-t['httpStart'])
                for span in t['spans']:durations[span['operation']].append(span['end']-span['start'])
            batch_seconds=(max((t['locked'] for t in traces),default=0)-min((t['enqueue'] for t in traces),default=0))/1e9 if success==n else time.perf_counter()-begin
            result=dict(n=n,concurrency=concurrency,http_qps=n/http_seconds,http_seconds=http_seconds,status=dict(collections.Counter(str(x['code']) for x in requests)),success=success,failed=sum(x['state'] in ['FAILED','REJECTED','EXPIRED'] for x in dbrows),pending=n-success-sum(x['state'] in ['FAILED','REJECTED','EXPIRED'] for x in dbrows),batch_seconds=batch_seconds,lock_tps=success/batch_seconds if batch_seconds>0 else None,timings={k:dict(**pct(v),count=len(v),mean_ms=sum(v)/len(v)/1e6) for k,v in durations.items()},inventory=dict(buckets=buckets,redis=rs,persisted_counts=counts,rows=dbrows),correctness=good,java_cpu_cores=(cpu_after['cpu_nanos']-cpu_before['cpu_nanos'])/(cpu_after['nano']-cpu_before['nano']))
            f.save(out/(str(n)+'-result.json'),result);batch_results.append(result);print(json.dumps({k:result[k] for k in ['n','http_qps','lock_tps','batch_seconds','success','failed','pending','correctness']}),flush=True)
            if not good:raise RuntimeError('Correctness/terminal guard failed; retain namespace and stop')
            retries=[]
            for first in requests[:4]:
                prior=next(t for t in traces if t['requestId']==first['requestId']);code,wire=http(port,'/api/tickets/reservations',first['token'],first['body'],first['source'],first['requestId']);assert code==202 and wire['data']['newSubmission'] is False
                check=probe(True);replayed=next(t for t in check['traces'] if t['requestId']==first['requestId']);assert replayed['workCount']==prior['workCount']==1
                retries.append(dict(requestId=first['requestId'],status=code,newSubmission=False,worker_count=replayed['workCount']))
            f.save(out/(str(n)+'-idempotency.json'),retries)
            f.save(out/'final-probe.json',probe(True))
            explain(db,out,sku,base)
            with (out/'jfr-dump.log').open('w',encoding='utf-8') as jf:
                subprocess.run([str(f.JAVA/'jcmd.exe'),str(proc.pid),'JFR.dump','name=FullChain','filename='+str(out/'full-chain.jfr')],stdout=jf,stderr=jf,timeout=20)

            if any(x['host'].get('host_memory_available',10**12)<256*1024*1024 for x in resources):raise RuntimeError('Low resource guard; stop further cohorts')
        safe=True;f.save(out/'summary.json',batch_results)
    finally:
        if 'container_stats' in locals():container_stats.close()
        if observer:observer.close();f.save(out/'mysql-lock-wait-samples.json',observer.rows)
        if 'watchdog' in locals():watchdog.cancel()
        if proc and proc.poll() is None:proc.terminate();proc.wait(10)
        if 'log' in locals():log.close()
        cleanup=dict(production_unchanged=fingerprint()==before,owned_java_exited=not proc or proc.poll() is not None,safe_complete=safe,db=db,redis_db=rdb,data_retained=not safe,docker_lifecycle_operations=0)
        if safe:
            assert sql(db,'SELECT owner FROM tb_lockdiag_owner;')==owner and redis(rdb,'GET credit-risk:lockdiag-owner')==owner
            keys=redis(rdb,'KEYS *').splitlines();assert all(k.startswith(('credit-risk:lockdiag-owner','ticket:','risk:','login:')) for k in keys),keys
            if keys:redis(rdb,'UNLINK '+' '.join(keys))
            sql('',f"DROP DATABASE {db}; DROP USER '{user}'@'%';");cleanup.update(data_retained=False,temporary_keys_left=redis(rdb,'DBSIZE'),owned_schema_dropped=True)
        f.save(out/'cleanup.json',cleanup);print('DONE '+str(out),flush=True)

if __name__=='__main__':run()
