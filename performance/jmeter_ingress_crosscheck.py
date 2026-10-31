"""Short, real-auth ingress cross-check. No production/configuration changes.
Requires official JMeter 5.6.3 in .tools, and already-running dedicated test Redis.
Both tools target 700/s with C outstanding requests; scheduling models differ.
"""
import argparse, collections, csv, ctypes, hashlib, json, os, pathlib, subprocess
import threading, time, uuid
from types import SimpleNamespace
from xml.sax.saxutils import escape
import fast_ingress_benchmark as f

ROOT=f.ROOT

def fingerprint():
    return {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()
            for p in (ROOT/'ticket-risk-platform/src/main').rglob('*') if p.is_file()}

def process_memory(proc):
    from ctypes import wintypes as w
    class M(ctypes.Structure):
        _fields_=[('cb',w.DWORD),('faults',w.DWORD)]+[(k,ctypes.c_size_t) for k in ('peak','rss','a','b','c','d','commit','peak_commit')]
    m=M();m.cb=ctypes.sizeof(m)
    api=ctypes.WinDLL('psapi');api.GetProcessMemoryInfo.argtypes=[w.HANDLE,ctypes.POINTER(M),w.DWORD]
    ok=api.GetProcessMemoryInfo(w.HANDLE(int(proc._handle)),ctypes.byref(m),m.cb)
    return dict(rss=m.rss,commit=m.commit) if ok else {}

def prop(name,value,typ='stringProp'):
    return '<%s name="%s">%s</%s>'%(typ,name,escape(str(value)),typ)

def sampler(label):
    pre="""if (sampler.getName()=='ingress') {
 long origin=((Long)props.get('origin')).longValue();
 long slot=((java.util.concurrent.atomic.AtomicLong)props.get('pacer')).getAndIncrement();
 long due=origin+(long)(slot*1000000000L/700L);
 long remaining=due-System.nanoTime();
 if(remaining>0)java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
 if(System.nanoTime()>=origin+5000000000L)throw new org.apache.jorphan.util.JMeterStopThreadException('shared 5s deadline');
}
long n = ((java.util.concurrent.atomic.AtomicLong)props.get('sequence')).getAndIncrement();
int i=(int)(n%128); vars.put('token',props.getProperty('tokenPrefix')+i);
vars.put('uid',String.valueOf(100000+i)); vars.put('device','fast-device-'+i);
vars.put('rid',props.getProperty('runPrefix')+'-'+n); vars.put('submitted','false');"""
    post="vars.put('submitted',String.valueOf(prev.getResponseCode()=='202' && (prev.getResponseDataAsString() =~ /\"newSubmission\"\\s*:\\s*true/).find()));"
    body='{"requestId":"${rid}","userId":${uid},"eventSessionId":"fast-session","ticketSkuId":"fast-sku","quantity":1,"deviceId":"${device}"}'
    s='<HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="'+label+'" enabled="true">'
    s+='<elementProp name="HTTPsampler.Arguments" elementType="Arguments"><collectionProp name="Arguments.arguments"><elementProp name="" elementType="HTTPArgument">'+prop('HTTPArgument.always_encode','false','boolProp')+prop('Argument.value',body)+prop('Argument.metadata','=')+'</elementProp></collectionProp></elementProp>'
    for k,v in {'HTTPSampler.domain':'127.0.0.1','HTTPSampler.port':'${__P(port)}','HTTPSampler.protocol':'http','HTTPSampler.path':'/api/tickets/reservations','HTTPSampler.method':'POST','HTTPSampler.implementation':'HttpClient4','HTTPSampler.connect_timeout':'2000','HTTPSampler.response_timeout':'2000'}.items():s+=prop(k,v)
    for k,v in {'HTTPSampler.use_keepalive':'true','HTTPSampler.postBodyRaw':'true','HTTPSampler.follow_redirects':'false'}.items():s+=prop(k,v,'boolProp')
    s+='</HTTPSamplerProxy><hashTree><HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="Headers"><collectionProp name="HeaderManager.headers">'
    for k,v in {'Content-Type':'application/json','authorization':'${token}','X-Fast-Trace':'${rid}'}.items():s+='<elementProp name="" elementType="Header">'+prop('Header.name',k)+prop('Header.value',v)+'</elementProp>'
    s+='</collectionProp></HeaderManager><hashTree/>'
    for tag,gui,name,code in [('JSR223PreProcessor','TestBeanGUI','Fixture',pre),('JSR223PostProcessor','TestBeanGUI','Receipt flag',post)]:
        s+='<'+tag+' guiclass="'+gui+'" testclass="'+tag+'" testname="'+name+'">'+prop('scriptLanguage','groovy')+prop('cacheKey','true')+prop('script',code)+'</'+tag+'><hashTree/>'
    return s+'</hashTree>'

