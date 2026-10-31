"""One approved ABBAAB matrix. Owned localhost JDBC/RESP only; no automatic extra rounds."""
import argparse,collections,concurrent.futures,hashlib,json,os,pathlib,queue,secrets,shutil,socket,subprocess,threading,time,uuid,traceback
import fast_ingress_benchmark as f
from controlled_dispatch_checks import RedisAdmin
from full_chain_diagnostic import http
from jmeter_ingress_crosscheck import process_memory
ROOT=f.ROOT;JAVA=f.JAVA
def save(p,value):p.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
def code_identity():
    files=list((ROOT/'ticket-risk-platform/src/main').rglob('*'))+list((ROOT/'ticket-risk-platform/target/classes').rglob('*'))+list((ROOT/'.tmp/fullchain-classes').rglob('*.class'))+[ROOT/'.tmp/fullchain-agent.jar']
    return {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files if p.is_file()}
def memory_gate():
    samples=[]
    for _ in range(3):samples.append(f.client_resources());time.sleep(.2)
    free=shutil.disk_usage(ROOT).free
    return dict(ok=min(x['host_memory_available'] for x in samples)>=1024**3 and free>=1024**3,samples=samples,required_mib=1024,disk_free_bytes=free,disk_required_bytes=1024**3)

def check_environment(values,session):
    env=os.environ.copy();env.update(CONTROLLED_MYSQL_ROOT_PASSWORD=values['CREDIT_RISK_MYSQL_ROOT_PASSWORD'],COMPARISON_SCHEMA='credit_risk_controlled_readonly_check',COMPARISON_OWNER=uuid.uuid4().hex,COMPARISON_USER='crctrl_readonlycheck')
    db=Database(env,session,'environment-check')
    try:
        found=db.call('inspect-environment')['resources'];save(session/'environment-mysql.json',found)
        assert not found['schemas'] and not found['accounts'],'Controlled test database/account leftovers; no automatic deletion'
    finally:db.close()
    red=RedisAdmin(values['CREDIT_RISK_REDIS_PASSWORD']);owners=[]
    try:
        info=red.command('INFO','keyspace')
        for line in info.splitlines():
            if line.startswith('db'):
                index=int(line.split(':',1)[0][2:]);red.command('SELECT',index)
                if red.command('EXISTS','credit-risk:controlled-owner'):owners.append(index)
        save(session/'environment-redis.json',dict(owned_marker_databases=owners))
        assert not owners,'Controlled Redis owner markers remain; no automatic deletion'
    finally:red.close()

class Database:
    def __init__(self,env,out,name):
        deps=(ROOT/'ticket-risk-platform/target/benchmark-classpath.txt').read_text(encoding='utf-8-sig').strip();cp=str(ROOT/'.tmp/fullchain-classes')+os.pathsep+deps
        self.log=(out/(name+'.log')).open('w',encoding='utf-8');self.replies=queue.Queue();self.lock=threading.Lock()
        self.proc=subprocess.Popen([str(JAVA/'java.exe'),'-Xmx96m','-Duser.timezone=UTC','-cp',cp,'ComparisonDatabase'],cwd=ROOT,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.log,text=True,encoding='utf-8',creationflags=subprocess.CREATE_NO_WINDOW)
        def reader():
            for line in self.proc.stdout:
                if line.startswith('COMPARISON_JSON '):self.replies.put(json.loads(line[len('COMPARISON_JSON '):]))
                else:self.log.write(line);self.log.flush()
        self.thread=threading.Thread(target=reader,daemon=True);self.thread.start()
        self.events=[]
        start=time.monotonic()
        try:
            ready=self.replies.get(timeout=35)
            if 'ready_connect_ms' not in ready:raise RuntimeError('Invalid database startup handshake')
            self.events.append(dict(phase='startup',elapsed_ms=(time.monotonic()-start)*1000,**ready))
        except Exception:
            self.close();raise TimeoutError('Database helper startup/connect exceeded 35s or handshake failed')
    def call(self,op,sql=None):
        with self.lock:
            request_id=uuid.uuid4().hex;payload=dict(op=op,id=request_id)
            if sql is not None:payload['sql']=sql
            start=time.monotonic()
            self.proc.stdin.write(json.dumps(payload)+'\n');self.proc.stdin.flush()
            try:result=self.replies.get(timeout=35)
            except queue.Empty:
                self.events.append(dict(phase=op,timeout_seconds=35,elapsed_ms=(time.monotonic()-start)*1000))
                self.close()
                raise TimeoutError('Database phase '+op+' exceeded unchanged 35s budget; helper closed, no business load permitted')
            if result.get('id')!=request_id:raise RuntimeError('Database response ID mismatch')
            self.events.append(dict(phase=op,elapsed_ms=(time.monotonic()-start)*1000,operation_ms=result.get('operation_ms')))
            if not result.get('ok'):raise RuntimeError(result.get('error','JDBC operation failed'))
            return result
    def query(self,sql):return self.call('query',sql)['rows']
    def execute(self,sql):return self.call('execute',sql)
    def close(self):
        if self.proc.poll() is None:
            self.proc.stdin.close()
            try:self.proc.wait(5)
            except subprocess.TimeoutExpired:self.proc.kill();self.proc.wait(5)
        self.thread.join(2)
        if not self.log.closed:self.log.close()

