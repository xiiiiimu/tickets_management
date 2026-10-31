"""Manual, isolated Armory regression. Never deletes databases or flushes Redis.
Requires the manually started credit-risk-dev environment. Production sources are read-only.
"""
import concurrent.futures as futures
import collections
import hashlib
import json
import math
import os
import pathlib
import re
import socket
import statistics
import subprocess
import time
import threading
import urllib.request
import urllib.error
from datetime import datetime, timezone

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEV = ROOT / 'docker/credit_risk'
RUN = datetime.now().strftime('%Y%m%d_%H%M%S')
OUT = ROOT / 'performance/results' / ('armory-' + RUN)
OUT.mkdir(parents=True)
ENV = dict(line.split('=', 1) for line in (DEV / '.env').read_text(encoding='utf-8-sig').splitlines()
           if line and not line.startswith('#') and '=' in line)
BASE = 'http://127.0.0.1:18083'
DB = ''
RDB = 0
USER = 'crb_' + RUN.replace('_','')

def save(name, value):
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')

def docker(*args, stdin=None):
    p = subprocess.run(['docker', *args], input=stdin, capture_output=True,
                       encoding='utf-8', errors='replace', timeout=120)
    if p.returncode:
        raise RuntimeError('Docker failed: ' + p.stderr[-1500:])
    return p.stdout.strip()

def sql(statement, schema=True):
    return docker('exec', '-i', 'credit-risk-dev-mysql', 'sh', '-c',
                  'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -N -B ' + (DB if schema else ''), stdin=statement)

def redis(command):
    return docker('exec', '-i', 'credit-risk-dev-redis', 'sh', '-c',
                  'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --no-auth-warning --raw -n ' + str(RDB), stdin=command+'\n')

def http(path, data=None, token=None, method=None):
    headers = {'Content-Type': 'application/json'}
    if token: headers['authorization'] = token
    request = urllib.request.Request(BASE+path, data=None if data is None else json.dumps(data).encode(), headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=90) as r:
            body = r.read().decode()
            try: body = json.loads(body)
            except ValueError: pass
            return r.status, body
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()

def summary(values):
    a = sorted(values)
    return {**{f'p{p}': a[math.ceil(len(a)*p/100)-1] for p in (50,95,99)},
            'avg': statistics.mean(a), 'max': max(a), 'count':len(a)} if a else {}

def request(i, sku, prefix):
    begin = time.perf_counter()
    rid = prefix+'-'+str(i)
    try:
        status, body = http('/api/tickets/reserve', dict(requestId=rid,userId=100000+i,
            eventSessionId=sku+'-session',ticketSkuId=sku,quantity=1,deviceId='dev-'+str(i)), 'bench-'+str(i))
        return dict(requestId=rid,status=status,body=body,success=isinstance(body,dict) and body.get('success') is True,
                    ms=1000*(time.perf_counter()-begin))
    except Exception as e:
        return dict(requestId=rid,status=0,success=False,body=str(e),ms=1000*(time.perf_counter()-begin))

def batch(offset, sku, prefix):
    start=time.time(); begin=time.perf_counter()
    with futures.ThreadPoolExecutor(max_workers=32) as pool:
        rows=list(pool.map(lambda i:request(i,sku,prefix),range(offset+1,offset+161)))
    seconds=time.perf_counter()-begin
    return dict(start=start,end=time.time(),seconds=seconds,qps=160/seconds,
                latency_ms=summary([r['ms'] for r in rows]),successes=sum(r['success'] for r in rows),
                errors=dict(collections.Counter(str(r['status'])+':'+str(r['body']) for r in rows if not r['success'])),requests=rows)

def drain(expected, enabled=True):
    if not enabled: return
    end=time.monotonic()+240
    while time.monotonic()<end:
        c=sql("SELECT (SELECT COUNT(*) FROM tb_ticket_order WHERE status='ISSUED'),(SELECT COUNT(*) FROM tb_outbox_message WHERE status<>'SENT');")
        issued,pending=map(int,c.split())
        if issued==expected and pending==0:
            # RocketMQ persists consumer offsets every five seconds; allow shutdown without replay into the next schema.
            time.sleep(6)
            return
        time.sleep(1)
    raise RuntimeError('Fulfillment failed to drain: '+c)

