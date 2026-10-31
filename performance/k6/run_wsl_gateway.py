"""Windows owner, WSL k6 client; binding override only, no firewall mutations or ingress."""
import ctypes,hashlib,json,os,pathlib,queue,shutil,socket,subprocess,threading,time,uuid
from http.client import HTTPConnection
from run_diagnostic import f,HERE,save,fingerprint,observation,cpu_cores
from wsl_preflight import wsl
from diagnose_connections import api,Row

def linux(path):return '/mnt/e/credit_risk/'+path.resolve().relative_to(f.ROOT).as_posix()
def listeners():
    size=ctypes.wintypes.DWORD();api(None,ctypes.byref(size),False,2,5,0);buf=ctypes.create_string_buffer(size.value);code=api(buf,ctypes.byref(size),False,2,5,0)
    if code:raise RuntimeError('TCP table error '+str(code))
    result=[]
    for i in range(ctypes.wintypes.DWORD.from_buffer(buf).value):
        r=Row.from_buffer(buf,4+i*ctypes.sizeof(Row))
        if r.state==2 and socket.ntohs(r.local_port&65535)==18089:result.append(dict(ip=socket.inet_ntoa(r.local_address.to_bytes(4,'little')),port=18089,pid=r.pid))
    return result
def owns_ip(ip):
    iphlp=ctypes.WinDLL('iphlpapi');size=ctypes.wintypes.DWORD();iphlp.GetIpAddrTable(None,ctypes.byref(size),False);buf=ctypes.create_string_buffer(size.value)
    if iphlp.GetIpAddrTable(buf,ctypes.byref(size),False):raise RuntimeError('Windows address query failed')
    n=ctypes.wintypes.DWORD.from_buffer(buf).value
    return any(socket.inet_ntoa(bytes(buf[4+i*24:8+i*24]))==ip for i in range(n))
def settle(path):
    linux_memory=wsl('/bin/sh','-c','grep MemAvailable /proc/meminfo')
    available=int(linux_memory['stdout'].split()[1])*1024
    if available<512*1024*1024:raise RuntimeError('WSL available memory below512MiB; no load')
    previous=f.client_resources();good=0;rows=[]
    for _ in range(20):
        time.sleep(.5);current=f.client_resources();span=current['host_cpu_100ns']-previous['host_cpu_100ns'];idle=current['host_idle_100ns']-previous['host_idle_100ns'];cpu=100*(1-idle/max(1,span));good=good+1 if cpu<85 and current['host_memory_available']>=384*1024*1024 else 0;previous=current
        rows.append(dict(cpu_percent=cpu,available_bytes=current['host_memory_available'],linux_available_bytes=available,windows_start_floor_bytes=384*1024*1024,hard_stop_floor_bytes=256*1024*1024,consecutive_stable=good))
        if good>=3:save(path,rows);return
    save(path,rows);raise RuntimeError('Host did not settle; no more load: '+str(rows[-1]))
def run_case(out,node,url,rate,vus,snapshot):
    folder=out/(str(rate)+'-'+str(vus));folder.mkdir();before=snapshot();server_before=observation(node);host_before=f.client_resources();windows=[];stop=threading.Event();guard=[]
    args=['wsl','-d','Ubuntu','--exec','python3','/mnt/e/credit_risk/performance/k6/wsl_load.py',linux(folder),url,str(rate),str(vus)]
    save(folder/'configuration.json',dict(command=args,rate=rate,vus=vus,seconds=5,url=url));log=(folder/'wsl-launch.log').open('w',encoding='utf-8');proc=subprocess.Popen(args,stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW)
    def observe():
        high=0
        while not stop.wait(.5):
            host=f.client_resources();windows.append(dict(host=host,server=observation(node)));high=high+1 if host['host_memory_available']<256*1024*1024 else 0
            if high>=3:guard.append('Windows memory guard');(folder/'STOP').write_text('stop');return
    monitor=threading.Thread(target=observe,daemon=True);monitor.start()
    try:
        try:proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            (folder/'STOP').write_text('Windows deadline');proc.wait(timeout=10)
    finally:
        stop.set();monitor.join(2);log.close();save(folder/'windows-resource-samples.json',windows)
    if proc.returncode:raise RuntimeError('Linux k6 failed; see '+str(folder))
    server_after=observation(node);after=snapshot();raw=json.loads((folder/'k6-summary.json').read_text());resource=json.loads((folder/'linux-resources.json').read_text());metrics=raw['metrics']
    def count(k):return metrics.get(k,{}).get('values',{}).get('count',0)
    duration=max(5,metrics['completion_offset_ms']['values']['max']/1000);cpu_span=f.client_resources();cpu_delta=cpu_span['host_cpu_100ns']-host_before['host_cpu_100ns'];idle_delta=cpu_span['host_idle_100ns']-host_before['host_idle_100ns']
    r=dict(rate=rate,vus=vus,target_requests=rate*5,started=count('request_attempts'),http_requests_metric=count('http_reqs'),finished_iterations=count('iterations'),completed=count('response_completed'),server_received=after['requests']-before['requests'],server_finished=after['finished']-before['finished'],http_qps=count('response_completed')/duration,response_window_seconds=duration,dropped=count('dropped_iterations'),dropped_fraction=count('dropped_iterations')/(rate*5),status200=count('status_200'),errors=count('transport_errors'),timeouts=count('request_timeouts'),non200=count('status_other')+count('status_429')+count('status_401'),http_ms=metrics['http_req_duration']['values'],iteration_ms=metrics['iteration_duration']['values'],linux_cpu_cores=resource['cpu_cores'],linux_k6_max_rss=resource['max_rss_bytes'],server_cpu_cores=cpu_cores(server_before,server_after),server_max_sampled_rss=max([x['server']['memory'].get('rss',0) for x in windows]+[0]),windows_cpu_percent=100*(1-idle_delta/max(1,cpu_delta)),windows_min_available_bytes=min([x['host']['host_memory_available'] for x in windows]+[host_before['host_memory_available']]),new_connections=after['connections']-before['connections'],active_vus=metrics['vus']['values'],guard=guard+resource['guard'])
    r['counts_reconciled']=r['started']==r['http_requests_metric']==r['server_received']==r['server_finished']==r['completed']==r['status200']==r['finished_iterations']
    r['passed']=rate==5000 and r['http_qps']>=4750 and r['dropped_fraction']<=.01 and r['counts_reconciled'] and not r['errors'] and not r['timeouts'] and not r['guard']
    save(folder/'result.json',r);print(json.dumps(r,ensure_ascii=False),flush=True);return r
