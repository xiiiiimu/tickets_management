"""L1 timing tests + isolated protocol-4 correctness only; no application HTTP load, no container changes."""
import argparse,json,os,re,secrets,shutil,subprocess,time,uuid,xml.etree.ElementTree as ET
from pathlib import Path
from v3_local_env import Environment,ROOT,JAVA,free_memory,save

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,required=True);ap.add_argument('--execute',action='store_true');ap.add_argument('--label',default='l2-protocol4');ap.add_argument('--scope',choices=['all','held-faults'],default='all');a=ap.parse_args()
    if not a.execute:raise RuntimeError('--execute required')
    out=a.out.resolve();build=out/'build'
    if not re.fullmatch(r'l2-protocol4(?:-r[2-9])?',a.label):raise RuntimeError('Bounded correctness-run label required')
    if (out/(a.label+'-result.json')).exists():raise RuntimeError('Do not overwrite an existing correctness run')
    if out.parent!=ROOT/'performance/results' or not out.name.startswith('v3-plan-opt-'):raise RuntimeError('Owned result directory required')
    if json.loads((out/'l1-final-result.json').read_text())['exit']!=0:raise RuntimeError('Armory L1 must pass first')
    if a.label.endswith('-r3') and json.loads((out/'l1-prometheus-result.json').read_text())['exit']!=0:raise RuntimeError('Real Prometheus boundary test must pass first')
    tests=build/'src/test/java/com/ticketing/ticket'
    for name in ['V3BeginTimingTest.java','V3StreamIT.java']:shutil.copy2(ROOT/'ticket-risk-platform/src/test/java/com/ticketing/ticket'/name,tests/name)
    armory=build/'src/test/java/com/ticketing/sale/ArmoryRuntimeGateTest.java';shutil.copy2(ROOT/'ticket-risk-platform/src/test/java/com/ticketing/sale/ArmoryRuntimeGateTest.java',armory)
    cp=str(build/'target/classes')+';'+str(build/'target/test-classes')+';'+(ROOT/'ticket-risk-platform/target/benchmark-classpath.txt').read_text(encoding='utf-8-sig').strip()
    args=[str(JAVA/'javac.exe'),'--release','8','-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(build/'target/test-classes'),str(tests/'V3BeginTimingTest.java'),str(tests/'V3StreamIT.java'),str(armory)]
    start=time.monotonic();r=subprocess.run(args,capture_output=True,timeout=60,creationflags=subprocess.CREATE_NO_WINDOW);(out/(a.label+'-compile.log')).write_bytes(r.stdout+r.stderr)
    save(out/(a.label+'-compile.json'),dict(exit=r.returncode,seconds=time.monotonic()-start,target_java=8))
    if r.returncode:raise RuntimeError('Targeted test compilation failed')
    invalid=[str(p.relative_to(build)) for p in (build/'target/test-classes').rglob('*.class') if int.from_bytes(p.read_bytes()[6:8],'big')>52]
    save(out/(a.label+'-bytecode-check.json'),dict(maximum_class_major=52,unsupported=invalid))
    if invalid:raise RuntimeError('Unsupported test bytecode; no database fixture created')
    base=json.loads((out/'l1-command.json').read_text());base=['surefire:test' if x=='test' else x for x in base]
    def run_tests(name,selection,env,extra,timeout):
        if (out/(name+'-result.json')).exists():raise RuntimeError('Never overwrite completed test run')
        args=['-Dtest='+selection if x.startswith('-Dtest=') else '-DargLine=-Xmx384m -Duser.timezone=UTC' if x.startswith('-DargLine=') else x for x in base]
        args[-1:-1]=extra;save(out/(name+'-command.json'),args);t=time.monotonic()
        with (out/(name+'.log')).open('wb') as log:
            p=subprocess.Popen(args,cwd=ROOT,env=env,stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW)
            try:code=p.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                subprocess.run(['taskkill','/PID',str(p.pid),'/T','/F'],capture_output=True,timeout=15);raise
        report=build/'target/surefire-reports'/('TEST-com.ticketing.ticket.'+selection.split('#')[0]+'.xml');counts={}
        if report.exists():
            shutil.copy2(report,out/(name+'-surefire.xml'));tree=ET.parse(report).getroot();counts={k:int(tree.get(k,'0')) for k in ['tests','errors','failures','skipped']}
        save(out/(name+'-result.json'),dict(exit=code,seconds=time.monotonic()-t,counts=counts));print(name,code,counts,flush=True)
        if code or not counts or counts['errors'] or counts['failures'] or counts['skipped']:raise RuntimeError(name+' correctness failed')
    passed=build/'target/surefire-reports/TEST-com.ticketing.ticket.V3BeginTimingTest.xml'
    if passed.exists() and all(int(ET.parse(passed).getroot().get(k,'0'))==0 for k in ['errors','failures','skipped']):
        save(out/'l1-begin-reused.json',dict(source=str(passed),tests=int(ET.parse(passed).getroot().get('tests')),note='Already passed in L1 final; not run twice'))
    else:run_tests('l1-begin','V3BeginTimingTest',os.environ.copy(),[],90)
    if free_memory()['available']<1024**3:raise RuntimeError('Need >=1GiB RAM before isolated Spring correctness context')
    env=Environment(ROOT/'docker/credit_risk_v3_bench/runs/54090d45');env.assert_container('mysql');env.assert_container('redis')
    tag=uuid.uuid4().hex[:12];schema='credit_risk_v3_opt_'+tag;user='v3opt_'+tag;owner=uuid.uuid4().hex;password=secrets.token_hex(24);rdb=15
    if not re.fullmatch(r'credit_risk_v3_opt_[a-f0-9]{12}',schema) or schema==env.state['schema']:raise RuntimeError('Schema whitelist failure')
    save(out/(a.label+'-ownership.json'),dict(schema=schema,user=user,owner=owner,redis_db=rdb,mysql_port=13316,redis_port=16389))
    def sql(text,timeout=60,cleanup=False):
        if re.search(r'\b(DROP|TRUNCATE|FLUSH)\b',text,re.I) and not cleanup:raise RuntimeError('No destructive SQL during tests')
        env.assert_container('mysql')
        command='export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql --protocol=TCP -h127.0.0.1 -uroot --default-character-set=utf8mb4 -N -B'
        return env.docker('exec','-i',env.state['mysql_container'],'sh','-ec',command,data=text.encode(),timeout=timeout)
    redis=None;created=False;account_created=False;claimed=False;clean={}
    try:
        if sql("SELECT (SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"+schema+"')+(SELECT COUNT(*) FROM mysql.user WHERE user='"+user+"');")!='0':raise RuntimeError('Schema or account already exists')
        redis=env.redis();redis.owned();redis.call('SELECT',rdb)
        marker='credit-risk:v3-plan-test:owner'
        if redis.call('DBSIZE')!=0:raise RuntimeError('Redis test DB15 is not empty; no existing data will be changed')
        if redis.call('SET',marker,owner,'NX')!='OK':raise RuntimeError('Redis test ownership failed')
        claimed=True;created=True
        sql(f"CREATE DATABASE {schema} CHARACTER SET utf8mb4; CREATE TABLE {schema}.tb_plan_test_owner(owner VARCHAR(32) PRIMARY KEY); INSERT INTO {schema}.tb_plan_test_owner VALUES('{owner}');")
        sql(f"CREATE USER '{user}'@'%' IDENTIFIED BY '{password}';");account_created=True
        sql(f"GRANT ALL ON {schema}.* TO '{user}'@'%';")
        migration='USE '+schema+';\n'+ '\n'.join(p.read_text(encoding='utf-8-sig') for p in sorted((ROOT/'ticket-risk-platform/src/main/resources/db/migration').glob('V1*.sql')) if not p.name.startswith('V101_'))
        t=time.monotonic();sql(migration,timeout=120);save(out/(a.label+'-initialize.json'),dict(seconds=time.monotonic()-t))
        sql(f"INSERT INTO {schema}.tb_reservation_dispatch(id,pass_weight,capacity,active_limit,review_age_ms,runtime_protocol,ordered_lua_enabled,quick_review_enabled,vector_inventory_enabled,redis_lane_enabled) VALUES(1,2,512,4,2000,4,1,1,1,1);")
        child={k:v for k,v in os.environ.items() if not k.startswith(('SPRING_','TICKETING_','DB_')) and k not in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')}
        child.update(SPRING_DATASOURCE_URL=f"jdbc:mysql://127.0.0.1:13316/{schema}?useSSL=false&serverTimezone=UTC&useLegacyDatetimeCode=false&sessionVariables=time_zone='%2B00:00'&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=30000&useLocalSessionState=true&useLocalTransactionState=true&enableQueryTimeouts=false&cachePrepStmts=true",
            SPRING_DATASOURCE_USERNAME=user,SPRING_DATASOURCE_PASSWORD=password,SPRING_REDIS_HOST='127.0.0.1',SPRING_REDIS_PORT='16389',SPRING_REDIS_DATABASE=str(rdb),SPRING_REDIS_PASSWORD=env.private['V3_REDIS_PASSWORD'])
        selection='V3StreamIT' if a.scope=='all' else 'V3StreamIT#commitThatSucceededButReportedAnErrorIsNeverCompensated+consumerCrashAfterInventoryLuaIsTakenOverAndCompletesExactlyOnce'
        run_tests(a.label,selection,child,['-Dticketing.v3.integration=true','-Dticketing.reservations-v2.attempts.quick-review=true','-Dticketing.inventory.vector-reservations=true','-Dticketing.sale.plan-single-flight=true','-Dticketing.sale.plan-detached-redis=true'],360 if a.scope=='all' else 180)
        clean['business_invariants_passed']=True
    finally:
        try:
            if redis and claimed:
                if redis.call('GET','credit-risk:v3-plan-test:owner')!=owner:raise RuntimeError('Redis ownership changed; cleanup refused')
                keys=redis.call('KEYS','*')
                if any(not k.startswith(('credit-risk:v3-plan-test:owner','ticket:','risk:','login:','{tkv3}')) for k in keys):raise RuntimeError('Unrecognized keys in owned test DB; cleanup refused')
                for i in range(0,len(keys),100):redis.call('DEL',*keys[i:i+100])
                clean['redis_keys_left']=redis.call('DBSIZE')
        finally:
            try:
                if redis:redis.__exit__(None,None,None)
            finally:
                try:
                    if created:
                        if sql(f'SELECT owner FROM {schema}.tb_plan_test_owner;')!=owner:raise RuntimeError('SQL owner mismatch; cleanup refused')
                        if account_created:sql(f"DROP USER '{user}'@'%';",cleanup=True)
                        sql(f"DROP DATABASE {schema};",cleanup=True)
                        clean['schema_and_user_absent']=sql(f"SELECT (SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{schema}')+(SELECT COUNT(*) FROM mysql.user WHERE user='{user}');")=='0'
                finally:
                    clean['existing_environment_untouched']=True;save(out/(a.label+'-cleanup.json'),clean)
    if not clean.get('schema_and_user_absent') or clean.get('redis_keys_left')!=0:raise RuntimeError('Cleanup incomplete')

if __name__=='__main__':main()
