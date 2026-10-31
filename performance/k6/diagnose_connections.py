"""One5s reproduction: unchanged tiny service, diagnostic preload, TCP table, optional ETW."""
import collections,ctypes,hashlib,json,os,pathlib,queue,shutil,socket,subprocess,threading,time,uuid
from ctypes import wintypes as w
from run_diagnostic import f,HERE,load,save,fingerprint

class Row(ctypes.Structure):
    _fields_=[(k,w.DWORD) for k in ('state','local_address','local_port','remote_address','remote_port','pid')]
api=ctypes.WinDLL('iphlpapi').GetExtendedTcpTable
api.argtypes=[ctypes.c_void_p,ctypes.POINTER(w.DWORD),w.BOOL,w.DWORD,w.DWORD,w.DWORD];api.restype=w.DWORD
def tcp_snapshot():
    size=w.DWORD();api(None,ctypes.byref(size),False,2,5,0)
    buf=ctypes.create_string_buffer(size.value);status=api(buf,ctypes.byref(size),False,2,5,0)
    if status:return dict(epoch_ms=time.time_ns()/1e6,error=status)
    count=w.DWORD.from_buffer(buf).value;states=collections.Counter();listeners=[];pending=[]
    for i in range(count):
        r=Row.from_buffer(buf,4+i*ctypes.sizeof(Row));local=socket.ntohs(r.local_port&65535);remote=socket.ntohs(r.remote_port&65535)
        if local!=18089 and remote!=18089:continue
        side='server' if local==18089 else 'client';states[side+':'+str(r.state)]+=1
        if r.state==2:listeners.append(r.pid)
        if r.state in (3,4) and len(pending)<64:pending.append(dict(pid=r.pid,local=local,remote=remote,state=r.state))
    return dict(epoch_ms=time.time_ns()/1e6,states=dict(states),listener_pids=listeners,pending=pending)

def run():
    out=f.ROOT/'performance/results'/('connection-layer-'+time.strftime('%Y%m%d_%H%M%S')+'-'+uuid.uuid4().hex[:4]);out.mkdir(parents=True)
    before=fingerprint();servicehash=hashlib.sha256((HERE/'tiny-http.cjs').read_bytes()).hexdigest();node=None;trace=False;stop=threading.Event();observer=None;session='credit-risk-conn-'+uuid.uuid4().hex[:8]
    save(out/'environment.json',dict(host=f.client_resources(),head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),service_sha256=servicehash,diagnostic_source_hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [HERE/'diagnose_connections.py',HERE/'observe-connections.cjs',HERE/'arrival.js']}));print('RESULT_DIRECTORY='+str(out),flush=True)
    try:
        with socket.socket() as s:s.bind(('127.0.0.1',18089))
        env=os.environ.copy();env['TINY_PORT']='18089';stderr=(out/'node-stderr.log').open('w',encoding='utf-8');node=subprocess.Popen([shutil.which('node'),'--require',str(HERE/'observe-connections.cjs'),str(HERE/'tiny-http.cjs')],cwd=HERE,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=stderr,text=True,encoding='utf-8',creationflags=subprocess.CREATE_NO_WINDOW)
        replies=queue.Queue()
        def read():
            for line in node.stdout:replies.put(json.loads(line))
        threading.Thread(target=read,daemon=True).start();ready=replies.get(timeout=10);assert ready['ready']
        def control(line):node.stdin.write(line+'\n');node.stdin.flush();return replies.get(timeout=5)
        # Own ETW session only; if OS permission denies it, preserve the reason and use TCP-table evidence.
        cmd=['logman','start',session,'-p','Microsoft-Windows-TCPIP','0x000000040040000e','5','-o',str(out/'tcpip.etl'),'-f','bincirc','-max','16','-ets']
        t=subprocess.run(cmd,capture_output=True,text=True,timeout=8);(out/'etw-start.log').write_text(t.stdout+t.stderr);trace=t.returncode==0
        samples=[]
        def sample():
            while not stop.is_set():samples.append(tcp_snapshot());stop.wait(.02)
        observer=threading.Thread(target=sample,daemon=True);observer.start();sb=control('snapshot')
        r=load(out,'512-vu-5s',5000,'http://127.0.0.1:18089/',node,dict(VUS='512',CONNECTION_DIAG='1'));sa=control('snapshot');diag=control('diagnostics')
        r.update(server_before=sb,server_after=sa,server_received=sa['requests']-sb['requests'],server_finished=sa['finished']-sb['finished']);save(out/'512-vu-5s/result.json',r);save(out/'node-connection-diagnostics.json',diag)
    finally:
        stop.set()
        if observer:observer.join(2)
        if 'samples' in locals():save(out/'tcp-table-samples.json',samples)
        if trace:
            t=subprocess.run(['logman','stop',session,'-ets'],capture_output=True,text=True,timeout=10);(out/'etw-stop.log').write_text(t.stdout+t.stderr)
        if node and node.poll() is None:node.kill();node.wait(5)
        if 'stderr' in locals():stderr.close()
        save(out/'cleanup.json',dict(production_unchanged=fingerprint()==before,tiny_service_source_unchanged=hashlib.sha256((HERE/'tiny-http.cjs').read_bytes()).hexdigest()==servicehash,node_exited=not node or node.poll() is not None,etw_started=trace,etw_session=session if trace else None,docker_operations=0))
        print('DONE '+str(out),flush=True)

if __name__=='__main__':run()
