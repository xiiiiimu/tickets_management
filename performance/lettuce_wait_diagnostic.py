"""Standalone Redis/JFR diagnostic plus ONE 5s HTTP comparison. Manual use only."""
import json,os,pathlib,subprocess,threading,time,uuid
from types import SimpleNamespace
import fast_ingress_benchmark as f
from jmeter_ingress_crosscheck import fingerprint,process_memory

def redis(db,commands):
    return f.docker('exec','-i','credit-risk-v2-redis','sh','-c','export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --no-auth-warning --raw -n '+str(db),stdin=commands+'\n')
def snapshot(db):return redis(db,'INFO commandstats\nINFO clients\nINFO cpu\nINFO memory\nLATENCY LATEST\nSLOWLOG GET 10')
def waitfile(path,proc,seconds=40):
    deadline=time.monotonic()+seconds
    while not path.exists():
        if proc.poll() is not None:raise RuntimeError('Probe exited: inspect java log')
        if time.monotonic()>deadline:raise TimeoutError('Finite handshake deadline '+path.name)
        time.sleep(.1)
def monitor(proc,stop,rows,pressure):
    high=0;previous=None
    while not stop.wait(.5):
        host=f.client_resources();cpu=f.owned_java_cpu(proc);memory=process_memory(proc);cores=0
        if previous and cpu:cores=(cpu['cpu_nanos']-previous['cpu_nanos'])/max(1,cpu['nano']-previous['nano'])
        previous=cpu;rows.append(dict(host=host,java_cpu=cpu,java_memory=memory,cpu_cores=cores))
        high=high+1 if host.get('host_memory_available',10**12)<256*1024*1024 or memory.get('rss',0)>768*1024*1024 or cores>8 else 0
        if high>=3:pressure.append('resource safety threshold reached');return
def docker_sample(path):
    try:path.write_text(f.docker('stats','--no-stream','--format','{{json .}}','credit-risk-v2-redis'),encoding='utf-8')
    except Exception as e:path.write_text(str(e),encoding='utf-8')