def group(name,threads,loops,children,duration=None):
    x='<ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="'+name+'" enabled="true">'+prop('ThreadGroup.on_sample_error','continue')
    x+='<elementProp name="ThreadGroup.main_controller" elementType="LoopController">'+prop('LoopController.continue_forever','false','boolProp')+prop('LoopController.loops',loops)+'</elementProp>'
    x+=prop('ThreadGroup.num_threads',threads)+prop('ThreadGroup.ramp_time',0)+prop('ThreadGroup.scheduler',str(duration is not None).lower(),'boolProp')
    if duration:x+=prop('ThreadGroup.duration',duration)+prop('ThreadGroup.delay',0)
    return x+'</ThreadGroup><hashTree>'+children+'</hashTree>'

def make_plan(path):
    init="props.put('sequence',new java.util.concurrent.atomic.AtomicLong());"
    gate="""new File(props.getProperty('ready')).text='ready';
long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
while(!new File(props.getProperty('go')).exists()) { if(System.nanoTime()>end)throw new IllegalStateException('gate timeout'); Thread.sleep(10); }
props.put('pacer',new java.util.concurrent.atomic.AtomicLong()); props.put('origin',Long.valueOf(System.nanoTime()));"""
    def js(name,script):return '<JSR223Sampler guiclass="TestBeanGUI" testclass="JSR223Sampler" testname="'+name+'">'+prop('scriptLanguage','groovy')+prop('cacheKey','true')+prop('script',script)+'</JSR223Sampler><hashTree/>'
    xml='<?xml version="1.0" encoding="UTF-8"?><jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3"><hashTree><TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="Real auth isolated ingress">'+prop('TestPlan.serialize_threadgroups','true','boolProp')+'</TestPlan><hashTree>'
    xml+=group('Initialize',1,1,js('Initialize sequence',init))
    xml+=group('Warmup',8,8,sampler('warmup'))
    xml+=group('Wait for clean measurement boundary',1,1,js('Gate',gate))
    xml+=group('Measured POST', '${__P(concurrency)}',-1,sampler('ingress'),5)
    path.write_text(xml+'</hashTree></hashTree></jmeterTestPlan>',encoding='utf-8')

def summarize_jtl(path):
    with path.open(encoding='utf-8-sig') as stream:rows=[r for r in csv.DictReader(stream) if r['label']=='ingress']
    if not rows:raise RuntimeError('No measured JMeter samples')
    starts=[int(r['timeStamp']) for r in rows];ends=[int(r['timeStamp'])+int(r['elapsed']) for r in rows]
    seconds=max(5,(max(ends)-min(starts))/1000)
    status=dict(collections.Counter(r['responseCode'] for r in rows));submitted=sum(r.get('submitted')=='true' for r in rows)
    http_rows=[r for r in rows if r['responseCode'].isdigit()]
    return dict(http_qps=len(http_rows)/seconds,submitted_qps=submitted/seconds,submitted=submitted,status=status,http_ms=f.pct([int(r['elapsed']) for r in rows]),
                response_window_seconds=seconds,counts=dict(target_upper_bound=3500,attempted=len(rows),response_completed=len(http_rows),not_attempted_against_target=3500-len(rows),
                timeouts=sum('timed out' in r.get('responseMessage','').lower() for r in rows),inflight_at_load_end=sum(s<=min(starts)+5000<e for s,e in zip(starts,ends)),starts_after_first_sample_plus_5s=sum(s>min(starts)+5000 for s in starts),inflight_at_cleanup=0),
                rejected_429_fraction=int(status.get('429',0))/len(rows),first_sample_epoch_ms=min(starts),last_response_epoch_ms=max(ends))

