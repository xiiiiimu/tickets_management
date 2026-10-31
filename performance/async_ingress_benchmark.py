"""Owned fresh-schema verification and bounded POST-only load. Never clears existing data.
Run manually after package and with project test Redis/MySQL running. See run-async-ingress.ps1.
The historical harness is imported only for isolated provisioning/HTTP helpers; its results are not reused.
"""
import argparse, collections, concurrent.futures, hashlib, json, os, pathlib, subprocess, threading, time
import reservation_pipeline_benchmark as base

base.OUT.rename(base.ROOT/'performance/results'/('async-ingress-'+base.STAMP))
base.OUT=base.ROOT/'performance/results'/('async-ingress-'+base.STAMP)
OUT=base.OUT
save=base.save;sql=base.sql;redis=base.redis;http=base.http

class App(base.App):
    def __init__(self):
        super().__init__('async',18086,'new')
        self.env=os.environ.copy();self.env.update(SPRING_DATASOURCE_URL=f'jdbc:mysql://127.0.0.1:13306/{self.db}?useSSL=false&serverTimezone=UTC&useLegacyDatetimeCode=false&characterEncoding=utf8&allowPublicKeyRetrieval=true',SPRING_DATASOURCE_USERNAME=self.user,SPRING_DATASOURCE_PASSWORD=self.password,
            SPRING_REDIS_HOST='127.0.0.1',SPRING_REDIS_PORT='26379',SPRING_REDIS_DATABASE=str(self.rdb),SPRING_REDIS_PASSWORD=base.ENV['CREDIT_RISK_REDIS_PASSWORD'],ROCKETMQ_NAME_SERVER='127.0.0.1:22976')
        self.command=list(self.proc.args)
    def restart(self):
        self.close();self.spawn()
    def spawn(self):
        self.log=(OUT/'async-restart.stdout.log').open('a',encoding='utf-8');self.err=(OUT/'async-restart.stderr.log').open('a',encoding='utf-8')
        self.proc=subprocess.Popen(self.command,cwd=base.ROOT/'ticket-risk-platform',env=self.env,stdout=self.log,stderr=self.err,creationflags=subprocess.CREATE_NO_WINDOW)
        self.healthy(self.port,self.proc)
    @staticmethod
    def healthy(port,proc):
        until=time.monotonic()+180
        while time.monotonic()<until:
            if proc.poll() is not None:raise RuntimeError('Owned Java process exited')
            try:
                if http(port,'/actuator/health')[1].get('status')=='UP':return
            except Exception:pass
            time.sleep(1)
        raise TimeoutError('Java health')
    def fixture_n(self,label,n,mixed=False):
        self.counter+=1;userbase=1000000+self.counter*10000
        sku='async-'+label;session=sku+'-s';start=int(time.time())+20;stock=max(5000,n*2)
        sql(self.db,f"INSERT INTO tb_event_session VALUES('{session}','event-1','2027-12-31 19:30:00'); INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,sale_start_time,sale_end_time,purchase_limit,status,version) VALUES('{sku}','{session}','async benchmark',10,{stock},{stock},FROM_UNIXTIME({start}),FROM_UNIXTIME({start+7200}),2,'ON_SALE',0);")
        for offset in range(0,n,250):
            indices=range(offset,min(offset+250,n))
            sql(self.db,'INSERT INTO tb_user(id,phone,nick_name,role,create_time) VALUES '+','.join(f"({userbase+i},'139{userbase+i:08d}','bench','USER','2025-01-01')" for i in indices)+';')
            redis(self.rdb,'\n'.join(f'HSET login:token:bench-{userbase+i} id {userbase+i} role USER nickName benchmark' for i in indices))
        if mixed:
            # Real USER BLOCK profiles and DEVICE_ACCOUNTS rule input; no rule/threshold replacement.
            sql(self.db,'INSERT INTO tb_risk_profile(id,subject_type,subject_value,risk_level,decision,score,expires_at) VALUES '+','.join(f"('block-{sku}-{i}','USER','{userbase+i}','HIGH','BLOCK',100,FROM_UNIXTIME({start+7200}))" for i in range(n) if i%10==9)+';')
            redis(self.rdb,'\n'.join('ZADD risk:deviceAccounts:device-'+str(userbase+i)+' '+' '.join(f'{int(time.time()*1000)} seed-{j}' for j in range(6)) for i in range(n) if i%10 in (7,8)))
        code,wire=http(self.port,'/api/admin/sessions/'+session+'/assembly',{'requestId':'assemble-'+sku,'expectedVersion':0},'bench-admin');assert code==200 and wire.get('success'),wire
        time.sleep(max(0,start+.05-time.time()));return dict(sku=sku,session=session,userbase=userbase,stock=stock,n=n)
    def post(self,f,i):
        user=f['userbase']+i;rid='req-'+f['sku']+'-'+str(i);body=dict(requestId=rid,userId=user,eventSessionId=f['session'],ticketSkuId=f['sku'],quantity=1,deviceId='device-'+str(user));begin=time.time()
        try:
            code,wire=http(self.port,'/api/tickets/reservations',body,'bench-'+str(user),base.source(user));data=wire.get('data') or {}
            return dict(i=i,user=user,requestId=rid,begin=begin,end=time.time(),ms=1000*(time.time()-begin),code=code,wire=wire,submitted=code==202 and data.get('newSubmission') is True)
        except Exception as error:return dict(i=i,user=user,requestId=rid,begin=begin,end=time.time(),ms=1000*(time.time()-begin),code=0,error=str(error),submitted=False)
    def jobs(self,f):
        return [json.loads(x) for x in sql(self.db,f"SELECT JSON_OBJECT('requestId',request_id,'state',state,'decision',decision,'code',result_code,'created',UNIX_TIMESTAMP(created_at),'completed',UNIX_TIMESTAMP(updated_at)) FROM tb_reservation_job WHERE sku_id='{f['sku']}';").splitlines() if x]
    def stockproof(self,f):
        sku=f['sku'];buckets=[list(map(int,line.split())) for line in sql(self.db,f"SELECT bucket_id,total_stock,remaining_stock FROM tb_ticket_stock_bucket WHERE sku_id='{sku}' ORDER BY bucket_id;").splitlines()]
        rs=list(map(int,redis(self.rdb,'MGET '+' '.join(f'ticket:{{{sku}}}:stock:bucket:{i}' for i in range(16))).splitlines()))
        orders=int(sql(self.db,f"SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id='{sku}';"))
        duplicate=int(sql(self.db,f"SELECT COUNT(*) FROM (SELECT request_id FROM tb_reservation WHERE sku_id='{sku}' GROUP BY request_id HAVING COUNT(*)>1) d;"))
        assert len(buckets)==16 and [b[2] for b in buckets]==rs and sum(rs)+orders==f['stock'] and duplicate==0,(buckets,rs,orders,duplicate)
        return dict(mysql=buckets,redis=rs,orders=orders,duplicate_reservations=duplicate,conserved=True)
    def drain(self,f,rows,limit=180):
        deadline=time.monotonic()+limit;samples=[];accepted={r['requestId'] for r in rows if r['submitted']}
        while time.monotonic()<deadline:
            jobs=self.jobs(f);active=[j for j in jobs if j['state'] in ('EVALUATING','QUEUED','CLAIMED')]
            prom=http(self.port,'/actuator/prometheus')[1]
            queue=base.counter(prom,'reservation_ingress_queue_depth');running=base.counter(prom,'reservation_ingress_active')
            samples.append(dict(time=time.time(),volatile_queue=queue,ingress_active=running,oldest_wait_seconds=base.counter(prom,'reservation_ingress_oldest_wait'),durable_pending=len(active),terminal=len(jobs)-len(active)))
            if not active and queue==0 and running==0:break
            time.sleep(2)
        return jobs,samples
    def batch_n(self,label,n,rate=0,mixed=False):
        f=self.fixture_n(label,n,mixed);before=http(self.port,'/actuator/prometheus')[1];(OUT/(label+'-before.prom')).write_text(before,encoding='utf-8');begin=time.time();samples=[];stop=threading.Event()
        def observe():
            while not stop.wait(2):
                try:
                    prom=http(self.port,'/actuator/prometheus')[1];samples.append(dict(time=time.time(),queue=base.counter(prom,'reservation_ingress_queue_depth'),active=base.counter(prom,'reservation_ingress_active'),oldest_wait_seconds=base.counter(prom,'reservation_ingress_oldest_wait')))
                except Exception as error:samples.append(dict(time=time.time(),error=str(error)))
        monitor=threading.Thread(target=observe,daemon=True);monitor.start()
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:
                futures=[]
                for i in range(n):
                    if rate:time.sleep(max(0,begin+i/rate-time.time()))
                    futures.append(pool.submit(self.post,f,i))
                rows=[future.result() for future in futures]
            entry_end=time.time();jobs,drain=self.drain(f,rows);end=time.time()
        finally:stop.set();monitor.join(5)
        after=http(self.port,'/actuator/prometheus')[1];(OUT/(label+'-after.prom')).write_text(after,encoding='utf-8');byrequest={j['requestId']:j for j in jobs};submitted=sum(r['submitted'] for r in rows);success=[j for j in jobs if j['state']=='RESERVED'];unconfirmed=[r['requestId'] for r in rows if r['submitted'] and r['requestId'] not in byrequest]
        latency=[max(0,(j['completed']-r['begin'])*1000) for r in rows for j in [byrequest.get(r['requestId'])] if j and j['state']=='RESERVED']
        last=max([j['completed'] for j in success]+[begin]);seconds=max(.001,entry_end-begin)
        result=dict(label=label,fixture=f,post_requests=n,post_seconds=seconds,total_post_qps=n/seconds,submitted=submitted,submitted_qps=submitted/seconds,http_ms=base.percentiles([r['ms'] for r in rows]),http_status=dict(collections.Counter(str(r['code']) for r in rows)),http_errors=dict(collections.Counter(str(r.get('wire',{}).get('errorMsg',r.get('error'))) for r in rows if r['code']!=202)),reserved=len(success),reliable_tps=len(success)/max(.001,last-begin),click_to_reserved_ms=base.percentiles(latency),risk_decisions=dict(collections.Counter(j['decision'] for j in jobs)),terminal_states=dict(collections.Counter(j['state']+':'+str(j['code']) for j in jobs)),unconfirmed_request_ids=unconfirmed,drain_seconds=end-entry_end,total_observation_seconds=end-begin,stock=self.stockproof(f),samples=samples,drain_samples=drain,requests=rows,jobs=jobs,status_queries_in_measured_post_window=0,offered_rate=rate,payment_requests=0)
        save(label+'.json',result);print('RESULT',json.dumps({k:v for k,v in result.items() if k not in ('requests','jobs','samples','drain_samples','stock','fixture','unconfirmed_request_ids')},ensure_ascii=False),flush=True)
        return result

