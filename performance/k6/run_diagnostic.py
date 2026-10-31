"""Bounded local k6 sender validation; ingress is strictly gated on tiny HTTP passing."""
import hashlib,json,os,pathlib,queue,shutil,socket,subprocess,sys,threading,time,uuid
from types import SimpleNamespace
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
import fast_ingress_benchmark as f
from jmeter_ingress_crosscheck import fingerprint,process_memory

HERE=pathlib.Path(__file__).resolve().parent
K6=f.ROOT/'.tools/k6-v2.3.0-windows-amd64/k6.exe'

def save(p,v):f.save(p,v)
def observation(proc):return dict(clock=time.perf_counter_ns(),cpu=f.owned_java_cpu(proc),memory=process_memory(proc))
def cpu_cores(a,b):return (b['cpu']['cpu_nanos']-a['cpu']['cpu_nanos'])/max(1,b['cpu']['nano']-a['cpu']['nano'])

def load(out,label,rate,url,server,extra=None):
    folder=out/label;folder.mkdir();env=os.environ.copy();env.update(RATE=str(rate),VUS='128',URL=url,KIND='tiny',SUMMARY_PATH=str(folder/'k6-summary.json'),RUN_ID=label+'-'+uuid.uuid4().hex[:12],K6_NO_USAGE_REPORT='true',K6_NO_COLOR='true')
    if extra:env.update(extra)
    command=[str(K6),'run','--quiet','--address','127.0.0.1:0',str(HERE/'arrival.js')]
    save(folder/'configuration.json',dict(command=command,rate=rate,duration=5,preAllocatedVUs=int(env['VUS']),maxVUs=int(env['VUS']),url=url,kind=env['KIND'],gracefulStop=3,requestTimeout=2))
    log=(folder/'console.log').open('w',encoding='utf-8');server_before=observation(server);host_before=f.client_resources()
    proc=subprocess.Popen(command,cwd=HERE,env=env,stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW);client_before=observation(proc);resources=[];guard=[];stop=threading.Event()
    def observe():
        high=0;previous_host=None
        while not stop.wait(.5):
            host=f.client_resources();client=observation(proc);target=observation(server);resources.append(dict(host=host,client=client,server=target))
            host_cpu=0
            if previous_host and 'host_cpu_100ns' in host:
                total=host['host_cpu_100ns']-previous_host['host_cpu_100ns'];idle=host['host_idle_100ns']-previous_host['host_idle_100ns'];host_cpu=100*(1-idle/max(1,total))
            previous_host=host
            high=high+1 if host.get('host_memory_available',10**12)<256*1024*1024 or client['memory'].get('rss',0)>768*1024*1024 or host_cpu>=97 else 0
            if high>=3:
                guard.append('available RAM below256MiB, k6 RSS above768MiB, or host CPU>=97% for3 samples');proc.kill();return
    monitor=threading.Thread(target=observe,daemon=True);monitor.start()
    try:
        proc.wait(timeout=25);client_after=observation(proc);server_after=observation(server)
    finally:
        stop.set();monitor.join(2)
        if proc.poll() is None:proc.kill();proc.wait(5)
        log.close();save(folder/'resource-samples.json',resources)
    if proc.returncode or not (folder/'k6-summary.json').exists():
        save(folder/'failure.json',dict(exit_code=proc.returncode,resource_guard=guard));raise RuntimeError('k6 failed or resource guard: '+str(folder))
    raw=json.loads((folder/'k6-summary.json').read_text());metrics=raw['metrics']
    def count(name):return metrics.get(name,{}).get('values',{}).get('count',0)
    seconds=max(5,metrics.get('completion_offset_ms',{}).get('values',{}).get('max',5000)/1000)
    timing=metrics['http_req_duration']['values'];attempted=count('request_attempts');completed=count('response_completed');dropped=count('dropped_iterations')
    r=dict(label=label,target_rate=rate,target_requests=rate*5,attempted=attempted,http_requests_metric=count('http_reqs'),completed=completed,dropped_iterations=dropped,target_deficit=rate*5-attempted,
           response_window_seconds=seconds,http_qps=completed/seconds,nominal_completed_per_5s=completed/5,submitted=count('submitted_202'),submitted_qps=count('submitted_202')/seconds,rejected=count('status_429'),rejected_qps=count('status_429')/seconds,
           status200=count('status_200'),status401=count('status_401'),other=count('status_other'),transport_errors=count('transport_errors'),timeouts=count('request_timeouts'),
           p50=timing['med'],p95=timing['p(95)'],p99=timing['p(99)'],http_call_ms=metrics['http_call_ms']['values'],client_cpu_cores=cpu_cores(client_before,client_after),server_cpu_cores=cpu_cores(server_before,server_after),
           client_peak_sampled_rss=max([s['client']['memory'].get('rss',0) for s in resources]+[0]),server_peak_sampled_rss=max([s['server']['memory'].get('rss',0) for s in resources]+[0]),
           observation_seconds=(server_after['clock']-server_before['clock'])/1e9,host_before=host_before,host_after=f.client_resources(),resource_guard=guard,owned_k6_exited=proc.poll() is not None)
    save(folder/'result.json',r);print(json.dumps({k:r[k] for k in ('label','http_qps','submitted_qps','completed','dropped_iterations','p95','p99','client_cpu_cores')}),flush=True);return r

