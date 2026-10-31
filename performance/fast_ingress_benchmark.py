"""5..10 second entry-only experiments. stdlib, bounded client, no MySQL provisioning.
isolated = real Controller/auth/validation/ReservationIngress + test consumer, NEVER stock TPS.
real = attach explicitly provisioned test server/fixtures, never owns/stops that server.
"""
import argparse, collections, concurrent.futures, hashlib, json, math, os, pathlib, queue
import shlex, socket, statistics, subprocess, threading, time, uuid, gc, platform
from http.client import HTTPConnection
ROOT=pathlib.Path(__file__).resolve().parents[1]
JAVA=pathlib.Path(r'C:\Program Files\Java\jdk-21.0.11\bin')

def save(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def docker(*args,stdin=None):
    p=subprocess.run(['docker',*args],input=stdin,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=30)
    if p.returncode:raise RuntimeError(p.stderr[-1200:])
    return p.stdout.strip()
def pct(values):
    values=sorted(values)
    return {f'p{p}':values[math.ceil(len(values)*p/100)-1] for p in (50,95,99)} if values else {}
def http(port,path,token=None,data=None,timeout=2):
    c=HTTPConnection('127.0.0.1',port,timeout=timeout)
    try:
        c.request('POST' if data is not None else 'GET',path,None if data is None else json.dumps(data),{'Content-Type':'application/json',**({'authorization':token} if token else {})})
        r=c.getresponse();raw=r.read().decode('utf-8');return r.status,json.loads(raw) if raw else {}
    finally:c.close()

def client_resources():
    v=dict(nano=time.perf_counter_ns(),cpu_nanos=time.process_time_ns(),pid=os.getpid(),hostname=socket.gethostname(),gc=gc.get_stats())
    if os.name=='nt':
        import ctypes
        from ctypes import wintypes as w
        class Memory(ctypes.Structure):
            _fields_=[('cb',w.DWORD),('PageFaultCount',w.DWORD)]+[(k,ctypes.c_size_t) for k in ('PeakWorkingSetSize','WorkingSetSize','QuotaPeakPagedPoolUsage','QuotaPagedPoolUsage','QuotaPeakNonPagedPoolUsage','QuotaNonPagedPoolUsage','PagefileUsage','PeakPagefileUsage')]
        m=Memory();m.cb=ctypes.sizeof(m);kernel=ctypes.WinDLL('kernel32');kernel.GetCurrentProcess.restype=w.HANDLE
        ps=ctypes.WinDLL('psapi');ps.GetProcessMemoryInfo.argtypes=[w.HANDLE,ctypes.POINTER(Memory),w.DWORD]
        if ps.GetProcessMemoryInfo(kernel.GetCurrentProcess(),ctypes.byref(m),m.cb):v.update(working_set_bytes=m.WorkingSetSize,private_commit_bytes=m.PagefileUsage)
        class Physical(ctypes.Structure):
            _fields_=[('length',w.DWORD),('load',w.DWORD)]+[(k,ctypes.c_ulonglong) for k in ('total','available','total_page','available_page','total_virtual','available_virtual','extended')]
        physical=Physical();physical.length=ctypes.sizeof(physical)
        if kernel.GlobalMemoryStatusEx(ctypes.byref(physical)):v.update(host_memory_total=physical.total,host_memory_available=physical.available,host_memory_load_percent=physical.load)
        idle=w.FILETIME();kt=w.FILETIME();ut=w.FILETIME()
        if kernel.GetSystemTimes(ctypes.byref(idle),ctypes.byref(kt),ctypes.byref(ut)):
            stamp=lambda f:(f.dwHighDateTime<<32)+f.dwLowDateTime
            v.update(host_cpu_100ns=stamp(kt)+stamp(ut),host_idle_100ns=stamp(idle))
    return v

def owned_java_cpu(proc):
    """Read only our Popen child's CPU clock; no server diagnostic endpoint required."""
    if os.name!='nt':return None
    import ctypes
    from ctypes import wintypes as w
    kernel=ctypes.WinDLL('kernel32');created=w.FILETIME();exited=w.FILETIME();kt=w.FILETIME();ut=w.FILETIME()
    kernel.GetProcessTimes.argtypes=[w.HANDLE,ctypes.POINTER(w.FILETIME),ctypes.POINTER(w.FILETIME),ctypes.POINTER(w.FILETIME),ctypes.POINTER(w.FILETIME)]
    if not kernel.GetProcessTimes(w.HANDLE(int(proc._handle)),ctypes.byref(created),ctypes.byref(exited),ctypes.byref(kt),ctypes.byref(ut)):return None
    stamp=lambda f:(f.dwHighDateTime<<32)+f.dwLowDateTime
    return dict(nano=time.perf_counter_ns(),cpu_nanos=(stamp(kt)+stamp(ut))*100)

class Isolated:
    def __init__(self,args,out):
        self.out=out;self.port=args.port;self.proc=None;self.started=False;self.owner=out.name;self.admin='fast-admin-'+self.owner;self.userprefix='fast-user-'+self.owner+'-'
        self.envfile=ROOT/'docker/credit_risk/.env';self.compose=ROOT/'docker/credit_risk/compose-v2-tests.yaml'
        info=json.loads(docker('inspect','credit-risk-v2-redis'))[0]
        if info['Config']['Labels'].get('com.docker.compose.project')!='credit-risk-v2-tests' or info['HostConfig']['RestartPolicy']['Name']!='no':raise RuntimeError('Redis owner/policy mismatch')
        with socket.socket() as s:s.bind(('127.0.0.1',self.port))
        try:
            if not info['State']['Running']:
                self.started=True
                docker('compose','-p','credit-risk-v2-tests','-f',str(self.compose),'--env-file',str(self.envfile),'up','-d','redis')
            claim="if redis.call('DBSIZE')==0 then redis.call('SET',KEYS[1],ARGV[1]); return 1 else return 0 end"
            command='export REDISCLI_AUTH="$REDIS_PASSWORD"; for i in $(seq 1 255); do x=$(redis-cli --no-auth-warning --raw -n "$i" EVAL '+shlex.quote(claim)+' 1 credit-risk:fast-ingress-owner '+shlex.quote(self.owner)+'); if [ "$x" = 1 ]; then printf "%s" "$i"; exit 0; fi; done; exit 1'
            self.rdb=int(docker('exec','credit-risk-v2-redis','sh','-c',command))
            commands=[f'HSET login:token:{self.admin} id 1 role ADMIN nickName fast-test']+[f'HSET login:token:{self.userprefix}{i} id {100000+i} role USER nickName fast-test' for i in range(128)]
            docker('exec','-i','credit-risk-v2-redis','sh','-c','export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --no-auth-warning --raw -n '+str(self.rdb),stdin='\n'.join(commands)+'\n')
            cpfile=ROOT/'ticket-risk-platform/target/benchmark-classpath.txt'
            if not cpfile.exists():raise RuntimeError('Missing dependency classpath; see run-fast-ingress.ps1 -Compile')
            cp=cpfile.read_text(encoding='utf-8-sig').strip();classes=ROOT/'ticket-risk-platform/target/fast-ingress-benchmark-classes'
            if not (classes/'benchmark/FastIngressApplication.class').exists():raise RuntimeError('Compile the test-only launcher first')
            values=dict(x.split('=',1) for x in self.envfile.read_text(encoding='utf-8-sig').splitlines() if '=' in x and not x.startswith('#'))
            env=os.environ.copy();env.update(SPRING_REDIS_HOST='127.0.0.1',SPRING_REDIS_PORT='26379',SPRING_REDIS_DATABASE=str(self.rdb),SPRING_REDIS_PASSWORD=values['CREDIT_RISK_REDIS_PASSWORD'])
            overlay=getattr(args,'class_overlay',None)
            extra=(str(pathlib.Path(overlay).resolve())+os.pathsep) if overlay else ''
            command=[str(JAVA/'java.exe'),'-Xms128m','-Xmx384m','-XX:+UseSerialGC','-Duser.timezone=UTC','-cp',str(classes)+os.pathsep+extra+str(ROOT/'ticket-risk-platform/target/classes')+os.pathsep+cp,getattr(args,'main_class','benchmark.FastIngressApplication'),
                '--spring.config.name=fast-ingress-benchmark', '--server.address=127.0.0.1',f'--server.port={self.port}', '--ticketing.reservations-v2.enabled=true','--fast.benchmark.isolated=true','--fast.benchmark.owner='+self.owner,f'--fast.benchmark.consumer-ms={args.consumer_ms}',
                '--management.endpoints.web.exposure.include=health,prometheus','--spring.main.banner-mode=off', '--fast.benchmark.diagnostics='+str(args.diagnostics).lower()]
            self.log=(out/'java.log').open('w',encoding='utf-8');self.proc=subprocess.Popen(command,cwd=ROOT,env=env,stdout=self.log,stderr=self.log,creationflags=subprocess.CREATE_NO_WINDOW)
            self.watchdog=threading.Timer(args.process_timeout,self.kill);self.watchdog.daemon=True;self.watchdog.start()
            end=time.monotonic()+90
            while time.monotonic()<end:
                if self.proc.poll() is not None:raise RuntimeError('Test Java exited; inspect java.log')
                try:
                    if http(self.port,'/actuator/health')[1].get('status')=='UP':break
                except (OSError,ValueError):pass
                time.sleep(.2)
            else:raise TimeoutError('Test Java startup deadline exceeded')
            save(out/'server.json',dict(mode='ISOLATED_TEST_ONLY_NO_RISK_NO_STOCK',pid=self.proc.pid,redis_db=self.rdb,java_args=command,capacity=256,workers=4,state_capacity=4096,post_rate=1000,per_user=4,consumer_ms=args.consumer_ms))
        except BaseException:self.close();raise
    def kill(self):
        if self.proc and self.proc.poll() is None:self.proc.kill()
    def close(self):
        if hasattr(self,'watchdog'):self.watchdog.cancel()
        self.kill()
        if self.proc:self.proc.wait(timeout=5)
        if hasattr(self,'log'):self.log.close()
        if self.started:docker('compose','-p','credit-risk-v2-tests','-f',str(self.compose),'--env-file',str(self.envfile),'stop','redis')
    def control(self,path,data=None):
        code,value=http(self.port,'/api/admin/fast-ingress/'+path,self.admin,data)
        if code!=200:raise RuntimeError((code,value))
        return value
    def probe(self):return self.control('probe')
    def diagnostic(self,traces=False,timeout=2):
        code,value=http(self.port,'/api/admin/fast-ingress/diagnostics?traces='+str(traces).lower(),self.admin,timeout=timeout)
        if code!=200:raise RuntimeError((code,value))
        return value
    def redis_info(self):
        return docker('exec','-i','credit-risk-v2-redis','sh','-c','export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --no-auth-warning --raw -n '+str(self.rdb),stdin='INFO commandstats\nINFO cpu\nINFO clients\n')
    def drain(self,seconds=8):
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            p=self.probe()
            if p['queue']==p['active']==0:return p
            time.sleep(.02)
        raise TimeoutError('Short isolated consumer drain timed out; no long drain is attempted')
    def fixture(self,i,rid):return dict(token=self.userprefix+str(i%128),body=dict(requestId=rid,userId=100000+i%128,eventSessionId='fast-session',ticketSkuId='fast-sku',quantity=1,deviceId='fast-device-'+str(i%128)))
    def reset(self):self.drain();return self.control('reset',{})

def run_window(port,duration,concurrency,rate,reuse,fixture,label,timeout=2,diagnostic=None,monitor_resources=True):
    work=queue.Queue(maxsize=concurrency);rows=[];mutex=threading.Lock();connections=0;skipped=0;at_stop=None
    live=dict(attempted=0,write_completed=0,response_completed=0,finished=0,timeouts=0,inflight=0,max_inflight=0)
    resources=[];stop_monitor=threading.Event()
    start=deadline=0.0
    def event(name,delta=1):
        if diagnostic:
            with mutex:
                live[name]+=delta
                if name=='inflight':live['max_inflight']=max(live['max_inflight'],live['inflight'])
    def observe():
        while not stop_monitor.wait(.5):
            try:
                with mutex:flow=dict(live);flow['client_queue_depth']=work.qsize()
                resources.append(dict(offset_ms=1000*(time.perf_counter()-start),flow=flow,client=client_resources(),server=diagnostic(False)))
            except Exception as e:resources.append(dict(error=str(e)))
    def worker(w):
        nonlocal connections,skipped
        conn=None;current_source=None
        try:
            while True:
                item=work.get()
                if item is None:return
                i,due,enqueued=item
                if time.perf_counter()>=deadline:
                    with mutex:skipped+=1
                    continue
                dequeued=time.perf_counter();f=fixture(i,label+'-'+str(i));source=f.get('source');begin=time.perf_counter();row=dict(i=i,requestId=f['body']['requestId'],begin_offset_ms=(begin-start)*1000,client_schedule_lag_ms=(begin-due)*1000,producer_delay_ms=max(0,(enqueued-due)*1000),client_queue_wait_ms=max(0,(dequeued-enqueued)*1000),client_connection_pool_wait_ms=0,write_completed=False,response_completed=False,timeout=False)
                event('attempted');event('inflight');failure_stage='prepare';connect_ms=0
                try:
                    encoded=json.dumps(f['body']);prepared=time.perf_counter()
                    if conn is None or source!=current_source:
                        if conn:conn.close()
                        conn=HTTPConnection('127.0.0.1',port,timeout=timeout,source_address=(source,0) if source else None);current_source=source
                    if conn.sock is None:
                        with mutex:connections+=1
                        failure_stage='connect';connect_start=time.perf_counter();conn.connect();connect_ms=1000*(time.perf_counter()-connect_start)
                    ready=time.perf_counter();failure_stage='write'
                    conn.request('POST','/api/tickets/reservations',encoded,{'Content-Type':'application/json','authorization':f['token'],'X-Fast-Trace':f['body']['requestId']})
                    written=time.perf_counter();row.update(write_completed=True,write_done_offset_ms=1000*(written-start));event('write_completed');failure_stage='read_headers'
                    r=conn.getresponse();headers=time.perf_counter();row['code']=r.status;failure_stage='read_body';raw=r.read();body_end=time.perf_counter();row.update(response_completed=True,response_done_offset_ms=1000*(body_end-start));event('response_completed')
                    failure_stage='decode';wire=json.loads(raw.decode('utf-8')) if raw else {};data=wire.get('data') or {};row.update(submitted=r.status==202 and data.get('newSubmission') is True,response=wire)
                    if diagnostic:row.update(client_prepare_ms=1000*(prepared-begin),client_connect_ms=connect_ms,client_request_write_ms=1000*(written-ready),client_wait_headers_ms=1000*(headers-written),client_read_body_ms=1000*(body_end-headers),client_decode_ms=1000*(time.perf_counter()-body_end))
                    if not reuse or r.will_close:conn.close();conn=None
                except Exception as error:
                    timed_out=isinstance(error,(TimeoutError,socket.timeout));row.update(code=row.get('code',0),submitted=False,error=str(error),failure_stage=failure_stage,timeout=timed_out)
                    if timed_out:event('timeouts')
                    if conn:conn.close();conn=None
                row.update(ms=1000*(time.perf_counter()-begin),end_offset_ms=1000*(time.perf_counter()-start))
                event('finished');event('inflight',-1)
                with mutex:rows.append(row)
        finally:
            if conn:conn.close()
    threads=[threading.Thread(target=worker,args=(i,),daemon=True) for i in range(concurrency)]
    for t in threads:t.start()
    # Thread creation is setup, not offered load. Never Sleep(0) for overdue slots:
    # on Windows that yields the producer and prevents it from catching up.
    start=time.perf_counter();deadline=start+duration
    client_before=client_resources()
    monitor=threading.Thread(target=observe,daemon=True) if diagnostic and monitor_resources else None
    if monitor:monitor.start()
    offered=int(duration*rate);missed=0
    for i in range(offered):
        due=start+i/rate;delay=due-time.perf_counter()
        if delay>0:time.sleep(delay)
        if time.perf_counter()-due>.05:missed+=1;continue
        try:work.put_nowait((i,due,time.perf_counter()))
        except queue.Full:
            with mutex:skipped+=1
    # Stop counters are derived again from per-request offsets at the exact load boundary.
    for t in threads:work.put(None,timeout=timeout+1)
    end=time.perf_counter()+timeout+2
    for t in threads:t.join(max(0,end-time.perf_counter()))
    stop_monitor.set()
    if monitor:monitor.join(timeout+1)
    if any(t.is_alive() for t in threads):raise TimeoutError('Bounded HTTP client did not stop')
    seconds=max(duration,max((r['end_offset_ms']/1000 for r in rows),default=0));submitted=sum(r['submitted'] for r in rows)
    sent=sum(r['write_completed'] for r in rows);completed=sum(r['response_completed'] for r in rows)
    assert offered==len(rows)+skipped+missed,(offered,len(rows),skipped,missed)
    counts=dict(target=offered,attempted=len(rows),write_completed=sent,response_completed=completed,not_attempted=skipped+missed,not_written=len(rows)-sent,inflight_at_load_end=sum(r['write_completed'] and r['write_done_offset_ms']<=duration*1000 and (not r['response_completed'] or r['response_done_offset_ms']>duration*1000) for r in rows),inflight_at_cleanup=0,timeouts=sum(r['timeout'] for r in rows),http_write_rate_during_load=sum(r['write_completed'] and r['write_done_offset_ms']<=duration*1000 for r in rows)/duration,http_completed_rate_during_load=sum(r['response_completed'] and r['response_done_offset_ms']<=duration*1000 for r in rows)/duration)
    return dict(label=label,duration_config_seconds=duration,response_window_seconds=seconds,concurrency=concurrency,offered_rate=rate,offered=offered,client_skipped=skipped,producer_late_skipped=missed,connections=connections,keep_alive=reuse,post_responses=len(rows),http_qps=completed/seconds,submitted=submitted,submitted_qps=submitted/seconds,status=dict(collections.Counter(str(r['code']) for r in rows)),rejected_429_fraction=sum(r['code']==429 for r in rows)/max(1,len(rows)),http_ms=pct([r['ms'] for r in rows]),submitted_http_ms=pct([r['ms'] for r in rows if r['submitted']]),client_schedule_lag_ms=pct([r['client_schedule_lag_ms'] for r in rows]),counts=counts,client_before=client_before,client_after=client_resources(),resource_samples=resources,requests=rows)

def checks(server,out):
    fixture=server.fixture(0,'check-'+uuid.uuid4().hex)
    a=http(server.port,'/api/tickets/reservations',data=fixture['body'],timeout=8);assert a[0] in (401,403),a
    invalid=dict(fixture['body'],quantity=0);b=http(server.port,'/api/tickets/reservations',fixture['token'],invalid,timeout=8);assert b[0]==400,b
    wrong=dict(fixture['body'],userId=999);c=http(server.port,'/api/tickets/reservations',fixture['token'],wrong,timeout=8);assert c[0]==403,c
    before=server.probe();one=http(server.port,'/api/tickets/reservations',fixture['token'],fixture['body']);two=http(server.port,'/api/tickets/reservations',fixture['token'],fixture['body']);after=server.drain()
    assert one[0]==two[0]==202 and one[1]['data']['newSubmission'] is True and two[1]['data']['newSubmission'] is False and after['consumed']-before['consumed']==1,(one,two,after)
    full_before=server.reset();server.control('pause?paused=true',{})
    try:
        def send(i):
            f=server.fixture(i,'full-'+str(i));return http(server.port,'/api/tickets/reservations',f['token'],f['body'])
        with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:full=list(pool.map(send,range(300)))
        assert any(code==429 for code,value in full),collections.Counter(x[0] for x in full)
        assert all(code in (202,429) for code,value in full)
    finally:server.control('pause?paused=false',{})
    full_after=server.drain();assert full_after['consumed']-full_before['consumed']==sum(code==202 for code,value in full)
    server.reset();save(out/'functional.json',dict(unauthenticated=a[0],invalid_parameter=b[0],identity_mismatch=c[0],duplicate_consumed=1,full_status=dict(collections.Counter(str(x[0]) for x in full)),full_consumed=full_after['consumed']-full_before['consumed'],consumer_thread=after['consumerThread']))

def main():
    p=argparse.ArgumentParser();p.add_argument('--mode',choices=['isolated','real'],default='isolated');p.add_argument('--port',type=int,default=18088);p.add_argument('--duration',type=int,default=5);p.add_argument('--steps',default='16:500,32:1000,64:2000,128:5000');p.add_argument('--consumer-ms',type=int,default=0);p.add_argument('--connections',choices=['reuse','close'],default='reuse');p.add_argument('--fixture',type=pathlib.Path);p.add_argument('--process-timeout',type=int,default=180);p.add_argument('--diagnostics',action='store_true');args=p.parse_args()
    assert 5<=args.duration<=10 and 60<=args.process_timeout<=300
    steps=[tuple(map(int,x.split(':'))) for x in args.steps.split(',')];assert len(steps)<=6 and all(1<=c<=128 and 1<=r<=10000 for c,r in steps)
    out=ROOT/'performance/results'/('fast-ingress-'+time.strftime('%Y%m%d_%H%M%S')+'-'+uuid.uuid4().hex[:4]);out.mkdir(parents=True)
    args_dict=vars(args).copy();args_dict['fixture']=str(args.fixture) if args.fixture else None;save(out/'config.json',args_dict)
    server=None;started=time.perf_counter();results=[]
    try:
        if args.mode=='isolated':
            server=Isolated(args,out);checks(server,out);fixture=server.fixture;warm=run_window(args.port,1,8,64,True,fixture,'warm');server.reset();save(out/'warm.json',warm)
        else:
            if args.port!=18086 or not args.fixture:raise RuntimeError('Real mode requires explicit dedicated test fixture and port 18086; never targets developer port 18082')
            f=json.loads(args.fixture.read_text(encoding='utf-8'));assert f['environment']=='credit-risk-dedicated-test' and len(f['requests'])>=sum(args.duration*r for c,r in steps)
            assert http(args.port,'/api/tickets/reservation-mode')[1]['data']['receiptSemantics']=='VOLATILE_ASYNC_RECEIPT'
            offset=0
            def fixture(i,rid):
                row=dict(f['requests'][offset+i]);row['body']=dict(row['body'],requestId=rid);return row
        for index,(concurrency,rate) in enumerate(steps):
            label=f'{out.name}-{args.mode}-{index}-c{concurrency}-r{rate}';before=server.reset() if server else None
            diag_before=server.diagnostic(timeout=8) if server and args.diagnostics else None
            redis_before=server.redis_info() if diag_before else None
            java_cpu_before=owned_java_cpu(server.proc) if server else None
            result=run_window(args.port,args.duration,concurrency,rate,args.connections=='reuse',fixture,label,diagnostic=server.diagnostic if diag_before else None)
            result['java_native_cpu_before']=java_cpu_before;result['java_native_cpu_after']=owned_java_cpu(server.proc) if server else None
            result['mode']='ISOLATED_NO_RISK_NO_STOCK' if server else 'REAL_BACKGROUND_HTTP_ONLY_NOT_RESERVATION_TPS'
            if server:
                after=server.drain();result.update(server_before=before,server_after=after,consumed=after['consumed']-before['consumed'])
                actual_submitted=sum(v-before['counters'].get(k,0) for k,v in after['counters'].items() if k.startswith('reservation.ingress.submitted['))
                result['server_actual_submitted']=actual_submitted;result['consumption_verified']=actual_submitted==result['consumed']
                count=after['timedCalls']-before['timedCalls'];result['before_controller_mean_ms']=(after['beforeControllerNanos']-before['beforeControllerNanos'])/max(1,count)/1e6;result['controller_mean_ms']=(after['controllerNanos']-before['controllerNanos'])/max(1,count)/1e6
                result['rejection_reason_counters']={k:v-before['counters'].get(k,0) for k,v in after['counters'].items() if 'reservation.ingress.rejected' in k and v!=before['counters'].get(k,0)}
                result['queue_full_count']=sum(v for k,v in result['rejection_reason_counters'].items() if 'reason=queue_full' in k)
                result['queue_full_fraction']=result['queue_full_count']/max(1,result['post_responses'])
                if diag_before:
                    result['diagnostic_before']=diag_before
                    code,diag_after=http(server.port,'/api/admin/fast-ingress/diagnostics?traces=true',server.admin,timeout=8)
                    assert code==200,diag_after
                    result['diagnostic_after']=diag_after;result['redis_info_before']=redis_before;result['redis_info_after']=server.redis_info()
            else:offset+=args.duration*rate
            save(out/(label+'.json'),result)
            if server:assert result['consumption_verified'], 'Server submit/consume counts differ; raw results retained'
            results.append({k:v for k,v in result.items() if k not in ('requests','server_before','server_after','diagnostic_before','diagnostic_after','resource_samples','redis_info_before','redis_info_after')});print(json.dumps(results[-1],ensure_ascii=True),flush=True)
        save(out/'summary.json',results)
    finally:
        if server:server.close()
        save(out/'cleanup.json',dict(own_java_exited=server is None or server.proc is None or server.proc.poll() is not None,restored_container_initial_state=True,elapsed_seconds=time.perf_counter()-started,real_server_owned=False))
        print('Artifacts:',out,flush=True)
if __name__=='__main__':main()