def one(app,out,plan,kind,c):
    folder=out/(kind+'-'+str(c));folder.mkdir();samples=[];proc=None;stop=threading.Event();monitor=None
    try:
        if kind=='jmeter':
            logfile=(folder/'console.log').open('w',encoding='utf-8')
            cmd=[str(f.JAVA/'java.exe'),'-Xms128m','-Xmx384m','-XX:+UseSerialGC','-jar',str(ROOT/'.tools/apache-jmeter-5.6.3/bin/ApacheJMeter.jar'),'-n','-t',str(plan),'-l',str(folder/'samples.jtl'),'-j',str(folder/'jmeter.log'),
                 '-Jport='+str(app.port),'-Jconcurrency='+str(c),'-JtokenPrefix='+app.userprefix,'-JrunPrefix=jm-'+uuid.uuid4().hex[:12],'-Jready='+str(folder/'ready'),'-Jgo='+str(folder/'go'),
                 '-Jsample_variables=rid,submitted','-Jjmeter.save.saveservice.output_format=csv','-Jjmeter.save.saveservice.response_data=false','-Jjmeter.save.saveservice.response_data.on_error=false','-Jjmeter.save.saveservice.samplerData=false','-Jjmeter.save.saveservice.requestHeaders=false','-Jjmeter.save.saveservice.responseHeaders=false']
            f.save(folder/'command.json',cmd);proc=subprocess.Popen(cmd,cwd=folder,stdout=logfile,stderr=logfile,creationflags=subprocess.CREATE_NO_WINDOW)
            deadline=time.monotonic()+45
            while not (folder/'ready').exists():
                if proc.poll() is not None:raise RuntimeError('JMeter exited; see console.log')
                if time.monotonic()>deadline:raise TimeoutError('JMeter setup timeout')
                time.sleep(.1)
        app.drain(3);probe_before=app.control('reset',{});before=app.diagnostic(timeout=8);ri=app.redis_info()
        host_before=f.client_resources();java_before=f.owned_java_cpu(app.proc);client_before=f.owned_java_cpu(proc) if proc else None
        def observe():
            while not stop.wait(.5):
                try:samples.append(dict(host=f.client_resources(),client=process_memory(proc) if proc else None,server=app.diagnostic(timeout=2)))
                except Exception as e:samples.append(dict(error=str(e)))
        monitor=threading.Thread(target=observe,daemon=True);monitor.start();clock=time.perf_counter()
        if kind=='python':
            result=f.run_window(app.port,5,c,700,True,app.fixture,'py-'+uuid.uuid4().hex[:12],diagnostic=app.diagnostic,monitor_resources=False)
            raw=result.pop('requests')
            for row in raw:row.pop('response',None)
            f.save(folder/'requests.json',raw)
        else:
            (folder/'go').write_text('go');proc.wait(timeout=18);result=summarize_jtl(folder/'samples.jtl')
            if proc.returncode:raise RuntimeError('JMeter nonzero exit')
        elapsed=time.perf_counter()-clock;stop.set();monitor.join(4)
        java_after=f.owned_java_cpu(app.proc);client_after=f.owned_java_cpu(proc) if proc else None;host_after=f.client_resources()
        probe=app.drain(3);after=app.diagnostic(traces=True,timeout=8)
        delta={k:probe['counters'].get(k,0)-probe_before['counters'].get(k,0) for k in set(probe['counters'])|set(probe_before['counters'])}
        consumed=probe['consumed']-probe_before['consumed']
        result.update(tool=kind,concurrency=c,target_rate=700,load_seconds=5,measurement_wall_seconds=elapsed,before=before,after=after,probe=probe,probe_before=probe_before,consumed_delta=consumed,counter_deltas=delta,resource_samples=samples,
                      host_before=host_before,host_after=host_after,java_cpu_before=java_before,java_cpu_after=java_after,client_cpu_before=client_before,client_cpu_after=client_after)
        phases=after['phases'];result['redis_mean_ms']={k:phases.get(k,{}).get('nanos',0)/max(1,phases.get(k,{}).get('count',0))/1e6 for k in ('redis_command.hGetAll','redis_command.pExpire','redis_connection_handle_acquire')}
        result['java_cpu_cores']=(java_after['cpu_nanos']-java_before['cpu_nanos'])/(java_after['nano']-java_before['nano'])
        if proc:result['client_cpu_cores']=(client_after['cpu_nanos']-client_before['cpu_nanos'])/(client_after['nano']-client_before['nano'])
        else:result['client_cpu_cores']=(result['client_after']['cpu_nanos']-result['client_before']['cpu_nanos'])/(result['client_after']['nano']-result['client_before']['nano'])
        (folder/'redis-before.txt').write_text(ri);(folder/'redis-after.txt').write_text(app.redis_info());f.save(folder/'result.json',result)
        if result['submitted']!=consumed:raise AssertionError(('submitted != consumed',result['submitted'],consumed))
        print(json.dumps(dict(tool=kind,c=c,qps=result['http_qps'],p95=result['http_ms']['p95'],submitted=result['submitted'],consumed=consumed),ensure_ascii=False),flush=True)
        return result
    finally:
        stop.set()
        if monitor:monitor.join(4)
        if proc and proc.poll() is None:proc.kill();proc.wait(5)
        if proc:logfile.close()

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=18088);args=parser.parse_args()
    info=json.loads(f.docker('inspect','credit-risk-v2-redis'))[0]
    if not info['State']['Running']:raise RuntimeError('Dedicated Redis must already be running; this script never restarts containers')
    out=ROOT/'performance/results'/('jmeter-crosscheck-'+time.strftime('%Y%m%d_%H%M%S')+'-'+uuid.uuid4().hex[:4]);out.mkdir(parents=True)
    plan=ROOT/'performance/jmeter/ingress_crosscheck.jmx';make_plan(plan);before=fingerprint();f.save(out/'production-before.json',before)
    f.save(out/'configuration.json',dict(duration=5,concurrency=[32,64,128],target_rate=700,keepalive=True,identity_count=128,consumer_ms=0,jmeter_heap='128m/384m',model_difference='Python scheduled bounded producer skips late/full slots; JMeter worker-side shared pacer catches up overdue slots, with one common 5s deadline. Actual arrivals are not strictly equivalent.'))
    app=None
    try:
        app=f.Isolated(SimpleNamespace(port=args.port,consumer_ms=0,process_timeout=300,diagnostics=True),out)
        fixture=app.fixture(0,'preflight-'+uuid.uuid4().hex[:12]);status,_=f.http(app.port,'/api/tickets/reservations',None,fixture['body']);assert status==401,status
        results=[]
        for c in (32,64,128):
            for kind in (('python','jmeter') if c!=64 else ('jmeter','python')):results.append(one(app,out,plan,kind,c))
        f.save(out/'summary.json',[{k:v for k,v in r.items() if k not in ('before','after','resource_samples')} for r in results])
    finally:
        if app:app.close()
        f.save(out/'cleanup.json',dict(production_unchanged=fingerprint()==before,owned_java_exited=not app or app.proc.poll() is not None,redis_still_running=json.loads(f.docker('inspect','credit-risk-v2-redis'))[0]['State']['Running']))
        print('RESULT_DIRECTORY='+str(out),flush=True)

if __name__=='__main__':main()