def run():
    initial=json.loads(f.docker('inspect','credit-risk-v2-redis'))[0]
    if not initial['State']['Running']:raise RuntimeError('Redis must already be running; no containers are started')
    out=f.ROOT/'performance/results'/('lettuce-wait-'+time.strftime('%Y%m%d_%H%M%S')+'-'+uuid.uuid4().hex[:4]);out.mkdir(parents=True)
    before=fingerprint();f.save(out/'production-before.json',before);print('RESULT_DIRECTORY='+str(out),flush=True)
    (out/'docker-engine.txt').write_text(f.docker('info','--format','{{.OperatingSystem}} {{.KernelVersion}} {{.NCPU}} {{.MemTotal}}'))
    owner=out.name;key='credit-risk:lettuce-probe:'+owner;db=None;proc=None;app=None;pressure=[];completed=[]
    env=os.environ.copy();values=dict(x.split('=',1) for x in (f.ROOT/'docker/credit_risk/.env').read_text(encoding='utf-8-sig').splitlines() if '=' in x and not x.startswith('#'))
    try:
        claim="if redis.call('DBSIZE')==0 then redis.call('SET',KEYS[1],ARGV[1]);return 1 else return 0 end"
        import shlex
        cmd='export REDISCLI_AUTH="$REDIS_PASSWORD"; for i in $(seq 1 255); do x=$(redis-cli --no-auth-warning --raw -n "$i" EVAL '+shlex.quote(claim)+' 1 credit-risk:lettuce-probe-owner '+shlex.quote(owner)+'); if [ "$x" = 1 ]; then printf "%s" "$i"; exit 0; fi; done; exit 1'
        db=int(f.docker('exec','credit-risk-v2-redis','sh','-c',cmd))
        env.update(SPRING_REDIS_HOST='127.0.0.1',SPRING_REDIS_PORT='26379',SPRING_REDIS_DATABASE=str(db),SPRING_REDIS_PASSWORD=values['CREDIT_RISK_REDIS_PASSWORD'])
        cp=str(f.ROOT/'.tmp/lettuce-wait-classes')+os.pathsep+(f.ROOT/'ticket-risk-platform/target/benchmark-classpath.txt').read_text(encoding='utf-8-sig').strip()
        command=[str(f.JAVA/'java.exe'),'-Xms128m','-Xmx384m','-XX:+UseSerialGC','-cp',cp,'LettuceWaitProbe',str(out),key]
        f.save(out/'invocation.json',command);log=(out/'standalone.log').open('w',encoding='utf-8');proc=subprocess.Popen(command,cwd=f.ROOT,env=env,stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW)
        watchdog=threading.Timer(240,proc.kill);watchdog.daemon=True;watchdog.start()
        try:
            for c in (1,8,32,64):
                for mode in ('HGETALL','PEXPIRE','PAIR'):
                    label=mode+'-'+str(c);waitfile(out/(label+'.ready'),proc,45)
                    (out/(label+'-redis-before.txt')).write_text(snapshot(db));samples=[];stop=threading.Event();observer=threading.Thread(target=monitor,args=(proc,stop,samples,pressure),daemon=True);observer.start()
                    dt=threading.Thread(target=docker_sample,args=(out/(label+'-docker.json'),),daemon=True);dt.start();(out/(label+'.go')).write_text('go')
                    waitfile(out/(label+'.done'),proc,12);stop.set();observer.join(2);dt.join(4)
                    (out/(label+'-redis-after.txt')).write_text(snapshot(db));f.save(out/(label+'-resources.json'),samples)
                    r=json.loads((out/(label+'.json')).read_text());completed.append(label);print(json.dumps(dict(label=label,qps=r['qps'],p95=r['operation']['p95_ms'],errors=r['errors'])),flush=True)
                    if pressure or r['errors']>=3:(out/'STOP').write_text('resource or timeout guard')
                    (out/(label+'.next')).write_text('next')
                    if pressure or r['errors']>=3:break
                if pressure:break
            proc.wait(15)
            if proc.returncode:raise RuntimeError('Standalone probe failed: standalone.log')
        finally:
            watchdog.cancel()
            if proc.poll() is None:proc.kill();proc.wait(5)
            log.close()
        if not pressure:
            httpdir=out/'http-64';httpdir.mkdir();app=f.Isolated(SimpleNamespace(port=18088,consumer_ms=0,process_timeout=90,diagnostics=True),httpdir)
            # JFR attach belongs only to the owned test JVM. Start after its boot, before warmup.
            jcmd=[str(f.JAVA/'jcmd.exe'),str(app.proc.pid)]
            attach=subprocess.run(jcmd+['JFR.start','name=rediswait','settings=profile','filename='+str(httpdir/'http.jfr'),'dumponexit=true'],capture_output=True,text=True,timeout=15)
            (httpdir/'jfr-start.log').write_text(attach.stdout+attach.stderr);attach.check_returncode()
            f.run_window(app.port,2,8,100,True,app.fixture,'http-warmup-'+uuid.uuid4().hex[:8],monitor_resources=False)
            app.drain(3);pb=app.control('reset',{});(httpdir/'redis-before.txt').write_text(snapshot(app.rdb));before_diag=app.diagnostic(timeout=8)
            rows=[];stop=threading.Event();observer=threading.Thread(target=monitor,args=(app.proc,stop,rows,pressure),daemon=True);observer.start();dt=threading.Thread(target=docker_sample,args=(httpdir/'docker.json',),daemon=True);dt.start();start=time.time()
            result=f.run_window(app.port,5,64,700,True,app.fixture,'http-measured-'+uuid.uuid4().hex[:8],diagnostic=app.diagnostic,monitor_resources=False);end=time.time();stop.set();observer.join(2);dt.join(4)
            raw=result.pop('requests')
            for row in raw:row.pop('response',None)
            f.save(httpdir/'requests.json',raw);probe=app.drain(3);diag=app.diagnostic(traces=True,timeout=8)
            result.update(start_epoch=start,end_epoch=end,probe_before=pb,probe_after=probe,before=before_diag,after=diag,resources=rows);f.save(httpdir/'result.json',result)
            assert result['submitted']==probe['consumed']-pb['consumed']
            (httpdir/'redis-after.txt').write_text(snapshot(app.rdb));dump=subprocess.run(jcmd+['JFR.stop','name=rediswait','filename='+str(httpdir/'http.jfr')],capture_output=True,text=True,timeout=15);(httpdir/'jfr-stop.log').write_text(dump.stdout+dump.stderr);dump.check_returncode()
            print(json.dumps(dict(label='HTTP-64',qps=result['http_qps'],submitted=result['submitted'])),flush=True)
    finally:
        if app:
            app.close()
            keys=['credit-risk:fast-ingress-owner','login:token:'+app.admin]+['login:token:'+app.userprefix+str(i) for i in range(128)]
            redis(app.rdb,'DEL '+' '.join(keys))
        if proc and proc.poll() is None:proc.kill();proc.wait(5)
        if db is not None:redis(db,'DEL '+key+' credit-risk:lettuce-probe-owner')
        f.save(out/'cleanup.json',dict(production_unchanged=fingerprint()==before,standalone_exited=not proc or proc.poll() is not None,http_exited=not app or app.proc.poll() is not None,completed=completed,resource_stop=pressure,standalone_fixture_remaining=redis(db,'EXISTS '+key) if db is not None else None,redis_started_at_unchanged=json.loads(f.docker('inspect','credit-risk-v2-redis'))[0]['State']['StartedAt']==initial['State']['StartedAt']))
    print('DONE '+str(out),flush=True)

if __name__=='__main__':run()