class Observer:
    def __init__(self,env,out,schema):
        self.db=Database(env,out,'mysql-observer');self.rows=[];self.stop=threading.Event();self.schema=schema
        self.thread=threading.Thread(target=self.run,daemon=True);self.thread.start()
    def run(self):
        try:
            while not self.stop.is_set():
                begin=time.time()
                locks=self.db.query("SELECT w.REQUESTING_ENGINE_TRANSACTION_ID requesting,w.BLOCKING_ENGINE_TRANSACTION_ID blocking,tr.PROCESSLIST_ID connection_id,l.OBJECT_NAME table_name,l.INDEX_NAME index_name,l.ENGINE_LOCK_ID lock_id,l.LOCK_MODE mode FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID LEFT JOIN performance_schema.threads tr ON tr.THREAD_ID=w.REQUESTING_THREAD_ID WHERE l.OBJECT_SCHEMA='"+self.schema+"'")
                counts=self.db.query("SELECT state,COUNT(*) n FROM tb_reservation_job GROUP BY state")
                self.rows.append(dict(epoch=begin,query_ms=(time.time()-begin)*1000,locks=locks,counts=counts));self.stop.wait(.5)
        except Exception as e:self.rows.append(dict(error=str(e)))
    def close(self):self.stop.set();self.thread.join(38);self.db.close()

def snapshot(db):
    return dict(status=db.query("SELECT VARIABLE_NAME name,VARIABLE_VALUE value FROM performance_schema.global_status WHERE VARIABLE_NAME IN ('Threads_running','Threads_connected','Innodb_log_waits','Innodb_os_log_fsyncs','Innodb_data_fsyncs','Innodb_os_log_written')"),digest=db.query("SELECT DIGEST digest,DIGEST_TEXT sql_text,COUNT_STAR count,SUM_TIMER_WAIT sum_ps,SUM_LOCK_TIME lock_ps FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME=DATABASE()"))

