"""One-variable, interleaved token refresh comparison. Never runs transaction workloads.
Requires the original/candidate single-class overlays and test launcher already compiled.
"""
import argparse, hashlib, json, pathlib, statistics, time, types, uuid
import fast_ingress_benchmark as fast

def mean_phase(snapshot,key):
    metric=snapshot['phases'].get(key)
    return metric['nanos']/metric['count']/1e6 if metric and metric['count'] else 0
def counter_delta(before,after,name):
    return sum(v-before['counters'].get(k,0) for k,v in after['counters'].items() if k.startswith(name+'['))
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--baseline',type=pathlib.Path,required=True);parser.add_argument('--candidate',type=pathlib.Path,required=True);args=parser.parse_args()
    for p in (args.baseline,args.candidate):assert (p/'com/ticketing/common/util/RefreshTokenInterceptor.class').exists()
    out=fast.ROOT/'performance/results'/('token-refresh-ab-'+time.strftime('%Y%m%d_%H%M%S')+'-'+uuid.uuid4().hex[:4]);out.mkdir(parents=True)
    settings=dict(concurrency=128,rate=700,duration_seconds=5,rounds=3,order=['baseline','candidate','candidate','baseline','baseline','candidate'],consumer_ms=0,workers=4,queue_capacity=256,state_capacity=4096,post_rate=1000,monitor_rpc_during_load=False,
        acceptance='All 3 paired submitted-QPS deltas positive; median uplift >=10%; median P95/P99 no worse; no semantic/test/consumption errors; no increase in admission rejects',
        overlays={v:dict(path=str(p.resolve()),interceptor_sha256=hashlib.sha256((p/'com/ticketing/common/util/RefreshTokenInterceptor.class').read_bytes()).hexdigest()) for v,p in [('baseline',args.baseline),('candidate',args.candidate)]})
    fast.save(out/'config.json',settings);apps={};results=[];started=time.perf_counter()
    try:
        for version,port,path in [('baseline',18088,args.baseline),('candidate',18089,args.candidate)]:
            folder=out/(out.name+'-'+version);folder.mkdir()
            options=types.SimpleNamespace(port=port,consumer_ms=0,process_timeout=300,diagnostics=True,class_overlay=path)
            apps[version]=fast.Isolated(options,folder)
        # Both JVMs stay resident for every window, and both get the same checks/warmup.
        for version,app in apps.items():
            fast.checks(app,app.out)
            warm=fast.run_window(app.port,1,16,128,True,app.fixture,'warm-'+uuid.uuid4().hex[:8]);app.reset();fast.save(app.out/'warm.json',warm);app.diagnostic(timeout=8)
        plan=[(0,'baseline'),(0,'candidate'),(1,'candidate'),(1,'baseline'),(2,'baseline'),(2,'candidate')]
        for index,(pair,version) in enumerate(plan):
            app=apps[version];label=f'pair{pair+1}-{version}-{out.name[-4:]}'
            before=app.reset();diag_before=app.diagnostic(timeout=8);java_before=fast.owned_java_cpu(app.proc)
            result=fast.run_window(app.port,5,128,700,True,app.fixture,label,diagnostic=app.diagnostic,monitor_resources=False)
            java_after=fast.owned_java_cpu(app.proc);after=app.drain(seconds=3)
            status,diag_after=fast.http(app.port,'/api/admin/fast-ingress/diagnostics?traces=true',app.admin,timeout=8);assert status==200
            actual=counter_delta(before,after,'reservation.ingress.submitted');rejects={k:v-before['counters'].get(k,0) for k,v in after['counters'].items() if 'reservation.ingress.rejected' in k and v!=before['counters'].get(k,0)}
            consumed=after['consumed']-before['consumed'];valid=actual==consumed==result['submitted'] and result['counts']['timeouts']==0 and all(r['code'] in (202,429) for r in result['requests'])
            phases={key:mean_phase(diag_after,key) for key in diag_after['phases'] if key.startswith('redis_command.') or key.startswith('interceptor_pre.com.ticketing.common.util.RefreshToken')}
            calls={}
            for trace in diag_after['traces']:
                for event in trace['calls']:
                    if event['name'].startswith('redis.'):calls[event['name']]=calls.get(event['name'],0)+1
            cpu=lambda a,b:(b['cpu_nanos']-a['cpu_nanos'])/(b['nano']-a['nano']) if a and b else None
            row=dict(pair=pair+1,version=version,http_qps=result['http_qps'],submitted_qps=result['submitted_qps'],submitted=result['submitted'],consumed=consumed,http_ms=result['http_ms'],sent=result['counts']['write_completed'],completed=result['counts']['response_completed'],not_sent=result['counts']['not_attempted'],timeouts=result['counts']['timeouts'],queue_reject_fraction=sum(v for k,v in rejects.items() if 'reason=queue_full' in k)/max(1,result['post_responses']),all_reject_fraction=result['rejected_429_fraction'],reject_reasons=rejects,
                redis_mean_ms=phases,redis_command_calls=calls,client_cpu_cores=cpu(result['client_before'],result['client_after']),java_cpu_cores=cpu(java_before,java_after),client_rss_mib=result['client_after']['working_set_bytes']/2**20,java_heap_before_mib=diag_before['jvm']['heap_used']/2**20,java_heap_after_mib=diag_after['jvm']['heap_used']/2**20,host_cpu_percent=100*(1-(result['client_after']['host_idle_100ns']-result['client_before']['host_idle_100ns'])/(result['client_after']['host_cpu_100ns']-result['client_before']['host_cpu_100ns'])),host_memory_percent=result['client_after']['host_memory_load_percent'],valid=valid)
            fast.save(out/(label+'.json'),dict(summary=row,requests=result,diagnostic_before=diag_before,diagnostic_after=diag_after,server_before=before,server_after=after,java_cpu_before=java_before,java_cpu_after=java_after));results.append(row);fast.save(out/'rounds.json',results)
            print(json.dumps(row,ensure_ascii=True),flush=True)
            if not valid:raise AssertionError('Functional/consumption error; stop comparison and reject candidate')
        medians={v:{k:statistics.median(r[k] for r in results if r['version']==v) for k in ('http_qps','submitted_qps','client_cpu_cores','java_cpu_cores','client_rss_mib','java_heap_after_mib','host_cpu_percent')} for v in apps}
        for v in apps:
            medians[v].update({f'p{p}_ms':statistics.median(r['http_ms'][f'p{p}'] for r in results if r['version']==v) for p in (50,95,99)})
        pairs=[]
        for i in range(1,4):
            b=next(r for r in results if r['version']=='baseline' and r['pair']==i);c=next(r for r in results if r['version']=='candidate' and r['pair']==i);pairs.append(dict(pair=i,uplift=c['submitted_qps']/b['submitted_qps']-1))
        uplift=medians['candidate']['submitted_qps']/medians['baseline']['submitted_qps']-1
        retain=all(p['uplift']>0 for p in pairs) and uplift>=.1 and medians['candidate']['p95_ms']<=medians['baseline']['p95_ms'] and medians['candidate']['p99_ms']<=medians['baseline']['p99_ms'] and all(r['all_reject_fraction']==0 and r['valid'] for r in results)
        fast.save(out/'decision.json',dict(retain=retain,median_uplift=uplift,paired_uplifts=pairs,medians=medians,scope='Single auth class/Lua only; no stock or fulfillment workload; short observation not stable capacity certification'))
        print('DECISION',json.dumps(dict(retain=retain,median_uplift=uplift,medians=medians)),flush=True)
    finally:
        for app in reversed(list(apps.values())):app.close()
        fast.save(out/'cleanup.json',dict(java_processes_exited=all(a.proc.poll() is not None for a in apps.values()),elapsed_seconds=time.perf_counter()-started,existing_services_preserved=True));print('Artifacts:',out,flush=True)
if __name__=='__main__':main()