class RowLock:
    def __init__(self,app,statement):
        self.proc=subprocess.Popen(['docker','exec','-i','credit-risk-dev-mysql','sh','-c','exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --unbuffered -N -B '+app.db],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8')
        self.proc.stdin.write("BEGIN; "+statement+"; SELECT 'LOCKED';\n");self.proc.stdin.flush();self.lines=[];ready=threading.Event()
        def read():
            for line in self.proc.stdout:
                self.lines.append(line.strip())
                if line.strip()=='LOCKED':ready.set();return
        threading.Thread(target=read,daemon=True).start()
        if not ready.wait(30):self.close();raise TimeoutError('Owned SQL fault lock not acquired')
    def close(self):
        if self.proc.poll() is None:
            try:self.proc.stdin.write('COMMIT;\n');self.proc.stdin.flush();self.proc.stdin.close();self.proc.wait(15)
            except Exception:self.proc.kill();self.proc.wait(10)
    def __enter__(self):return self
    def __exit__(self,*args):self.close()

def faults(app):
    base.free_port(18087)
    f=app.fixture_n('precrash',300);secondlog=(OUT/'second-node.log').open('w');args=[x if not x.startswith('--server.port=') else '--server.port=18087' for x in app.command];node=None
    try:
        node=subprocess.Popen(args,cwd=base.ROOT/'ticket-risk-platform',env=app.env,stdout=secondlog,stderr=secondlog,creationflags=subprocess.CREATE_NO_WINDOW);App.healthy(18087,node)
        # Both nodes are started before locking the coordinator: their initialization also uses this row.
        with RowLock(app,'SELECT id FROM tb_reservation_dispatch WHERE id=1 FOR UPDATE'):
            begin=time.time()
            with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:rows=list(pool.map(lambda i:app.post(f,i),range(300)))
            assert any(r['code']==429 for r in rows),collections.Counter(r['code'] for r in rows)
            assert all(r['code'] in (202,429) for r in rows)
            accepted=[r for r in rows if r['submitted']];one=accepted[0]
            code,wire=http(18087,'/api/tickets/reservations/by-request?requestId='+one['requestId']+'&receivedAt='+str(int(one['begin']*1000)),token='bench-'+str(one['user']));assert code==200 and wire['data']['authoritative'] is False and wire['data']['terminal'] is False,wire
            # Explicit hard stop: volatile receipts are allowed to disappear, but no stock changed.
            app.proc.kill();app.proc.wait(15)
        node.terminate();node.wait(30);node=None
        assert not app.jobs(f),app.jobs(f);assert app.stockproof(f)['orders']==0
        app.spawn();code,unknown=http(app.port,'/api/tickets/reservations/by-request?requestId='+one['requestId']+'&receivedAt='+str(int((time.time()-130)*1000)),token='bench-'+str(one['user']));assert unknown['data']['state']=='UNCONFIRMED' and not unknown['data']['terminal'],unknown
        retry1=app.post(f,one['i']);retry2=app.post(f,one['i']);jobs,samples=app.drain(f,[retry1,retry2]);assert len(jobs)==1 and jobs[0]['state']=='RESERVED',jobs
        save('fault-prestock-crash.json',dict(post_ms=base.percentiles([r['ms'] for r in rows]),http_status=dict(collections.Counter(str(r['code']) for r in rows)),submitted=len(accepted),post_qps=300/(max(r['end'] for r in rows)-begin),cross_node=wire,restarted_unknown=unknown,retries=[retry1,retry2],jobs=jobs,stock=app.stockproof(f),allowed_lost_before_retry=len(accepted)))
    finally:
        if node and node.poll() is None:node.terminate();node.wait(30)
        secondlog.close()
    f=app.fixture_n('luacrash',1);sku=f['sku']
    with RowLock(app,f"SELECT bucket_id FROM tb_ticket_stock_bucket WHERE sku_id='{sku}' FOR UPDATE"):
        row=app.post(f,0);assert row['submitted'];deadline=time.time()+45
        while time.time()<deadline:
            rs=list(map(int,redis(app.rdb,'MGET '+' '.join(f'ticket:{{{sku}}}:stock:bucket:{i}' for i in range(16))).splitlines()))
            if sum(rs)==f['stock']-1:break
            time.sleep(.25)
        else:raise TimeoutError('Lua stock effect not observed before MySQL commit')
        intent=sql(app.db,f"SELECT status,reservation_id FROM tb_reserve_request WHERE request_id='{row['requestId']}';");assert intent.startswith('REQUESTED\t'),intent
        assert sql(app.db,f"SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id='{sku}';")=='0'
        app.proc.kill();app.proc.wait(15)
    app.spawn();jobs,samples=app.drain(f,[row],90);assert len(jobs)==1 and jobs[0]['state']=='RESERVED',jobs
    replay=app.post(f,0);app.drain(f,[replay]);proof=app.stockproof(f);assert proof['orders']==1
    save('fault-lua-before-commit-crash.json',dict(durable_intent_before_crash=intent,redis_stock_before_commit=sum(rs),mysql_orders_before_crash=0,job_after_recovery=jobs,stock_after=proof,observations=samples,replay=replay))

def multinode(app):
    base.free_port(18087)
    f=app.fixture_n('multinode',2);log=(OUT/'second-node.log').open('w');node=None
    args=[x if not x.startswith('--server.port=') else '--server.port=18087' for x in app.command]
    try:
        node=subprocess.Popen(args,cwd=base.ROOT/'ticket-risk-platform',env=app.env,stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW);App.healthy(18087,node)
        user=f['userbase'];body=dict(requestId='req-'+f['sku']+'-0',userId=user,eventSessionId=f['session'],ticketSkuId=f['sku'],quantity=1,deviceId='device-'+str(user))
        def send(i):
            code,wire=http(18086 if i%2==0 else 18087,'/api/tickets/reservations',body,'bench-'+str(user),base.source(user));return dict(code=code,wire=wire)
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:receipts=list(pool.map(send,range(8)))
        assert all(r['code']==202 for r in receipts),receipts
        jobs,samples=app.drain(f,[]);assert len(jobs)==1 and jobs[0]['state']=='RESERVED',jobs
        proof=app.stockproof(f);assert proof['orders']==1
        statuses=[]
        for port in (18086,18087):
            code,wire=http(port,'/api/tickets/reservations/by-request?requestId='+body['requestId']+'&receivedAt='+str(int(time.time()*1000)),token='bench-'+str(user));assert code==200 and wire['data']['reservationSuccess'] is True,wire;statuses.append(wire)
        denied,wire=http(18087,'/api/tickets/reservations/by-request?requestId='+body['requestId'],token='bench-'+str(user+1));assert denied==403,(denied,wire)
        save('multinode-idempotency.json',dict(receipts=receipts,jobs=jobs,statuses=statuses,other_user_query_http=denied,stock=proof,samples=samples))
    finally:
        if node and node.poll() is None:node.terminate();node.wait(30)
        log.close()

def main():
    p=argparse.ArgumentParser();p.add_argument('--suite',choices=['faults','multinode','load','all'],default='all');p.add_argument('--duration',type=int,default=120);p.add_argument('--rate',type=int,default=10);args=p.parse_args();assert args.duration>=60 and 1<=args.rate<=100
    save('method.json',dict(suite=args.suite,workers=4,volatile_queue=256,global_durable_queue=512,global_stock_workers=4,pass_weight=2,review_age_ms=2000,hikari=10,post_concurrency=32,stock_min=5000,armory=True,buckets=16,real_java_rules=True,background_jobs=False,mq=False,duration=args.duration,offered_rate=args.rate,semantic_warning='202 is volatile receipt; not comparable to old synchronous completion TPS',jar_sha256=hashlib.sha256((base.ROOT/'ticket-risk-platform/target/ticket-risk-platform-1.0.0-SNAPSHOT.jar').read_bytes()).hexdigest()))
    app=None
    try:
        app=App();results=[]
        if args.suite in ('faults','all'):faults(app)
        if args.suite in ('multinode','all'):multinode(app)
        if args.suite in ('load','all'):
            app.batch_n('warm',32)
            results.append(app.batch_n('smoke',160,mixed=True))
            results.append(app.batch_n('burst',640))
            results.append(app.batch_n('sustained',args.duration*args.rate,args.rate))
            save('summary.json',[{k:v for k,v in result.items() if k not in ('requests','jobs','samples','drain_samples','stock','fixture','unconfirmed_request_ids')} for result in results])
    finally:
        if app:app.close()
        print('Artifacts:',OUT,flush=True)
if __name__=='__main__':main()
