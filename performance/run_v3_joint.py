"""One authorized 30s joint run; no reset, no rerun, no business configuration changes."""
import argparse, ctypes, hashlib, json, os, queue, re, subprocess, threading, time, uuid
from pathlib import Path
from v3_local_env import Environment, ROOT, JAVA, free_memory, save

def prom():
    import urllib.request
    with urllib.request.urlopen('http://127.0.0.1:18083/actuator/prometheus',timeout=4) as r:return r.read().decode()

def selected(raw):
    prefixes=('http_server_requests_seconds_count','attempt_v3_','attempt_processing_worker_','reservation_v2_confirmed_',
              'hikaricp_','jvm_gc_pause_','process_cpu_usage','system_cpu_usage','jvm_memory_used_bytes','executor_','attempt_transaction_','attempt_path_')
    out={}
    for line in raw.splitlines():
        if line.startswith(prefixes):
            try:k,v=line.rsplit(' ',1);out[k]=float(v)
            except ValueError:pass
    return out

def resources(pid):
    from ctypes import wintypes as w
    k=ctypes.WinDLL('kernel32',use_last_error=True);p=ctypes.WinDLL('psapi',use_last_error=True)
    k.OpenProcess.restype=w.HANDLE;k.OpenProcess.argtypes=[w.DWORD,w.BOOL,w.DWORD]
    k.CloseHandle.argtypes=[w.HANDLE]
    k.GetProcessTimes.argtypes=[w.HANDLE,ctypes.POINTER(w.FILETIME),ctypes.POINTER(w.FILETIME),ctypes.POINTER(w.FILETIME),ctypes.POINTER(w.FILETIME)]
    class Mem(ctypes.Structure):
        _fields_=[('cb',w.DWORD),('faults',w.DWORD)]+[(n,ctypes.c_size_t) for n in ('peak','rss','peakPaged','paged','peakNonPaged','nonPaged','pagefile','peakPagefile','private')]
    p.GetProcessMemoryInfo.argtypes=[w.HANDLE,ctypes.POINTER(Mem),w.DWORD]
    h=k.OpenProcess(0x1000|0x10,False,pid)
    if not h:return {'pid':pid,'unavailable':True}
    try:
        fs=[w.FILETIME() for _ in range(4)];ok=k.GetProcessTimes(h,*[ctypes.byref(x) for x in fs])
        num=lambda f:(f.dwHighDateTime<<32)+f.dwLowDateTime
        m=Mem();m.cb=ctypes.sizeof(m);p.GetProcessMemoryInfo(h,ctypes.byref(m),m.cb)
        return dict(pid=pid,cpu_seconds=(num(fs[2])+num(fs[3]))/1e7 if ok else None,rss=m.rss,private=m.private)
    finally:k.CloseHandle(h)

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--run',type=Path,required=True);ap.add_argument('--execute',action='store_true');ap.add_argument('--prepared',type=Path);a=ap.parse_args()
    if not a.execute:raise RuntimeError('Explicit --execute required')
    env=Environment(a.run)
    prepared=None
    if a.prepared:
        prepared=a.prepared.resolve()
        if prepared.parent!=env.path/'benchmarks':raise RuntimeError('Prepared directory outside owned run')
        old=json.loads((prepared/'configuration.json').read_text())
        summary=json.loads((prepared/'k6-summary.json').read_text())
        if summary.get('k6',{}).get('metrics',{}).get('http_reqs',{}).get('values',{}).get('count',0)!=0:raise RuntimeError('Never repeat an actual HTTP load')
        if env.sql(f"SELECT COUNT(*) FROM tb_reservation_job WHERE session_id='{old['session']}';")!='0':raise RuntimeError('Prepared scene already used')
    elif env.state.get('formal_test_started'):raise RuntimeError('Formal arena already used; no automatic second run')
    if not env.state.get('verification_passed'):raise RuntimeError('Owned correctness verification missing')
    env.assert_container('mysql');env.assert_container('redis')
    if free_memory()['available']<1536*1024**2:raise RuntimeError('Need >=1536 MiB available RAM for bounded 512-VU joint test')
    if env.http('GET','/actuator/health')[0]!=200:raise RuntimeError('Spring not healthy')
    if env.sql('SELECT owner FROM tb_v3_bench_owner;')!=env.owner:raise RuntimeError('Schema owner mismatch')
    buyers=json.loads((env.path/'fixtures.json').read_text())['requests']
    if len(buyers)!=5000 or len({u['userId'] for u in buyers})!=5000:raise RuntimeError('5000 unique buyers required')
    with env.redis() as r:
        r.owned()
        for offset in range(0,5000,200):
            batch=buyers[offset:offset+200];commands=[]
            for u in batch:commands.extend([('HMGET','login:token:'+u['token'],'id','role'),('TTL','login:token:'+u['token'])])
            r.socket.sendall(b''.join(r.encode(c) for c in commands))
            for u in batch:
                identity,ttl=r.read(),r.read()
                if identity!=[str(u['userId']),'USER'] or ttl<180:raise RuntimeError('Expired/invalid test login; no load sent')
        cfg=r.call('HGETALL','{tkv3}:cfg')
        if int(r.call('XLEN','{tkv3}:stream'))!=0 or int(r.call('ZCARD','{tkv3}:active'))!=0:raise RuntimeError('Existing unfinished V3 work; no load')
    out=env.path/'benchmarks'/('joint-'+uuid.uuid4().hex[:10]);out.mkdir(parents=True)
    print('Joint artifacts: '+str(out),flush=True)
    cp=(ROOT/'ticket-risk-platform/target/benchmark-classpath.txt').read_text(encoding='utf-8-sig').strip()
    classes=out/'probe-classes';classes.mkdir()
    compile_result=subprocess.run([str(JAVA/'javac.exe'),'-cp',cp,'-d',str(classes),str(ROOT/'performance/V3JointProbe.java')],capture_output=True,timeout=45,creationflags=subprocess.CREATE_NO_WINDOW)
    (out/'probe-compile.log').write_bytes(compile_result.stdout+compile_result.stderr)
    if compile_result.returncode:raise RuntimeError('Read-only observer compile failed before formal run')
    # No performance request before this one-shot owned arena allocation.
    if prepared:
        session=old['session'];t0=int(time.time()+45)*1000
        fixtures=out/'fixtures.json';fixtures.write_bytes((prepared/'fixtures.json').read_bytes())
        save(out/'preload-recovery.json',dict(source=str(prepared),reason='Unsupported CLI flag, then missed setup T0; both invocations sent zero HTTP requests. Rebase test T0 only; no inventory or business state reset.'))
    else:session,t0,fixtures=env.load_arena(out)
    save(out/'configuration.json',dict(target_rate=5000,seconds=30,target_requests=150000,vus=512,session=session,t0=t0,
        schema=env.state['schema'],owner=env.owner,jar_sha256=env.state['jar_sha256'],gateway='NONE: direct Spring',
        readiness_memory=free_memory(),redis_cfg=cfg,deadline_drain_seconds=90,
        stable_intent_per_buyer=True,first_wave_buyers=5000,backoff_ms=50,request_timeout_ms=2000,
        limit_note='Existing POST 100000/s; dynamic budget, hard capacity512 and active16 unchanged',
        spring_qps_boundary='Micrometer completed POST requests, not server socket-arrival timestamps',
        held_boundary='Read-only SQL consistent snapshot: committed visibility, sampled time upper bounds',
        hashes={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [ROOT/'performance/V3JointProbe.java',ROOT/'performance/k6/v3_joint.js',Path(__file__)]}))
    probe=None;k6=None;stats=None;stop=threading.Event();failure=[];samples=[];sql_samples=[];prom_samples=[];q=queue.Queue()
    handles=[]
    def file(name):
        h=(out/name).open('wb');handles.append(h);return h
    child=os.environ.copy();child['V3_PROBE_PASSWORD']=env.private['V3_MYSQL_PASSWORD']
    try:
        probe=subprocess.Popen([str(JAVA/'java.exe'),'-Xms16m','-Xmx96m','-cp',str(classes)+';'+cp,'V3JointProbe',env.state['schema'],env.owner,session],env=child,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=file('probe.stderr.log'),text=True,creationflags=subprocess.CREATE_NO_WINDOW)
        def readprobe():
            for line in probe.stdout:
                if line.startswith('V3_PROBE '):q.put(json.loads(line[9:]))
        threading.Thread(target=readprobe,daemon=True).start()
        if not q.get(timeout=12).get('ready'):raise RuntimeError('JDBC observer not ready')
        def snapshot(i):
            probe.stdin.write(str(i)+'\n');probe.stdin.flush();v=q.get(timeout=7)
            if not v.get('ok'):raise RuntimeError('Read-only SQL snapshot failed: '+str(v))
            v['relative_ms']=v['end_epoch_ms']-t0;sql_samples.append(v)
            if v['mysql_remaining']+v['allocated']!=5000 or v['min_bucket']<0 or v['held_tickets']>5000 or v['held_buyers']!=v['held_orders'] or v['held_tickets']!=v['allocated']:raise RuntimeError('Committed stock/order invariant violation')
            return v
        snapshot('before')
        raw=prom();(out/'metrics-before.prom').write_text(raw);prom_samples.append(dict(epoch_ms=time.time()*1000,metrics=selected(raw)))
        with env.redis() as r:save(out/'redis-before.json',dict(commandstats=r.call('INFO','commandstats'),stream=r.call('XINFO','STREAM','{tkv3}:stream')))
        save(out/'mysql-before.json',dict(status=env.sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_log_waits','Threads_running','Binlog_commits','Binlog_group_commits');")))
        stats=subprocess.Popen(['docker','stats','--format','{{json .}}',env.state['mysql_container'],env.state['redis_container']],stdout=file('docker-stats.jsonl'),stderr=file('docker-stats.stderr.log'),creationflags=subprocess.CREATE_NO_WINDOW)
        def monitor():
            try:
                with env.redis() as r:
                    r.owned();r.socket.settimeout(4)
                    while not stop.is_set():
                        now=time.time()*1000
                        commands=[('XINFO','STREAM','{tkv3}:stream'),('XLEN','{tkv3}:stream'),('ZCARD','{tkv3}:waiting'),('ZCARD','{tkv3}:active'),('XINFO','GROUPS','{tkv3}:stream')]
                        r.socket.sendall(b''.join(r.encode(c) for c in commands));values=[r.read() for _ in commands]
                        samples.append(dict(epoch_ms=now,relative_ms=now-t0,redis=dict(zip(['stream','length','waiting','active','groups'],values)),memory=free_memory(),java=resources(env.state['app_pid']),k6=resources(k6.pid) if k6 else None,probe=resources(probe.pid)))
                        prom_samples.append(dict(epoch_ms=time.time()*1000,metrics=selected(prom())))
                        if free_memory()['available']<384*1024**2:raise RuntimeError('Available RAM below384MiB; stop flood')
                        stop.wait(1)
            except Exception as e:failure.append(str(e))
        thread=threading.Thread(target=monitor,daemon=True);thread.start()
        child=os.environ.copy();child.update(V3_JOINT_EXECUTE='one-owned-joint-run',V3_T0=str(t0),V3_VUS='512',V3_FIXTURES=str(fixtures),V3_OWNER=env.owner,V3_RUN='j-'+uuid.uuid4().hex[:12],V3_SUMMARY=str(out/'k6-summary.json'))
        if time.time()*1000>t0-3000:raise RuntimeError('Preparation missed bounded T0 lead; no load started, no rerun')
        k6=subprocess.Popen([str(ROOT/'.tools/k6-v2.3.0-windows-amd64/k6.exe'),'run','--quiet',str(ROOT/'performance/k6/v3_joint.js')],env=child,stdout=file('k6.stdout.log'),stderr=file('k6.stderr.log'),creationflags=subprocess.CREATE_NO_WINDOW)
        start=time.monotonic();last_print=-999;idx=0
        ticks=sorted(set([float(x) for x in range(-1,122)]+[29.7,29.9,30.0,30.1,30.3]))
        for tick in ticks:
            wait=t0/1000+tick-time.time()
            if wait>0:time.sleep(wait)
            if failure:raise RuntimeError('Resource observer: '+failure[0])
            v=snapshot(idx);idx+=1
            if tick-last_print>=5:
                print(json.dumps(dict(seconds=round(v['relative_ms']/1000,2),held=v['held_tickets'],processing=v['processing'],queue_timeout=v['queue_timeout'],memory_mib=free_memory()['available']//1024**2)),flush=True);last_print=tick
            if tick>=38 and k6.poll() is None:raise RuntimeError('Bounded k6 did not exit after send window/grace')
            if tick>=34 and k6.poll() is not None:
                latest=samples[-1]['redis'] if samples else {}
                if latest.get('length')==0 and latest.get('active')==0 and latest.get('waiting')==0 and v['processing']==0:break
        if k6.poll() is None:k6.wait(timeout=5)
        save(out/'k6-exit.json',dict(code=k6.returncode))
        (out/'metrics-after.prom').write_text(prom())
        with env.redis() as r:save(out/'redis-after.json',dict(commandstats=r.call('INFO','commandstats'),stream=r.call('XINFO','STREAM','{tkv3}:stream'),groups=r.call('XINFO','GROUPS','{tkv3}:stream')))
        save(out/'mysql-after.json',dict(status=env.sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_log_waits','Threads_running','Binlog_commits','Binlog_group_commits');"),
            digests=env.sql("SELECT LEFT(DIGEST_TEXT,240),COUNT_STAR,ROUND(SUM_TIMER_WAIT/1e12,3),ROUND(AVG_TIMER_WAIT/1e9,3),ROUND(SUM_LOCK_TIME/1e12,3) FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME=DATABASE() ORDER BY SUM_TIMER_WAIT DESC LIMIT 25;")))
        save(out/'final-states.json',dict(states=env.sql(f"SELECT state,COUNT(*) FROM tb_reservation_job WHERE session_id='{session}' GROUP BY state;"),
            held_by_sku=env.sql(f"SELECT r.sku_id,COUNT(*),COUNT(DISTINCT r.user_id),SUM(r.quantity) FROM tb_reservation r JOIN tb_ticket_sku s ON s.id=r.sku_id WHERE s.event_session_id='{session}' AND r.status='HELD' GROUP BY r.sku_id;"),
            stock=env.sql(f"SELECT b.sku_id,SUM(b.remaining_stock),MIN(b.remaining_stock) FROM tb_ticket_stock_bucket b JOIN tb_ticket_sku s ON s.id=b.sku_id WHERE s.event_session_id='{session}' GROUP BY b.sku_id;")))
    except Exception as e:
        failure.append(str(e));save(out/'run-error.json',dict(error=str(e)));raise
    finally:
        if k6 and k6.poll() is None:k6.terminate();k6.wait(timeout=10)
        stop.set()
        if 'thread' in locals():thread.join(timeout=8)
        if probe:
            if probe.stdin:probe.stdin.close()
            try:probe.wait(timeout=3)
            except subprocess.TimeoutExpired:probe.terminate();probe.wait(timeout=5)
        if stats and stats.poll() is None:stats.terminate();stats.wait(timeout=5)
        for h in handles:h.close()
        save(out/'sql-timeline.json',sql_samples);save(out/'resource-redis-timeline.json',samples);save(out/'prometheus-timeline.json',prom_samples)
        save(out/'cleanup.json',dict(observer_stopped=probe is None or probe.poll() is not None,k6_stopped=k6 is None or k6.poll() is not None,docker_stats_stopped=stats is None or stats.poll() is not None,app_and_containers_left_running=True,no_data_removed=True,failures=failure))
        print('RAW_RESULTS='+str(out),flush=True)

if __name__=='__main__':main()
