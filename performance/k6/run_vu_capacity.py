"""Only the unchanged tiny HTTP fixture. Bounded VU staircase; never imports ingress launch."""
import hashlib,json,os,pathlib,queue,shutil,socket,subprocess,threading,time,uuid
from run_diagnostic import f,HERE,K6,load,save,fingerprint

def run():
    out=f.ROOT/'performance/results'/('k6-vu-capacity-'+time.strftime('%Y%m%d_%H%M%S')+'-'+uuid.uuid4().hex[:4]);out.mkdir(parents=True)
    before=fingerprint();serverfile=HERE/'tiny-http.cjs';serverhash=hashlib.sha256(serverfile.read_bytes()).hexdigest();node=None;stderr=None;results=[];passed=False;reason=''
    save(out/'production-before.json',before)
    save(out/'environment.json',dict(k6=subprocess.check_output([str(K6),'version'],text=True).strip(),node=subprocess.check_output(['node','--version'],text=True).strip(),head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),working_tree='dirty, preserved',host=f.client_resources(),scripts={str(p.relative_to(f.ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in HERE.glob('*') if p.is_file()}))
    save(out/'plan.json',dict(rate=5000,seconds=5,target_iterations=25000,vu_stages=[256,512,768],fixed_max_equal_preallocated=True,pass_qps=4750,max_drop_fraction=.01,stop_qps_growth_fraction=.03,stop_p95_p99_worsening_factor=1.5,resource_policy='stop on errors; during run guard free RAM<256MiB, k6RSS>768MiB or hostCPU>=97% for3 samples',scope='TINY_HTTP_ONLY_NO_INGRESS_NO_DOCKER'))
    print('RESULT_DIRECTORY='+str(out),flush=True)
    try:
        with socket.socket() as s:s.bind(('127.0.0.1',18089))
        env=os.environ.copy();env['TINY_PORT']='18089';stderr=(out/'tiny-stderr.log').open('w',encoding='utf-8')
        node=subprocess.Popen([shutil.which('node'),str(serverfile)],cwd=HERE,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=stderr,text=True,encoding='utf-8',creationflags=subprocess.CREATE_NO_WINDOW)
        replies=queue.Queue()
        def read():
            for line in node.stdout:replies.put(json.loads(line))
        reader=threading.Thread(target=read,daemon=True);reader.start();assert replies.get(timeout=10)['ready']
        def snapshot():node.stdin.write('snapshot\n');node.stdin.flush();return replies.get(timeout=3)
        for vus in (256,512,768):
            sb=snapshot();r=load(out,'vu-'+str(vus),5000,'http://127.0.0.1:18089/',node,dict(VUS=str(vus)));sa=snapshot()
            raw=json.loads((out/('vu-'+str(vus))/'k6-summary.json').read_text());metrics=raw['metrics'];samples=json.loads((out/('vu-'+str(vus))/'resource-samples.json').read_text());hosta=r['host_before'];hostb=r['host_after']
            hostcpu=100*(1-(hostb['host_idle_100ns']-hosta['host_idle_100ns'])/max(1,hostb['host_cpu_100ns']-hosta['host_cpu_100ns']))
            r.update(vus=vus,server_received=sa['requests']-sb['requests'],server_finished=sa['finished']-sb['finished'],new_connections=sa['connections']-sb['connections'],server_before=sb,server_after=sa,
                     iterations_started=r['attempted'],iterations_finished=metrics['iterations']['values']['count'],iteration_duration=metrics['iteration_duration']['values'],http_req_duration=metrics['http_req_duration']['values'],active_vus_sampled=metrics['vus']['values'],host_cpu_percent=hostcpu,
                     host_min_available_bytes=min([x['host']['host_memory_available'] for x in samples]+[hosta['host_memory_available'],hostb['host_memory_available']]),dropped_fraction=r['dropped_iterations']/25000)
            r['counts_reconciled']=r['attempted']==r['http_requests_metric']==r['server_received']==r['server_finished']==r['completed']==r['status200']==r['iterations_finished']
            r['passed']=r['http_qps']>=4750 and r['dropped_fraction']<=.01 and r['counts_reconciled'] and not r['transport_errors'] and not r['timeouts'] and not r['other'] and not r['resource_guard']
            save(out/('vu-'+str(vus))/'result.json',r);results.append(r)
            if r['passed']:passed=True;reason=f'{vus} VU passed; higher stages omitted';break
            if not r['counts_reconciled'] or r['transport_errors'] or r['timeouts'] or r['other'] or r['resource_guard']:
                reason='Errors, resource pressure or count discrepancy; no higher stage';break
            if r['host_min_available_bytes']<512*1024*1024 or r['host_cpu_percent']>=90:
                reason='Insufficient CPU/RAM headroom for next stage';break
            if len(results)>1:
                previous=results[-2];gain=r['http_qps']/previous['http_qps']-1
                if gain<=.03:reason=f'QPS plateau: improvement {gain:.1%} <=3%; no higher stage';break
                if r['p95']>=previous['p95']*1.5 or r['p99']>=previous['p99']*1.5:
                    reason='P95 or P99 worsened by >=50%; no higher stage';break
        if not reason:reason='Reached authorized768 VU ceiling without passing; no further test'
        save(out/'comparison.json',dict(passed=passed,stop_reason=reason,rounds=results));print('STOP_REASON='+reason,flush=True)
    finally:
        if node and node.poll() is None:node.kill();node.wait(timeout=5)
        if node:
            node.stdin.close();node.stdout.close()
        if stderr:stderr.close()
        save(out/'cleanup.json',dict(production_unchanged=fingerprint()==before,tiny_service_source_unchanged=hashlib.sha256(serverfile.read_bytes()).hexdigest()==serverhash,owned_tiny_process_exited=not node or node.poll() is not None,owned_k6_processes_exited=all(r['owned_k6_exited'] for r in results),docker_operations=0,ingress_started=False))
        print('DONE '+str(out),flush=True)

if __name__=='__main__':run()