def digest():
    q="SELECT JSON_OBJECT('digest',DIGEST,'sql',DIGEST_TEXT,'count',COUNT_STAR,'ms',SUM_TIMER_WAIT/1000000000,'lock_ms',SUM_LOCK_TIME/1000000000,'rows',SUM_ROWS_EXAMINED) FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='"+DB+"';"
    raw=sql(q,False)
    return [json.loads(line) for line in raw.splitlines() if line]

def locks():
    return {a:int(b) for a,b in (line.split() for line in sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_time','Innodb_row_lock_waits');").splitlines())}

def fingerprint():
    paths=list((ROOT/'ticket-risk-platform/src').rglob('*'))+list((DEV).glob('*.yml'))+list(DEV.glob('*.yaml'))
    return {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths if p.is_file()}

def fixture(on):
    sql((ROOT/'ticket-risk-platform/src/main/resources/schema.sql').read_text(encoding='utf-8'))
    sql((ROOT/'ticket-risk-platform/src/main/resources/data.sql').read_text(encoding='utf-8'))
    sql("UPDATE tb_risk_rule SET config=JSON_SET(config,'$.threshold',100000) WHERE rule_code IN ('IP_FREQUENCY','IP_ACCOUNTS'); UPDATE tb_risk_rule SET config=JSON_SET(config,'$.rateLimit',100000) WHERE rule_code='DECISION_POLICY';")
    sql('INSERT INTO tb_user(id,phone,nick_name,role,create_time) VALUES '+','.join(f"({100000+i},'139{i:08d}','bench','USER','2025-01-01')" for i in range(1,1201))+';')
    redis('\n'.join(f'HSET login:token:bench-{i} id {100000+i} role USER nickName bench' for i in range(1,1201))+'\nHSET login:token:bench-admin id 1 role ADMIN nickName admin')

def catalog(on):
    sale=int(time.time())+25
    for sku in ('warm','measured'):
        sql(f"INSERT INTO tb_event_session VALUES('{sku}-session','event-1','2027-12-31 19:30:00'); INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,sale_start_time,sale_end_time,purchase_limit,status,version) VALUES('{sku}','{sku}-session','bench',10,1000,1000,FROM_UNIXTIME({sale}),'2030-12-31',2,'ON_SALE',0);")
        if on:
            s,b=http('/api/admin/sessions/'+sku+'-session/assembly',dict(requestId='assemble-'+sku,expectedVersion=0),'bench-admin')
            assert s==200 and b.get('success'),(s,b)
        else:
            sql('INSERT INTO tb_ticket_stock_bucket(sku_id,bucket_id,total_stock,remaining_stock) VALUES '+','.join(f"('{sku}',{i},{63 if i<8 else 62},{63 if i<8 else 62})" for i in range(16))+';')
            s,b=http('/api/admin/skus/'+sku+'?status=ON_SALE',token='bench-admin',method='PATCH')
            assert s==200 and b.get('success'),(s,b)
    time.sleep(max(0,sale+1-time.time()))

def launch(label,on,background):
    env=os.environ.copy()
    env.update(SPRING_DATASOURCE_URL=f"jdbc:mysql://127.0.0.1:13306/{DB}?useSSL=false&serverTimezone=UTC&useLegacyDatetimeCode=false&characterEncoding=utf8&allowPublicKeyRetrieval=true",
       SPRING_DATASOURCE_USERNAME=USER,SPRING_DATASOURCE_PASSWORD=PASSWORD,
       SPRING_REDIS_HOST='127.0.0.1',SPRING_REDIS_PORT='16379',SPRING_REDIS_PASSWORD=ENV['CREDIT_RISK_REDIS_PASSWORD'],
       SPRING_REDIS_DATABASE=str(RDB),ROCKETMQ_NAME_SERVER='127.0.0.1:21976')
    args=[r'C:\Program Files\Java\jdk-21.0.11\bin\java.exe','-Xms128m','-Xmx384m','-XX:+UseSerialGC',
       '-Dspring.jdbc.getParameterType.ignore=true','-Duser.timezone=UTC','-jar',str(ROOT/'ticket-risk-platform/target/ticket-risk-platform-1.0.0-SNAPSHOT.jar'),
       '--server.port=18083','--spring.datasource.hikari.maximum-pool-size=10','--ticketing.inventory.bucket-count=16',
       '--ticketing.sale.armory.enabled='+str(on).lower(),'--ticketing.perf-log.enabled=true','--ticketing.perf-log.pool-interval-ms=200',
       '--ticketing.jobs.enabled='+str(background).lower(),'--ticketing.mq.enabled='+str(background).lower()]
    # CREATE_NO_WINDOW keeps the manually requested worker hidden on Windows.
    log=(OUT/(label+'.stdout.log')).open('w',encoding='utf-8'); err=(OUT/(label+'.stderr.log')).open('w',encoding='utf-8')
    proc=subprocess.Popen(args,cwd=ROOT/'ticket-risk-platform',env=env,stdout=log,stderr=err,creationflags=subprocess.CREATE_NO_WINDOW)
    deadline=time.monotonic()+150
    while time.monotonic()<deadline:
        if proc.poll() is not None: raise RuntimeError('Java exited: '+label)
        try:
            if http('/actuator/health')[1].get('status')=='UP': return proc,log,err
        except Exception: pass
        time.sleep(1)
    proc.terminate(); raise RuntimeError('Java startup timed out')

def one(label,on,index,background=True):
    global DB,RDB
    DB='credit_risk_bench_'+RUN+'_'+label.lower().replace('-','_'); RDB=index
    prepared=False
    if redis('DBSIZE')!='0':
        assert redis('GET credit-risk:benchmark-owner')==RUN+'-'+label, 'Redis database has a different owner'
        exists=sql("SELECT COUNT(*) FROM information_schema.schemata WHERE SCHEMA_NAME='"+DB+"';",False)=='1'
        if exists:
            assert sql('SELECT COUNT(*) FROM tb_ticket_order;')=='0', 'Incomplete measured data requires review; refusing reuse'
            prepared=True
        else:assert redis('DBSIZE')=='1','Unexpected cache data without database'
    else:
        redis('SET credit-risk:benchmark-owner '+RUN+'-'+label)
    if not prepared:
        sql('CREATE DATABASE '+DB+' CHARACTER SET utf8mb4; GRANT ALL ON '+DB+".* TO '"+USER+"'@'%';",False)
        fixture(on)
    for attempt in range(3):
        for suffix in ('stdout.log','stderr.log'):
            p=OUT/(label+'.'+suffix)
            if p.exists():p.rename(OUT/(label+'-startup-'+str(time.time_ns())+'.'+suffix))
        try:
            proc,log,err=launch(label,on,background);break
        except RuntimeError as error:
            print('STARTUP_RETRY',label,attempt,str(error),flush=True)
            if attempt==2:raise
            time.sleep(5)
    try:
        catalog(on)
        warm=[]
        for j in range(3):
            row=batch(j*160,'warm',label+'-warm'+str(j)); save(label+'-warm'+str(j)+'.json',row)
            assert row['successes']==160,row['errors']
            drain((j+1)*160,background);warm.append(row['qps'])
            print('WARM',label,j,round(row['qps'],2),flush=True)
        r=request(481,'measured',label+'-catalog'); assert r['success'],r
        drain(481,background)
        before= http('/actuator/prometheus')[1]; (OUT/(label+'-before.prom')).write_text(before,encoding='utf-8')
        d0=digest();l0=locks()
        samples=[];stop_sample=threading.Event();sampler=None
        jcmd=pathlib.Path(r'C:\Program Files\Java\jdk-21.0.11\bin\jcmd.exe')
        profiling=not background and label.endswith('deferred')
        if profiling:
            def sample_locks():
                while not stop_sample.is_set():
                    start=time.time()
                    try:
                        raw=sql("SELECT JSON_OBJECT('table',l.OBJECT_NAME,'index',l.INDEX_NAME,'mode',l.LOCK_MODE,'status',l.LOCK_STATUS,'blocking',w.BLOCKING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID;",False)
                        samples.append(dict(time=start,query_ms=1000*(time.time()-start),waits=[json.loads(s) for s in raw.splitlines() if s]))
                    except Exception as e:samples.append(dict(time=start,error=str(e)))
                    stop_sample.wait(.5)
            sampler=threading.Thread(target=sample_locks,daemon=True);sampler.start()
            subprocess.run([str(jcmd),str(proc.pid),'JFR.start','name=armory','settings=profile','maxsize=32m'],capture_output=True)
        try: result=batch(481,'measured',label+'-measure')
        finally:
            stop_sample.set()
            if sampler:sampler.join(timeout=10)
            if profiling:
                subprocess.run([str(jcmd),str(proc.pid),'JFR.stop','name=armory','filename='+str(OUT/(label+'.jfr'))],capture_output=True)
                save(label+'-lock-samples.json',samples)
        save(label+'-requests.json',result)
        after=http('/actuator/prometheus')[1]; (OUT/(label+'-after.prom')).write_text(after,encoding='utf-8')
        d1=digest();l1=locks()
        observed=sql("SELECT status,COUNT(*) FROM tb_ticket_order GROUP BY status;")
        drain(641,background)
        d2=digest();l2=locks()
        inventory=sql("SELECT bucket_id,total_stock,remaining_stock FROM tb_ticket_stock_bucket WHERE sku_id='measured' ORDER BY bucket_id;")
        rv=redis('MGET '+' '.join(f'ticket:{{measured}}:stock:bucket:{i}' for i in range(16)))
        bucketrows=[list(map(int,line.split())) for line in inventory.splitlines()]
        assert len(bucketrows)==16 and sum(r[2] for r in bucketrows)==839,inventory
        assert [r[2] for r in bucketrows]==list(map(int,rv.splitlines())),rv
        assert result['successes']==160,result['errors']
        save(label+'-diagnostics.json',dict(db=DB,redis_db=RDB,armory=on,background=background,warm_qps=warm,
          sql_before=d0,sql_after_sync=d1,sql_after_drain=d2,locks_before=l0,locks_after_sync=l1,locks_after_drain=l2,
          observed_orders_after_sync=observed,buckets=bucketrows,redis_remaining=rv,
          final_orders=sql("SELECT status,COUNT(*) FROM tb_ticket_order GROUP BY status;")))
        print('RESULT',label,json.dumps({k:result[k] for k in ('qps','latency_ms','successes','errors')}),flush=True)
    finally:
        proc.terminate(); proc.wait(timeout=30);log.close();err.close()

if __name__=='__main__':
    import secrets
    import argparse
    parser=argparse.ArgumentParser();parser.add_argument('--resume',type=pathlib.Path);parser.add_argument('--quiet-controls',action='store_true');options=parser.parse_args()
    if options.resume:
        OUT=options.resume.resolve();RUN=OUT.name.removeprefix('armory-');USER='crb_'+RUN.replace('_','')
        PASSWORD=(OUT/'.env').read_text(encoding='utf-8').strip().split('=',1)[1]
    else:
        PASSWORD=secrets.token_hex(20)
        # Password retained only in ignored result directory, never in console or tracked sources.
        (OUT/'.env').write_text('BENCH_PASSWORD='+PASSWORD+'\n',encoding='utf-8')
    with socket.socket() as check: check.bind(('127.0.0.1',18083))
    for name in ('mysql','redis','broker'):
        info=json.loads(docker('inspect','credit-risk-dev-'+name))[0]
        assert info['Config']['Labels']['com.docker.compose.project']=='credit-risk-dev'
        assert info['HostConfig']['RestartPolicy']['Name']=='no'
    if not options.resume:save('source-before.json',fingerprint())
    # A pre-existing benchmark account is never replaced.
    if not options.resume:sql("CREATE USER '"+USER+"'@'%' IDENTIFIED BY '"+PASSWORD+"';",False)
    try:
        if options.quiet_controls:
            for index,(label,on) in enumerate([('A-quiet',False),('B-quiet',True)],10):
                if (OUT/(label+'-diagnostics.json')).exists():continue
                one(label,on,index,False)
        else:
            for index,(label,on) in enumerate([('A1',False),('B1',True),('B2',True),('A2',False),('A3',False),('B3',True)],2):
                if (OUT/(label+'-diagnostics.json')).exists():continue
                one(label,on,index)
            for index,(label,on) in enumerate([('A-deferred',False),('B-deferred',True)],8):
                if (OUT/(label+'-diagnostics.json')).exists():continue
                one(label,on,index,False)
    finally:
        save('source-after.json',fingerprint())
        print('ARTIFACTS',str(OUT),flush=True)
