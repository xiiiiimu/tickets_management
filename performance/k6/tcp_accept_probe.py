"""Two bounded512-connection tests without HTTP or service modifications."""
import asyncio,collections,hashlib,json,os,queue,shutil,socket,subprocess,threading,time,uuid
from run_diagnostic import f,HERE,save,fingerprint,observation,cpu_cores

async def connect_case(label,step):
    records=[];writers=[];origin=time.perf_counter()
    async def connect(i):
        if step:await asyncio.sleep((i//32)*step)
        begin=time.perf_counter()
        try:
            _,writer=await asyncio.wait_for(asyncio.open_connection('127.0.0.1',18089),2)
            writers.append(writer);records.append(dict(i=i,ok=True,start_ms=(begin-origin)*1000,duration_ms=(time.perf_counter()-begin)*1000))
        except Exception as e:records.append(dict(i=i,ok=False,error=str(e),winerror=getattr(e,'winerror',None),start_ms=(begin-origin)*1000,duration_ms=(time.perf_counter()-begin)*1000))
    await asyncio.gather(*(connect(i) for i in range(512)))
    await asyncio.sleep(.1)
    for writer in writers:writer.close()
    await asyncio.gather(*(writer.wait_closed() for writer in writers),return_exceptions=True)
    return dict(label=label,target_connections=512,successful=len(writers),failed=512-len(writers),errors=dict(collections.Counter(str(r.get('winerror')) for r in records if not r['ok'])),elapsed_seconds=time.perf_counter()-origin,records=records)

def main():
    out=f.ROOT/'performance/results'/('tcp-accept-'+time.strftime('%Y%m%d_%H%M%S')+'-'+uuid.uuid4().hex[:4]);out.mkdir(parents=True);before=fingerprint();servicehash=hashlib.sha256((HERE/'tiny-http.cjs').read_bytes()).hexdigest();node=None;stderr=None
    try:
        with socket.socket() as s:s.bind(('127.0.0.1',18089))
        env=os.environ.copy();env['TINY_PORT']='18089';stderr=(out/'node-stderr.log').open('w',encoding='utf-8');node=subprocess.Popen([shutil.which('node'),str(HERE/'tiny-http.cjs')],cwd=HERE,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=stderr,text=True,encoding='utf-8',creationflags=subprocess.CREATE_NO_WINDOW);replies=queue.Queue()
        def read():
            for line in node.stdout:replies.put(json.loads(line))
        threading.Thread(target=read,daemon=True).start();assert replies.get(timeout=10)['ready']
        def snapshot():node.stdin.write('snapshot\n');node.stdin.flush();return replies.get(timeout=3)
        for label,step in [('burst-512',0),('paced-32-every-10ms',.01)]:
            sb=snapshot();cpu=observation(node);r=asyncio.run(connect_case(label,step));sa=snapshot();cpu_after=observation(node);r.update(server_connections=sa['connections']-sb['connections'],http_requests=sa['requests']-sb['requests'],server_cpu_cores=cpu_cores(cpu,cpu_after));save(out/(label+'.json'),r);print(json.dumps({k:v for k,v in r.items() if k!='records'}),flush=True)
    finally:
        if node and node.poll() is None:node.kill();node.wait(5)
        if stderr:stderr.close()
        save(out/'cleanup.json',dict(node_exited=not node or node.poll() is not None,production_unchanged=fingerprint()==before,tiny_service_unchanged=hashlib.sha256((HERE/'tiny-http.cjs').read_bytes()).hexdigest()==servicehash,docker_operations=0));print('RESULT_DIRECTORY='+str(out),flush=True)

if __name__=='__main__':main()
