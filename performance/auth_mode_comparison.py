"""A/B/C auth diagnostic. Tests only; no production switch or configuration changes."""
import json,os,pathlib,statistics,threading,time,uuid,zipfile
from types import SimpleNamespace
import fast_ingress_benchmark as f
from jmeter_ingress_crosscheck import fingerprint,process_memory
from lettuce_wait_diagnostic import redis

def mode(app,value):
    code,result=f.http(app.port,'/api/admin/fast-auth/mode?mode='+value,app.admin,{},timeout=8)
    assert code==200 and result['authMode']==value,(code,result)
def counts(d):return {k:d['phases'].get('redis_command.'+k,{}).get('count',0) for k in ('hGetAll','pExpire')}
def token_ttls(app,initialize=False):
    keys=['login:token:'+app.userprefix+str(i) for i in range(128)]
    if initialize:redis(app.rdb,'\n'.join('EXPIRE '+key+' 1800' for key in keys))
    ttls=[int(x) for x in redis(app.rdb,'\n'.join('PTTL '+key for key in keys)).splitlines()]
    assert len(ttls)==128 and min(ttls)>60000,ttls[:5]
    return dict(min_ms=min(ttls),max_ms=max(ttls),count=len(ttls))
def reset(app):app.drain(3);return app.control('reset',{})
def smoke(app,out):
    results=[]
    for value in ('A','B','C'):
        mode(app,value);reset(app);before=app.probe();fixture=app.fixture(0,'smoke-'+value+'-'+uuid.uuid4().hex[:12])
        code,reply=f.http(app.port,'/api/tickets/reservations',fixture['token'],fixture['body'],timeout=8)
        assert code==202 and reply['data']['newSubmission'] is True,(code,reply)
        after=app.drain(3);d=app.diagnostic(timeout=8);expected={'hGetAll':0 if value=='C' else 1,'pExpire':1 if value=='A' else 0}
        assert counts(d)==expected,(value,counts(d),expected)
        assert after['consumed']-before['consumed']==1
        code,duplicate=f.http(app.port,'/api/tickets/reservations',fixture['token'],fixture['body'],timeout=8);assert code==202 and duplicate['data']['newSubmission'] is False
        assert app.drain(3)['consumed']==after['consumed']
        outcomes={}
        for label,token,body,want in [('missing',None,fixture['body'],401),('bad-token','not-a-test-token',fixture['body'],401),('wrong-user',fixture['token'],dict(fixture['body'],userId=999),403),('invalid-quantity',fixture['token'],dict(fixture['body'],quantity=0),400)]:
            status,_=f.http(app.port,'/api/tickets/reservations',token,body,timeout=8);assert status==want,(value,label,status,want);outcomes[label]=status
        results.append(dict(mode=value,valid=code,redis_post_calls=expected,validation=outcomes,duplicate_consumed=False))
    f.save(out/'smoke.json',results)

def window(app,out,value,rate,index):
    label=f'{index:02d}-{value}-{rate}';mode(app,value);ttls=token_ttls(app)
    f.run_window(app.port,1,8,64,True,app.fixture,'warm-'+uuid.uuid4().hex[:10],monitor_resources=False)
    pb=reset(app);before=app.diagnostic(timeout=8);native=f.owned_java_cpu(app.proc);stop=threading.Event();memory=[]
    def observe():
        while not stop.wait(.5):memory.append(process_memory(app.proc))
    monitor=threading.Thread(target=observe,daemon=True);monitor.start()
    try:r=f.run_window(app.port,5,64,rate,True,app.fixture,label+'-'+uuid.uuid4().hex[:10],diagnostic=app.diagnostic)
    finally:stop.set();monitor.join(2)
    native_end=f.owned_java_cpu(app.proc);raw=r.pop('requests')
    for row in raw:row.pop('response',None)
    f.save(out/(label+'-requests.json'),raw);pa=app.drain(3);after=app.diagnostic(traces=True,timeout=8)
    expected={'hGetAll':0 if value=='C' else r['counts']['response_completed'],'pExpire':r['counts']['response_completed'] if value=='A' else 0}
    actual=counts(after)
    delta={k:pa['counters'].get(k,0)-pb['counters'].get(k,0) for k in set(pa['counters'])|set(pb['counters'])}
    r.update(mode=value,before=before,after=after,probe_before=pb,probe_after=pa,counter_deltas=delta,consumed=pa['consumed']-pb['consumed'],token_ttl_before=ttls,
             redis_post_calls=actual,expected_redis_post_calls=expected,java_cpu_cores=(native_end['cpu_nanos']-native['cpu_nanos'])/(native_end['nano']-native['nano']),java_memory=memory)
    r['client_cpu_cores']=(r['client_after']['cpu_nanos']-r['client_before']['cpu_nanos'])/(r['client_after']['nano']-r['client_before']['nano'])
    r['redis_mean_ms']={k:after['phases'].get('redis_command.'+k,{}).get('nanos',0)/max(1,actual[k])/1e6 for k in actual}
    f.save(out/(label+'.json'),r)
    assert actual==expected,(label,'MODE DID NOT TAKE EFFECT',actual,expected)
    assert r['consumed']==r['submitted'],(label,'unconsumed submissions')
    assert int(r['status'].get('401',0))==0 and not any(code.startswith('5') for code in r['status']),r['status']
    print(json.dumps(dict(label=label,qps=r['http_qps'],submitted_qps=r['submitted_qps'],p95=r['http_ms']['p95'],counts=r['counts'],redis_calls=actual)),flush=True)
    return r