def run():
    out=f.ROOT/'performance/results'/('k6-diagnostic-'+time.strftime('%Y%m%d_%H%M%S')+'-'+uuid.uuid4().hex[:4]);out.mkdir(parents=True);before=fingerprint();save(out/'production-before.json',before)
    version=subprocess.check_output([str(K6),'version'],text=True).strip();save(out/'environment.json',dict(k6=version,node=subprocess.check_output(['node','--version'],text=True).strip(),host=f.client_resources(),head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),working_tree='dirty, preserved',sources={str(p.relative_to(f.ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in HERE.glob('*') if p.is_file()},k6_sha256=hashlib.sha256(K6.read_bytes()).hexdigest()))
    print('RESULT_DIRECTORY='+str(out),flush=True);node=None;app=None;passed=False;pure=[];ingress=[]
    try:
        with socket.socket() as sock:sock.bind(('127.0.0.1',18089))
        env=os.environ.copy();env['TINY_PORT']='18089';node=subprocess.Popen([shutil.which('node'),str(HERE/'tiny-http.cjs')],cwd=HERE,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=(out/'tiny-stderr.log').open('w'),text=True,encoding='utf-8',creationflags=subprocess.CREATE_NO_WINDOW)
        replies=queue.Queue()
        def read():
            for line in node.stdout:replies.put(json.loads(line))
        reader=threading.Thread(target=read,daemon=True);reader.start();ready=replies.get(timeout=10);assert ready['ready']
        def snapshot():node.stdin.write('snapshot\n');node.stdin.flush();return replies.get(timeout=3)
        for rate in (1000,2000,5000):
            sb=snapshot();r=load(out,'tiny-'+str(rate),rate,'http://127.0.0.1:18089/',node);sa=snapshot();r.update(server_received=sa['requests']-sb['requests'],server_finished=sa['finished']-sb['finished'],server_before=sb,server_after=sa);save(out/('tiny-'+str(rate))/'result.json',r);pure.append(r)
            if r['resource_guard'] or r['transport_errors'] or r['other'] or r['status200']!=r['completed']:break
        final=pure[-1];passed=final['target_rate']==5000 and final['http_qps']>=4900 and final['dropped_iterations']<=125 and final['status200']==final['completed']==final['server_received'] and not final['transport_errors'] and not final['other']
        save(out/'stage1-gate.json',dict(passed=passed,criterion='5000 tier >=4900 completed/s, <=125 dropped iterations, zero errors, all responses200 and server count reconciles',rounds=pure))
        node.kill();node.wait(5)
        if not passed:
            print('STAGE1_NOT_PROVEN: ingress not started',flush=True);return
        # This branch alone touches the dedicated Redis test fixture.
        import auth_mode_comparison as auth
        from lettuce_wait_diagnostic import redis
        initial=json.loads(f.docker('inspect','credit-risk-v2-redis'))[0]
        if not initial['State']['Running']:raise RuntimeError('Dedicated Redis must already be running')
        root=out/'ingress';root.mkdir();app=f.Isolated(SimpleNamespace(port=18088,consumer_ms=0,process_timeout=180,diagnostics=True,class_overlay=str(f.ROOT/'.tmp/auth-mode-classes'),main_class='benchmark.AuthModeIngress'),root);auth.token_ttls(app,True)
        for mode in ('C','A'):
            auth.mode(app,mode);pb=auth.reset(app);fixture=app.fixture(0,'k6-smoke-'+mode+'-'+uuid.uuid4().hex[:8]);code,reply=f.http(app.port,'/api/tickets/reservations',fixture['token'],fixture['body'],timeout=8);assert code==202
            pa=app.drain(3);d=app.diagnostic(timeout=8);assert auth.counts(d)=={'hGetAll':0 if mode=='C' else 1,'pExpire':0 if mode=='C' else 1};assert pa['consumed']-pb['consumed']==1
        for rate,modes in ((1000,('C','A')),(2000,('A','C')),(5000,('C','A'))):
            for mode in modes:
                auth.mode(app,mode);auth.token_ttls(app);pb=auth.reset(app);before_diag=app.diagnostic(timeout=8)
                r=load(out,mode+'-'+str(rate),rate,'http://127.0.0.1:18088/api/tickets/reservations',app.proc,dict(KIND='ingress',TOKEN_PREFIX=app.userprefix));pa=app.drain(3);diag=app.diagnostic(traces=True,timeout=8)
                r.update(mode=mode,consumed=pa['consumed']-pb['consumed'],probe_before=pb,probe_after=pa,before=before_diag,after=diag,redis_calls=auth.counts(diag));save(out/(mode+'-'+str(rate))/'result.json',r)
                assert r['consumed']==r['submitted'];expected=r['completed'] if mode=='A' else 0;assert r['redis_calls']=={'hGetAll':expected,'pExpire':expected},r['redis_calls'];ingress.append(r)
    finally:
        if node and node.poll() is None:node.kill();node.wait(5)
        if app:
            app.close();from lettuce_wait_diagnostic import redis
            keys=['credit-risk:fast-ingress-owner','login:token:'+app.admin]+['login:token:'+app.userprefix+str(i) for i in range(128)];redis(app.rdb,'DEL '+' '.join(keys))
        save(out/'cleanup.json',dict(production_unchanged=fingerprint()==before,tiny_exited=not node or node.poll() is not None,ingress_started=app is not None,ingress_exited=not app or app.proc.poll() is not None,stage1_passed=passed))
        print('DONE '+str(out),flush=True)

if __name__=='__main__':run()