def run_one(mode,number,session,identity,values,preflight=False):
    out=session/(('preflight-'+mode) if preflight else f'{number}-{mode}');out.mkdir();stamp=time.strftime('%Y%m%d_%H%M%S')+'_'+uuid.uuid4().hex[:4];schema='credit_risk_controlled_'+stamp;owner=uuid.uuid4().hex;user='crctrl_'+uuid.uuid4().hex[:16];password=secrets.token_hex(18)
    env=os.environ.copy();env.update(CONTROLLED_MYSQL_ROOT_PASSWORD=values['CREDIT_RISK_MYSQL_ROOT_PASSWORD'],COMPARISON_SCHEMA=schema,COMPARISON_OWNER=owner,COMPARISON_USER=user,COMPARISON_PASSWORD=password)
    db=None;red=None;rdb=None;proc=None;observer=None;created=False;watchdog=None;monitor=None;stop=threading.Event();jlog=None;cleanup={};result=None
    try:
        assert code_identity()==identity,'Candidate code or artifact changed'
        gate=memory_gate();save(out/'resource-gate.json',gate)
        if not gate['ok']:raise RuntimeError('RESOURCE_GATE: less than 1GiB available; no service or load started')
        with socket.socket() as sock:sock.bind(('127.0.0.1',18090))
        save(out/'ownership.json',dict(schema=schema,owner=owner,user=user,phase='before-create'))
        db=Database(env,out,'mysql-control');created=True
        db.call('create');db.call('initialize-schema');db.call('initialize-data')
        save(out/'database-initialization.json',db.events)
        red=RedisAdmin(values['CREDIT_RISK_REDIS_PASSWORD'])
        for index in range(1,256):
            red.command('SELECT',index)
            if red.command('EVAL',"if redis.call('DBSIZE')==0 then redis.call('SET',KEYS[1],ARGV[1]);return 1 else return 0 end",1,'credit-risk:controlled-owner',owner)==1:rdb=index;break
        assert rdb is not None,'No empty Redis DB'
        admin='compare-admin-'+owner;red.command('HSET','login:token:'+admin,'id',1,'role','ADMIN','nickName','comparison');red.command('EXPIRE','login:token:'+admin,1800)
        props={'ticketing.reservations-v2.dispatch.'+k:mode=='B' for k in ['isolated','batch-claim','wakeup']}
        app_env=env.copy();app_env.pop('CONTROLLED_MYSQL_ROOT_PASSWORD',None);app_env.pop('COMPARISON_PASSWORD',None);app_env.pop('SPRING_APPLICATION_JSON',None)
        app_env.update(SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:13306/"+schema+"?useSSL=false&serverTimezone=UTC&useLegacyDatetimeCode=false&sessionVariables=time_zone='%2B00:00'&characterEncoding=utf8&allowPublicKeyRetrieval=true",SPRING_DATASOURCE_USERNAME=user,SPRING_DATASOURCE_PASSWORD=password,SPRING_REDIS_HOST='127.0.0.1',SPRING_REDIS_PORT='26379',SPRING_REDIS_DATABASE=str(rdb),SPRING_REDIS_PASSWORD=values['CREDIT_RISK_REDIS_PASSWORD'])
        deps=(ROOT/'ticket-risk-platform/target/benchmark-classpath.txt').read_text(encoding='utf-8-sig').strip();cp=str(ROOT/'.tmp/fullchain-classes')+os.pathsep+str(ROOT/'ticket-risk-platform/target/classes')+os.pathsep+deps
        args=[str(JAVA/'java.exe'),'-Xms128m','-Xmx384m','-XX:+UseSerialGC','-Duser.timezone=UTC','-Dnet.bytebuddy.experimental=true','-XX:StartFlightRecording=name=FullChain,settings=profile,filename='+str(out/'full-chain.jfr')+',maxsize=64m,dumponexit=true','-javaagent:'+str(ROOT/'.tmp/fullchain-agent.jar'),'-cp',cp,'fullchain.FullChainProbe','--server.address=127.0.0.1','--server.port=18090','--spring.profiles.active=reservations-v2','--ticketing.jobs.enabled=false','--ticketing.mq.enabled=false','--spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration','--ticketing.agent.key=','--ticketing.agent.url=http://127.0.0.1:1','--spring.main.banner-mode=off']+['--'+k+'='+str(v).lower() for k,v in props.items()]
        save(out/'configuration.json',dict(mode=mode,preflight=preflight,args=args,properties=props,schema=schema,redis_db=rdb,cold=True,warmup_requests=0))
        jlog=(out/'java.log').open('w',encoding='utf-8');proc=subprocess.Popen(args,cwd=ROOT,env=app_env,stdout=jlog,stderr=jlog,creationflags=subprocess.CREATE_NO_WINDOW);watchdog=threading.Timer(300,proc.kill);watchdog.daemon=True;watchdog.start()
        until=time.monotonic()+70
        while time.monotonic()<until:
            if proc.poll() is not None:raise RuntimeError('Service startup failed')
            try:
                if http(18090,'/actuator/health',timeout=3)[1].get('status')=='UP':break
            except Exception:pass
            time.sleep(.3)
        else:raise TimeoutError('Startup deadline')
        def probe(full=False):
            code,value=http(18090,'/api/admin/lock-diag?full='+str(full).lower(),admin,timeout=12);assert code==200,(code,value);return value
        initial=probe(True);save(out/'empty-probe.json',initial)
        expected={k:mode=='B' for k in ['isolated','batch-claim','wakeup']};assert initial['dispatchFlags']==expected
        assert [initial[k] for k in ['ingressCore','ingressCapacity','saleCore','saleMax','saleQueueCapacity','postRate','persistentCapacity','globalActive']]==[4,256,4,4,0,1000,512,4]
        assert initial['hikari']['max']==10 and initial['connectionIdMisses']==0
        assert not initial['traces'] and any(x['size']==0 and x['kind']==('claimBatch' if mode=='B' else 'claim') for x in initial['claimAttempts'])
        assert any(x['operation']=='ReservationV2Service.dispatchOnce' and x['thread']==('sale-dispatch-control' if mode=='B' else 'scheduling-1') for x in initial['scheduled'])
        ids={s.get('connectionId') for c in initial['claimAttempts'] for s in c['spans'] if s.get('connectionId')};server_ids={x['id'] for x in db.query("SELECT ID id FROM information_schema.PROCESSLIST WHERE DB=DATABASE() AND USER='"+user+"'")};assert ids and ids.issubset(server_ids)
        assert db.query("SELECT COUNT(*) n FROM tb_reservation_job")[0]['n']==0
        settings=db.query('SELECT * FROM tb_reservation_dispatch WHERE id=1')[0];assert settings['capacity']==512 and settings['active_limit']==4
        sku='ab-'+stamp;event=sku+'-e';sale=sku+'-s';base=901000
        db.execute(f"INSERT INTO tb_event(id,name,description,venue,status) VALUES('{event}','comparison','isolated','test','ON_SALE')")
        db.execute(f"INSERT INTO tb_event_session VALUES('{sale}','{event}','2027-12-31 19:30:00')")
        begin_sale=int(time.time())+30
        db.execute(f"INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,sale_start_time,sale_end_time,purchase_limit,status,version) VALUES('{sku}','{sale}','comparison',10,1000,1000,FROM_UNIXTIME({begin_sale}),FROM_UNIXTIME({begin_sale+3600}),2,'ON_SALE',0)")
        tokens=[]
        if not preflight:
            db.execute('INSERT INTO tb_user(id,phone,nick_name,role,create_time) VALUES '+','.join(f"({base+i},'155{base+i:08d}','comparison','USER','2025-01-01')" for i in range(50)))
            for i in range(50):
                token='compare-user-'+owner+'-'+str(i);tokens.append(token);red.command('HSET','login:token:'+token,'id',base+i,'role','USER','nickName','comparison');red.command('EXPIRE','login:token:'+token,1800)
        # Set fixture sale time after preparation, preserving the same 30-second lead for both modes.
        begin_sale=int(time.time())+30;db.execute(f"UPDATE tb_ticket_sku SET sale_start_time=FROM_UNIXTIME({begin_sale}),sale_end_time=FROM_UNIXTIME({begin_sale+3600}) WHERE id='{sku}'")
        code,assembled=http(18090,'/api/admin/sessions/'+sale+'/assembly',admin,dict(requestId='arm-'+sku,expectedVersion=0),timeout=25);assert code==200 and assembled.get('success'),assembled
        stock_keys=['ticket:{'+sku+'}:stock:bucket:'+str(i) for i in range(16)];assert sum(map(int,red.command('MGET',*stock_keys)))==1000
        rows=db.query(f"SELECT bucket_id,remaining_stock FROM tb_ticket_stock_bucket WHERE sku_id='{sku}' ORDER BY bucket_id");assert len(rows)==16 and sum(x['remaining_stock'] for x in rows)==1000
        assert db.query(f"SELECT state,active_version,version FROM tb_ticket_sale_assembly WHERE session_id='{sale}'")[0]==dict(state='READY',active_version=1,version=1)
        if preflight:
            state=db.query("SELECT (SELECT COUNT(*) FROM tb_reservation_job) jobs,(SELECT COUNT(*) FROM tb_reservation_job WHERE lease_token IS NOT NULL OR lease_until IS NOT NULL) leases,(SELECT COUNT(*) FROM tb_ticket_order) orders,(SELECT COUNT(*) FROM tb_reserve_request) intents")[0]
            assert state==dict(jobs=0,leases=0,orders=0,intents=0),state
            assert initial['ingressActive']==initial['ingressQueue']==initial['saleActive']==initial['saleQueue']==0
            save(out/'preflight.json',dict(passed=True,empty_queue=True,stock=1000,lease_rows=state['leases'],state=state,flags=expected,connection_ids_verified=True,buckets=rows));return dict(preflight=True,mode=mode,passed=True)
        time.sleep(max(0,begin_sale+.05-time.time()));observer=Observer(env,out,schema);before=probe();save(out/'mysql-before.json',snapshot(db));resources=[];alert=threading.Event()
        def sample():
            low=0
            while not stop.wait(.25):
                host=f.client_resources();resources.append(dict(host=host,java=f.owned_java_cpu(proc),memory=process_memory(proc)));low=low+1 if host['host_memory_available']<256*1024**2 else 0
                if low>=4:alert.set()
        monitor=threading.Thread(target=sample,daemon=True);monitor.start();cpu_before=f.owned_java_cpu(proc);start=time.perf_counter();requests=[]
        def send(i):
            if alert.is_set():raise RuntimeError('Resource alarm before send')
            rid='comparison-request-'+str(i);body=dict(requestId=rid,userId=base+i,eventSessionId=sale,ticketSkuId=sku,quantity=1,deviceId='device-'+rid);at=time.perf_counter();code,wire=http(18090,'/api/tickets/reservations',tokens[i],body,'127.2.1.'+str(i+1),rid,timeout=8)
            return dict(requestId=rid,body=body,code=code,response=wire,http_ms=(time.perf_counter()-at)*1000,token=tokens[i],source='127.2.1.'+str(i+1))
        with concurrent.futures.ThreadPoolExecutor(max_workers=10) as clients:requests=list(clients.map(send,range(50)))
        send_seconds=time.perf_counter()-start;assert all(x['code']==202 for x in requests),'Non-202 response; stop matrix'
        deadline=time.monotonic()+60;observations=[]
        while True:
            shot=probe();observations.append(shot)
            if len(shot['traces'])==50 and all(t['locked']>0 and t['saleEnd']>0 and t['consumerEnd']>0 for t in shot['traces']) and shot['ingressActive']==shot['ingressQueue']==shot['saleActive']==shot['saleQueue']==0:break
            if alert.is_set():raise RuntimeError('Sustained low-memory alarm')
            if any(t.get('aborted') and not t.get('locked') for t in shot['traces']):raise RuntimeError('Reservation abort; stop matrix')
            if time.monotonic()>deadline:raise TimeoutError('Finite terminal deadline')
            time.sleep(.5)
        full=probe(True);cpu_after=f.owned_java_cpu(proc);stop.set();monitor.join(2)
        save(out/'50-raw.json',dict(requests=requests,traces=full['traces'],before=before,after=full,observations=observations,resources=resources))
        persisted=db.query("SELECT j.request_id,j.state,j.lease_token,j.lease_until,j.reservation_id,j.order_id,o.status order_status,r.status reservation_status,i.status intent_status FROM tb_reservation_job j LEFT JOIN tb_ticket_order o ON o.id=j.order_id LEFT JOIN tb_reservation r ON r.id=j.reservation_id LEFT JOIN tb_reserve_request i ON i.request_id=j.request_id")
        assert len(persisted)==50 and all(x['state']=='RESERVED' and x['order_status']=='WAITING_PAYMENT' and x['reservation_status']=='HELD' and x['intent_status']=='COMPLETED' and x['lease_token'] is None and x['lease_until'] is None for x in persisted)
        buckets=db.query(f"SELECT bucket_id,remaining_stock FROM tb_ticket_stock_bucket WHERE sku_id='{sku}' ORDER BY bucket_id");stocks=list(map(int,red.command('MGET',*stock_keys)));assert stocks==[x['remaining_stock'] for x in buckets] and sum(stocks)==950
        assert len({x['order_id'] for x in persisted})==len({x['reservation_id'] for x in persisted})==50
        assert all(t['workCount']==t['submitCount']==t['claimCount']==1 and t['claimLease']==t['processLease'] and t['saleSubmitSuccess'] and not t['saleSubmitErrors'] for t in full['traces'])
        assert full['forbiddenCalls']==0 and full['connectionIdMisses']==0
        repeats=[]
        for request in requests[:4]:
            code,wire=http(18090,'/api/tickets/reservations',request['token'],request['body'],request['source'],request['requestId']);assert code==202 and wire['data']['newSubmission'] is False;repeats.append(dict(requestId=request['requestId'],status=code,newSubmission=False))
        final=probe(True);assert all(t['workCount']==t['claimCount']==1 for t in final['traces']);assert sum(map(int,red.command('MGET',*stock_keys)))==950
        counts=db.query("SELECT (SELECT COUNT(*) FROM tb_ticket_order) orders,(SELECT COUNT(*) FROM tb_reservation) reservations,(SELECT COUNT(*) FROM tb_stock_bucket_allocation WHERE released=false) allocations,(SELECT COALESCE(SUM(quantity),0) FROM tb_user_purchase WHERE sku_id='"+sku+"') quota,(SELECT COUNT(*) FROM tb_reserve_request WHERE status='REQUESTED') pending_intents,(SELECT COUNT(*) FROM tb_reservation_job WHERE state IN ('EVALUATING','QUEUED','CLAIMED')) pending_jobs")[0]
        assert counts==dict(orders=50,reservations=50,allocations=50,quota=50,pending_intents=0,pending_jobs=0),counts
        assert all(sum(x['n'] for x in tick['counts'] if x['state']=='CLAIMED')<=4 and sum(x['n'] for x in tick['counts'] if x['state'] in ('EVALUATING','QUEUED','CLAIMED'))<=512 for tick in observer.rows if 'counts' in tick)
        first=min(t['enqueue'] for t in full['traces']);last=max(t['locked'] for t in full['traces']);seconds=(last-first)/1e9
        timings={name:f.pct([(t[end]-t[begin])/1e6 for t in full['traces']]) for name,begin,end in [('end_to_end','enqueue','locked'),('memory_wait','enqueue','consumerStart'),('ingress_work','consumerStart','consumerEnd'),('sale_wait','saleSubmit','saleStart'),('claim','claimStart','claimEnd')]}
        result=dict(round=number,mode=mode,http_qps=50/send_seconds,lock_tps=50/seconds,batch_seconds=seconds,timings=timings,success=50,failed=0,pending=0,correctness=True,java_cpu_cores=(cpu_after['cpu_nanos']-cpu_before['cpu_nanos'])/(cpu_after['nano']-cpu_before['nano']))
        save(out/'50-result.json',result);save(out/'inventory.json',dict(rows=persisted,buckets=buckets,redis=stocks,counts=counts));save(out/'50-idempotency.json',repeats);save(out/'final-probe.json',final);save(out/'mysql-after.json',snapshot(db))
        with (out/'jfr-dump.log').open('w',encoding='utf-8') as log:
            dump=subprocess.run([str(JAVA/'jcmd.exe'),str(proc.pid),'JFR.dump','name=FullChain','filename='+str(out/'full-chain.jfr')],stdout=log,stderr=log,timeout=20);assert dump.returncode==0
        assert code_identity()==identity and not alert.is_set()
        return result
    except Exception as error:
        save(out/'failure.json',dict(error=str(error),traceback=traceback.format_exc(),business_result_available=result is not None));raise
    finally:
        stop.set()
        if monitor:monitor.join(2)
        if observer:observer.close();save(out/'mysql-observations.json',observer.rows)
        if watchdog:watchdog.cancel()
        if proc and proc.poll() is None:proc.terminate();proc.wait(10)
        if jlog:jlog.close()
        redis_error=None
        if red:
            try:
                if rdb is not None:
                    red.command('SELECT',rdb);assert red.command('GET','credit-risk:controlled-owner')==owner;keys=red.command('KEYS','*');assert all(k.startswith(('credit-risk:controlled-owner','ticket:','risk:','login:')) for k in keys)
                    if keys:red.command('UNLINK',*keys)
                    cleanup['redis_keys_left']=red.command('DBSIZE')
            except Exception as error:
                redis_error=error;cleanup['redis_cleanup_error']=str(error)
            finally:red.close()
        if db:
            try:
                save(out/'database-phases.json',db.events)
                if created:
                    if db.proc.poll() is not None:
                        db.close();db=Database(env,out,'mysql-cleanup')
                    db.call('cleanup');cleanup['owned_schema_removed']=True
            except Exception as error:
                cleanup['cleanup_error']=str(error);save(out/'cleanup.json',cleanup);raise
            finally:db.close()
        cleanup.update(owned_java_exited=proc is None or proc.poll() is not None,business_complete=result is not None,docker_lifecycle_operations=0);save(out/'cleanup.json',cleanup)
        if redis_error:raise redis_error

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--execute-approved',action='store_true');args=parser.parse_args()
    if not args.execute_approved:raise SystemExit('No load. Explicit --execute-approved is required after user approval.')
    session=ROOT/'performance/results'/('controlled-comparison-'+time.strftime('%Y%m%d_%H%M%S'));session.mkdir();identity=code_identity();save(session/'code-identity.json',identity);gate=memory_gate();save(session/'resource-gate.json',gate)
    print('RESULT_DIRECTORY='+str(session),flush=True);results=[]
    status=(ROOT/'.tmp/comparison-adaptation/docker-status.txt').read_text(encoding='utf-8-sig');assert '/credit-risk-dev-mysql true no credit-risk-dev' in status and '/credit-risk-v2-redis true no credit-risk-v2-tests' in status;save(session/'docker-status.json',dict(read_only=True,text=status,lifecycle_operations=0))
    save(session/'authorization.json',dict(user_approved=True,sequence=['A','B','B','A','A','B'],automatic_extra_rounds=False));shutil.copyfile(ROOT/'.tmp/comparison-adaptation/self-test.log',session/'probe-self-test.log')
    if not gate['ok']:
        save(session/'stopped.json',dict(reason='RESOURCE_GATE: available memory below approved 1GiB minimum',completed_rounds=0,business_requests_sent=0,resources_created=False));print('STOPPED: below 1GiB memory gate, no resources or traffic');return
    values=dict(x.split('=',1) for x in (ROOT/'docker/credit_risk/.env').read_text(encoding='utf-8-sig').splitlines() if '=' in x and not x.startswith('#'))
    try:
        check_environment(values,session)
        for mode in ['A','B']:run_one(mode,0,session,identity,values,preflight=True)
        for number,mode in enumerate(['A','B','B','A','A','B'],1):
            result=run_one(mode,number,session,identity,values);results.append(result);save(session/'results.json',results);print(json.dumps(result),flush=True)
    except Exception as error:
        save(session/'stopped.json',dict(reason=str(error),completed_rounds=len(results),no_automatic_retry=True));raise
    finally:save(session/'results.json',results)

if __name__=='__main__':main()