def run():
    state=json.loads(f.docker('inspect','credit-risk-v2-redis'))[0]
    if not state['State']['Running']:raise RuntimeError('Dedicated Redis must already be running; no container starts')
    out=f.ROOT/'performance/results'/('auth-mode-'+time.strftime('%Y%m%d_%H%M%S')+'-'+uuid.uuid4().hex[:4]);out.mkdir(parents=True);before=fingerprint();f.save(out/'production-before.json',before)
    f.save(out/'configuration.json',dict(rate=700,concurrency=64,seconds=5,order=['A','B','C','C','B','A'],workers=4,queue=256,state_capacity=4096,limiter='unchanged: 1000/s burst 1000',diagnostics=True,client='existing Python bounded producer, keepalive, one outstanding per worker',identity_count=128))
    jars=list((f.ROOT/'ticket-risk-platform/target').glob('ticket-risk-platform-*.jar'))
    for jar in jars:
        with zipfile.ZipFile(jar) as z:assert not any('benchmark/AuthModeIngress' in name for name in z.namelist())
    app=None;results=[];reason=''
    try:
        app=f.Isolated(SimpleNamespace(port=18088,consumer_ms=0,process_timeout=240,diagnostics=True,class_overlay=str(f.ROOT/'.tmp/auth-mode-classes'),main_class='benchmark.AuthModeIngress'),out)
        token_ttls(app,True);smoke(app,out)
        for i,value in enumerate(('A','B','C','C','B','A')):results.append(window(app,out,value,700,i+1))
        # Higher load is optional. Do not interpret an underdriven client as capacity.
        c=[r for r in results if r['mode']=='C'];fraction=min(r['counts']['write_completed']/r['offered'] for r in c)
        projected=max(r['client_cpu_cores'] for r in c)*2000/700
        if fraction<.98 or projected>.8:
            reason=f'Higher tiers not run: C sent fraction={fraction:.3f}; projected Python single-process CPU at 2000/s={projected:.2f} core equivalents. Sufficient sender capacity is unproven.'
        else:
            reason='700 tier sender passed headroom check; one additional A/B/C 2000 tier (sample insufficient for stable capacity).'
            for value in ('A','B','C'):results.append(window(app,out,value,2000,len(results)+1))
            reason+=' 5000 tier not run: sufficient sender capacity unproven and unchanged ingress limiter caps successful admissions.'
        f.save(out/'summary.json',[{k:r[k] for k in ('label','mode','offered_rate','http_qps','submitted_qps','http_ms','counts','status','redis_post_calls','redis_mean_ms','java_cpu_cores','client_cpu_cores','consumed')} for r in results]);(out/'higher-tier-decision.txt').write_text(reason)
    finally:
        if app:
            app.close();keys=['credit-risk:fast-ingress-owner','login:token:'+app.admin]+['login:token:'+app.userprefix+str(i) for i in range(128)]
            redis(app.rdb,'DEL '+' '.join(keys));remaining=redis(app.rdb,'EXISTS '+' '.join(keys))
        else:remaining=None
        f.save(out/'cleanup.json',dict(production_unchanged=fingerprint()==before,owned_java_exited=not app or app.proc.poll() is not None,temporary_keys_remaining=remaining,redis_not_restarted=json.loads(f.docker('inspect','credit-risk-v2-redis'))[0]['State']['StartedAt']==state['State']['StartedAt']))
        print('RESULT_DIRECTORY='+str(out),flush=True)

if __name__=='__main__':run()