def main():
    out=f.ROOT/'performance/results'/('wsl-gateway-load-'+time.strftime('%Y%m%d_%H%M%S')+'-'+uuid.uuid4().hex[:4]);out.mkdir(parents=True);before=fingerprint();sourcehash=hashlib.sha256((HERE/'tiny-http.cjs').read_bytes()).hexdigest();node=None;stderr=None;results=[];reason='';passed=False
    print('RESULT_DIRECTORY='+str(out),flush=True)
    try:
        route=wsl('ip','-j','-4','route','show','default');gateway=json.loads(route['stdout'])[0]['gateway'];addresses=wsl('ip','-j','-4','addr','show','eth0');source=json.loads(addresses['stdout'])[0]['addr_info'][0]['local'];network=wsl('wslinfo','--networking-mode')
        assert gateway=='172.30.192.1' and owns_ip(gateway),'Gateway no longer equals authorized address or Windows does not own it'
        assert not listeners(),'Port18089 already has a listener; no existing service touched'
        with socket.socket() as s:s.bind((gateway,18089))
        save(out/'environment.json',dict(route=route,addresses=addresses,network=network,gateway=gateway,source=source,windows_owns_gateway=True,k6=wsl('/mnt/e/credit_risk/.tools/k6-v2.3.0-linux-amd64/k6','version'),service_sha256=sourcehash,host=f.client_resources(),head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),scripts={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [HERE/'arrival.js',HERE/'wsl_load.py',HERE/'run_wsl_gateway.py',HERE/'bind-wsl-gateway.cjs']}))
        env=os.environ.copy();env.update(TINY_PORT='18089',TINY_BIND_HOST=gateway);stderr=(out/'node-stderr.log').open('w',encoding='utf-8');node=subprocess.Popen([shutil.which('node'),'--require',str(HERE/'bind-wsl-gateway.cjs'),str(HERE/'tiny-http.cjs')],env=env,cwd=HERE,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=stderr,text=True,encoding='utf-8',creationflags=subprocess.CREATE_NO_WINDOW);replies=queue.Queue()
        def read():
            for line in node.stdout:replies.put(json.loads(line))
        threading.Thread(target=read,daemon=True).start();assert replies.get(timeout=10)['ready'];actual=listeners();assert actual==[dict(ip=gateway,port=18089,pid=node.pid)],actual;save(out/'listener.json',actual)
        def snapshot():node.stdin.write('snapshot\n');node.stdin.flush();return replies.get(timeout=3)
        sb=snapshot();probe=wsl('python3','/mnt/e/credit_risk/performance/k6/wsl_connectivity.py','http://'+gateway+':18089/',timeout=20);sa=snapshot();checks=json.loads(probe['stdout']);checks['windows_received_delta']=sa['requests']-sb['requests'];save(out/'connectivity.json',checks)
        if checks['successful']!=10 or checks['windows_received_delta']!=10:
            reason='Connectivity failed; no load or persistent firewall changes';save(out/'firewall-readonly.json',dict(note='Previous authorized gateway preflight passed; current failure needs targeted inspection; no firewall writes performed'));return
        for rate,vus in [(1000,128),(3000,256),(5000,256),(5000,512)]:
            settle(out/('settled-'+str(rate)+'-'+str(vus)+'.json'));r=run_case(out,node,'http://'+gateway+':18089/',rate,vus,snapshot);results.append(r)
            if r['passed']:passed=True;reason=f'{rate}/s at{vus}VU passed; stop';break
            if r['errors'] or r['timeouts'] or r['non200'] or r['guard'] or not r['counts_reconciled']:reason='Connection/error/resource/count guard; no further tier';break
            if len(results)>1:
                previous=results[-2];p95=r['http_ms']['p(95)'];p99=r['http_ms']['p(99)']
                if (p95>100 and p95>2*previous['http_ms']['p(95)']) or (p99>250 and p99>2*previous['http_ms']['p(99)']):reason='Sharp latency deterioration; no further tier';break
        if not reason:reason='Authorized stages completed without passing'
    except Exception as error:
        reason='Stopped: '+str(error);raise
    finally:
        if node and node.poll() is None:node.kill();node.wait(5)
        if stderr:stderr.close()
        save(out/'comparison.json',dict(passed=passed,stop_reason=reason,rounds=results));save(out/'cleanup.json',dict(production_unchanged=fingerprint()==before,service_source_unchanged=hashlib.sha256((HERE/'tiny-http.cjs').read_bytes()).hexdigest()==sourcehash,node_exited=not node or node.poll() is not None,listeners_after=listeners(),firewall_rules_created=0,docker_operations=0,wsl_shutdown_called=False,ingress_started=False));print('DONE '+str(out),flush=True)
if __name__=='__main__':main()
