"""Only E/F six-round controlled performance comparison on explicitly owned localhost schemas. No load generator."""
from pathlib import Path
import os,sys,json,secrets,uuid,time,subprocess,shutil,hashlib,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'performance'))
import controlled_dispatch_compare as c
from controlled_dispatch_checks import RedisAdmin
from ef_redis_cleanup import cleanup_owned_redis
save=lambda p,x:p.write_text(json.dumps(x,ensure_ascii=False,indent=2),encoding='utf-8')
values=dict(x.split('=',1) for x in (ROOT/'docker/credit_risk/.env').read_text(encoding='utf-8-sig').splitlines() if '=' in x and not x.startswith('#'))
base=ROOT/'ticket-risk-platform';output=ROOT/'performance/results'/('attempt-ef-ab-'+time.strftime('%Y%m%d_%H%M%S'));output.mkdir();print('RESULT_DIRECTORY='+str(output),flush=True)
jfc=ET.parse(c.JAVA.parent/'lib/jfr/profile.jfc')
for event in jfc.getroot().findall('event'):
 if event.get('name') in ['jdk.InitialEnvironmentVariable','jdk.InitialSystemProperty','jdk.OldObjectSample']:
  for setting in event.findall('setting'):
   if setting.get('name')=='enabled':setting.text='false'
