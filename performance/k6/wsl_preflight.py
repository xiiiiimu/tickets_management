"""Windows owner of unchanged tiny service; WSL network preflight only, no configuration changes."""
import hashlib,json,os,pathlib,queue,shutil,socket,subprocess,threading,time,uuid,sys
from http.client import HTTPConnection
from run_diagnostic import f,HERE,save,fingerprint

def wsl(*args,timeout=20):
    p=subprocess.run(['wsl','-d','Ubuntu','--exec',*args],capture_output=True,timeout=timeout)
    return dict(code=p.returncode,stdout=p.stdout.decode('utf-8',errors='replace'),stderr=p.stderr.decode('utf-8',errors='replace'))
def main():
    out=f.ROOT/'performance/results'/('wsl-k6-'+time.strftime('%Y%m%d_%H%M%S')+'-'+uuid.uuid4().hex[:4]);out.mkdir(parents=True);before=fingerprint();servicehash=hashlib.sha256((HERE/'tiny-http.cjs').read_bytes()).hexdigest();node=None;stderr=None
    print('RESULT_DIRECTORY='+str(out),flush=True)
    try:
        environment=wsl('/bin/sh','-c','uname -a; id; nproc; head -n 6 /proc/meminfo; ip -4 route; ip -4 addr; wslinfo --networking-mode; test -r /mnt/e/credit_risk/performance/k6/tiny-http.cjs && echo shared_project_readable')
        route=wsl('ip','-j','-4','route','show','default');routes=json.loads(route['stdout']);gateway=routes[0]['gateway'];target='http://'+gateway+':18089/'
        version=wsl('/mnt/e/credit_risk/.tools/k6-v2.3.0-linux-amd64/k6','version');actualroute=wsl('ip','route','get',gateway)
        save(out/'environment.json',dict(ubuntu=environment,routes=route,actual_route=actualroute,target=target,k6=version,windows_host=f.client_resources(),service_sha256=servicehash,head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),constraints='unchanged127.0.0.1 listener; no network/config edits'))
        save(out/'test-plan.json',dict(executor='constant-arrival-rate',duration_seconds=5,stages=[dict(rate=1000,vus=128),dict(rate=3000,vus=256),dict(rate=5000,vus=256),dict(rate=5000,vus=512)],pass_qps=4750,max_dropped_fraction=.01,prerequisite='10 direct-IP checks must reach this owned Windows service'))
        gateway_binding='--bind-gateway' in sys.argv
        if gateway_binding and gateway!='172.30.192.1':raise RuntimeError('Current gateway differs from explicitly authorized172.30.192.1')
        bindhost=gateway if gateway_binding else '127.0.0.1'
        with socket.socket() as s:s.bind((bindhost,18089))
        env=os.environ.copy();env.update(TINY_PORT='18089',TINY_BIND_HOST=bindhost);command=[shutil.which('node')]+(['--require',str(HERE/'bind-wsl-gateway.cjs')] if gateway_binding else [])+[str(HERE/'tiny-http.cjs')]
        stderr=(out/'node-stderr.log').open('w',encoding='utf-8');node=subprocess.Popen(command,cwd=HERE,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=stderr,text=True,encoding='utf-8',creationflags=subprocess.CREATE_NO_WINDOW)
        replies=queue.Queue()
        def read():
            for line in node.stdout:replies.put(json.loads(line))
        threading.Thread(target=read,daemon=True).start();assert replies.get(timeout=10)['ready']
        def snapshot():node.stdin.write('snapshot\n');node.stdin.flush();return replies.get(timeout=3)
        connection=HTTPConnection(bindhost,18089,timeout=2);connection.request('GET','/');response=connection.getresponse();body=response.read();assert response.status==200 and body==b'OK';connection.close()
        save(out/'binding.json',dict(host=bindhost,port=18089,command=command,windows_local_target_check=200,override_by_preload=gateway_binding))
        sb=snapshot();probe=wsl('python3','/mnt/e/credit_risk/performance/k6/wsl_connectivity.py',target,timeout=20);sa=snapshot();save(out/'connectivity-command.json',probe)
        checked=json.loads(probe['stdout']) if probe['code']==0 else dict(successful=0,error='Linux-side preflight failed')
        checked.update(windows_service_received_delta=sa['requests']-sb['requests'],windows_loopback_check_status=200,windows_loopback_check_body='OK');save(out/'connectivity.json',checked)
        if checked['successful']!=10 or checked['windows_service_received_delta']!=10:
            save(out/'blocked.json',dict(reason='WSL NAT direct host-IP path does not reach unchanged Windows loopback-only listener',target=target,load_tests_started=False,network_changes=False));print('BLOCKED: '+json.dumps(checked),flush=True)
        else:
            save(out/'connectivity-passed.json',dict(passed=True,target=target));print('CONNECTIVITY_PASSED',flush=True)
    finally:
        if node and node.poll() is None:node.kill();node.wait(5)
        if stderr:stderr.close()
        save(out/'cleanup.json',dict(production_unchanged=fingerprint()==before,service_source_unchanged=hashlib.sha256((HERE/'tiny-http.cjs').read_bytes()).hexdigest()==servicehash,owned_node_exited=not node or node.poll() is not None,wsl_shutdown_called=False,docker_operations=0,load_tests_started=False));print('DONE '+str(out),flush=True)

if __name__=='__main__':main()