jfc.write(output/'safe-profile.jfc',encoding='utf-8',xml_declaration=True)
files=[p for folder in [base/'src/main',base/'src/test',base/'target/classes'] for p in folder.rglob('*') if p.is_file()]
identity={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files};save(output/'code-identity.json',identity)
gate=c.memory_gate();save(output/'resource-gate.json',gate);assert gate['ok'],'Resource threshold not met; no fixture created'
assert sys.argv[1:]==['--execute-approved'], 'Explicit approved comparison only'
c.check_environment(values,output)
selections={'ordered':'AttemptAcceptancePerformanceIT#oneControlledBatch'}
for number,mode in enumerate(['A','B','B','A','A','B'],1):
 suite='ordered'
 out=output/(str(number)+'-'+mode);out.mkdir();owner=uuid.uuid4().hex;schema='credit_risk_controlled_ef_ab_'+time.strftime('%Y%m%d_%H%M%S')+'_'+owner[:4];user='crctrl_'+owner[:16];password=secrets.token_hex(18)
 env=os.environ.copy();env.update(CONTROLLED_MYSQL_ROOT_PASSWORD=values['CREDIT_RISK_MYSQL_ROOT_PASSWORD'],COMPARISON_SCHEMA=schema,COMPARISON_OWNER=owner,COMPARISON_USER=user,COMPARISON_PASSWORD=password)
 save(out/'ownership.json',dict(schema=schema,user=user,owner=owner));db=None;red=None;rdb=None;created=False;proc=None;observer=None;ok=False
 try:
  assert identity=={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files},'Source or production bytecode changed; comparison stopped'
  assert c.memory_gate()['ok'],'Resource threshold not met'
  db=c.Database(env,out,'db-control');created=True;db.call('create')
  if suite!='mysql':
   db.call('initialize-schema')
   db.execute('INSERT INTO tb_reservation_dispatch(id,pass_weight,capacity,active_limit,review_age_ms,runtime_protocol,ordered_lua_enabled,quick_review_enabled,vector_inventory_enabled) VALUES(1,2,512,4,2000,3,1,1,'+('1' if mode=='B' else '0')+')')
   red=RedisAdmin(values['CREDIT_RISK_REDIS_PASSWORD'])
   for n in range(1,256):
    red.command('SELECT',n)
    if red.command('EVAL',"if redis.call('DBSIZE')==0 then redis.call('SET',KEYS[1],ARGV[1]);return 1 else return 0 end",1,'credit-risk:controlled-owner',owner)==1:rdb=n;break
   assert rdb is not None,'No empty Redis DB available'
  url='jdbc:mysql://127.0.0.1:13306/'+schema+"?useSSL=false&serverTimezone=UTC&useLegacyDatetimeCode=false&sessionVariables=time_zone='%2B00:00'&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=5000"
  testenv=os.environ.copy();testenv.pop('CONTROLLED_MYSQL_ROOT_PASSWORD',None);testenv.pop('SPRING_APPLICATION_JSON',None)
  testenv.update(SPRING_DATASOURCE_URL=url,SPRING_DATASOURCE_USERNAME=user,SPRING_DATASOURCE_PASSWORD=password,CONTROLLED_TEST_OWNER=owner,JAVA_HOME=str(c.JAVA.parent))
  if rdb is not None:testenv.update(SPRING_REDIS_HOST='127.0.0.1',SPRING_REDIS_PORT='26379',SPRING_REDIS_DATABASE=str(rdb),SPRING_REDIS_PASSWORD=values['CREDIT_RISK_REDIS_PASSWORD'])
  args=json.loads((ROOT/'.tmp/attempt-phase-b/unit-command.json').read_text(encoding='utf-8'));args=[('-Dtest='+selections[suite] if a.startswith('-Dtest=') else '-DargLine=-Xmx384m -Duser.timezone=UTC -XX:StartFlightRecording=filename='+str(out/'recording.jfr').replace('\\','/')+',settings='+(output/'safe-profile.jfc').as_posix()+',dumponexit=true,maxsize=32m' if a.startswith('-DargLine=') else a) for a in args]
  args[-1:-1]=['-Dcontrolled.vector='+str(mode=='B').lower(),'-Dvector.result.dir='+str(out).replace('\\','/'),'-Dticketing.v2.integration=true','-Dticketing.attempts.integration=true','-Dticketing.attempts.mysql=true']
  save(out/'command.json',dict(args=args,schema=schema,redis_db=rdb,kind='one controlled 50-request batch',business_load_generator=True,mode=mode,round=number,docker_operations=0))
  observer=c.Observer(env,out,schema)
  with (out/'tests.log').open('w',encoding='utf-8') as log:
   proc=subprocess.Popen(args,cwd=ROOT,env=testenv,stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW)
   try:rc=proc.wait(timeout=240)
   except subprocess.TimeoutExpired:
    subprocess.run(['taskkill','/PID',str(proc.pid),'/T','/F'],stdout=log,stderr=log,timeout=15);raise
  name='integration.AttemptAcceptancePerformanceIT'
  report=base/'target/surefire-reports'/('TEST-com.ticketing.'+name+'.xml');assert report.exists();shutil.copyfile(report,out/report.name)
  tree=ET.parse(report).getroot();counts={k:int(tree.get(k,'0')) for k in ['tests','failures','errors','skipped']};save(out/'result.json',dict(exit_code=rc,counts=counts));print(suite,json.dumps(counts),flush=True)
  assert rc==0 and counts['tests']==1 and counts['failures']==counts['errors']==counts['skipped']==0,'Correctness failed; no next suite'
  batch=json.loads((out/'batch.json').read_text());assert batch['all_invariants_passed'] and not batch['resource_pressure']
  print(json.dumps(dict(round=number,mode=mode,http_qps=batch['http_qps'],held_tps=batch['held_tps'],held=batch['held'],states=batch['states'])),flush=True)
  ok=True
 finally:
  if observer:observer.close();save(out/'db-observations.json',observer.rows)
  cleanup=dict(passed=ok,docker_operations=0,performance_loads=1)
  if proc and proc.poll() is None:subprocess.run(['taskkill','/PID',str(proc.pid),'/T','/F'],capture_output=True,timeout=15)
  try:
   if db and db.proc.poll() is None:
    snapshot={}
    for label,sql in {'order_lanes':'SELECT * FROM tb_reservation_sku_order','jobs':'SELECT request_id,state,result_code,lease_token FROM tb_reservation_job','stock':'SELECT sku_id,bucket_id,remaining_stock FROM tb_ticket_stock_bucket','orders':'SELECT id,status FROM tb_ticket_order'}.items():
     try:snapshot[label]=db.query(sql)
     except Exception as error:snapshot[label]=dict(unavailable=str(error))
    save(out/'final-snapshot.json',snapshot)
   if red:
    red.command('SELECT',rdb);assert red.command('GET','credit-risk:controlled-owner')==owner
    keys=red.command('KEYS','*');assert all(k.startswith(('credit-risk:controlled-owner','ticket:','risk:','login:')) for k in keys)
    stocks={k:red.command('GET',k) for k in keys if ':stock:bucket:' in k};save(out/'redis-final-stock.json',stocks)
    cleanup['redis_keys_left']=cleanup_owned_redis(red,owner);red.close()
  finally:
   if db:
    save(out/'database-phases.json',db.events)
    if created:
     if db.proc.poll() is not None:db.close();db=c.Database(env,out,'db-cleanup')
     cleanup['schema_and_account_absent']=db.call('cleanup').get('verified_absent',False)
    db.close()
   cleanup['owned_test_process_exited']=proc is None or proc.poll() is not None;save(out/'cleanup.json',cleanup)
  assert cleanup.get('schema_and_account_absent') and cleanup.get('redis_keys_left',0)==0 and cleanup['owned_test_process_exited'],'Cleanup failed; stop'
print('SIX APPROVED COMPARISON ROUNDS COMPLETED',flush=True)
