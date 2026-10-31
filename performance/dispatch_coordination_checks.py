"""Targeted dispatch/locking correctness; fresh schemas only, no container lifecycle operations."""
import argparse,hashlib,json,os,pathlib,secrets,shlex,subprocess,time,uuid,xml.etree.ElementTree as ET
import fast_ingress_benchmark as f
from real_lock_diagnostic import sql,redis

def run(suite):
    artifact=f.ROOT/'performance/results/dispatch-optimization'
    identity=json.loads((artifact/'change-identity.json').read_text(encoding='utf-8'))
    production=f.ROOT/'ticket-risk-platform/src/main/java/com/ticketing/ticket/ReservationDispatch.java'
    if hashlib.sha256(production.read_bytes()).hexdigest()!=identity['candidate_sha256']:
        raise RuntimeError('Candidate was rolled back. See DISPATCH-OPTIMIZATION.md; explicitly restore/recompile the reviewed candidate before candidate-only checks. No resources were created.')
    stamp=time.strftime('%Y%m%d_%H%M%S')+'_'+uuid.uuid4().hex[:4]
    db='credit_risk_lockdiag_dispatch_'+stamp;user='crdc_'+uuid.uuid4().hex[:16];password=secrets.token_hex(18);owner=uuid.uuid4().hex
    out=f.ROOT/'performance/results'/('dispatch-checks-'+suite+'-'+stamp);out.mkdir(parents=True)
    rdb=None;created=False
    for container,project in [('credit-risk-dev-mysql','credit-risk-dev'),('credit-risk-v2-redis','credit-risk-v2-tests')]:
        info=json.loads(f.docker('inspect',container))[0]
        assert info['State']['Running'] and info['Config']['Labels'].get('com.docker.compose.project')==project
    print('RESULT_DIRECTORY='+str(out),flush=True)
    try:
        sql('',f"CREATE DATABASE {db} CHARACTER SET utf8mb4; CREATE USER '{user}'@'%' IDENTIFIED BY '{password}'; GRANT ALL ON {db}.* TO '{user}'@'%';")
        created=True;sql(db,"CREATE TABLE tb_lockdiag_owner(owner VARCHAR(80) PRIMARY KEY); INSERT INTO tb_lockdiag_owner VALUES('"+owner+"');")
        url='jdbc:mysql://127.0.0.1:13306/'+db+"?useSSL=false&serverTimezone=UTC&useLegacyDatetimeCode=false&sessionVariables=time_zone='%2B00:00'&characterEncoding=utf8&allowPublicKeyRetrieval=true"
        env=os.environ.copy();env.update(DISPATCH_TEST_URL=url,DISPATCH_TEST_USER=user,DISPATCH_TEST_PASSWORD=password)
        test='ReservationDispatchCoordinationTest,RiskSubjectFenceTest,ReservationDispatchMysqlTest'
        if suite=='locking':
            claim="if redis.call('DBSIZE')==0 then redis.call('SET',KEYS[1],ARGV[1]);return 1 else return 0 end"
            command='export REDISCLI_AUTH="$REDIS_PASSWORD"; for i in $(seq 1 255); do x=$(redis-cli --no-auth-warning --raw -n "$i" EVAL '+shlex.quote(claim)+' 1 credit-risk:dispatch-check-owner '+shlex.quote(owner)+'); if [ "$x" = 1 ]; then printf "%s" "$i"; exit 0; fi; done; exit 1'
            rdb=int(f.docker('exec','credit-risk-v2-redis','sh','-c',command))
            values=dict(x.split('=',1) for x in (f.ROOT/'docker/credit_risk/.env').read_text(encoding='utf-8-sig').splitlines() if '=' in x and not x.startswith('#'))
            env.update(SPRING_DATASOURCE_URL=url,SPRING_DATASOURCE_USERNAME=user,SPRING_DATASOURCE_PASSWORD=password,SPRING_REDIS_HOST='127.0.0.1',SPRING_REDIS_PORT='26379',SPRING_REDIS_DATABASE=str(rdb),SPRING_REDIS_PASSWORD=values['CREDIT_RISK_REDIS_PASSWORD'])
            test='ReservationV2IT#passQueueDoesNotReserveAndConfirmedHoldHasExactlyTenMinutes+reviewCanReserveWithoutAgent+mysqlQuotaFailureAfterLuaIsRolledBackAndCompensatedExactlyOnce+recoveryCannotCrossLuaAndV2DatabaseCommitBoundary'
        maven=pathlib.Path('E:/maven/apache-maven-3.9.12')
        args=[str(f.JAVA/'java.exe'),'-Dmaven.home='+str(maven),'-Dclassworlds.conf='+str(maven/'bin/m2.conf'),'-Dmaven.multiModuleProjectDirectory='+str(f.ROOT/'ticket-risk-platform'),'-cp',str(maven/'boot/plexus-classworlds-2.9.0.jar'),'org.codehaus.plexus.classworlds.launcher.Launcher','-B','-f',str(f.ROOT/'ticket-risk-platform/pom.xml'),'-Dtest='+test,'-Dticketing.v2.integration=true','-Duser.timezone=UTC','surefire:test']
        # Production was compiled and its unit tests passed before these checks.
        # Compile just the affected test classes, not every unrelated test module.
        base=f.ROOT/'ticket-risk-platform';cp=str(base/'target/classes')+os.pathsep+str(base/'target/test-classes')+os.pathsep+(base/'target/benchmark-classpath.txt').read_text(encoding='utf-8-sig').strip()
        names=['ticket/ReservationDispatchTest','ticket/ReservationDispatchCoordinationTest','ticket/ReservationDispatchMysqlTest'] if suite=='dispatch' else ['integration/ReservationV2IT']
        sources=[]
        for name in names:
            source=base/'src/test/java/com/ticketing'/str(name+'.java')
            if not source.exists():source=artifact/'candidate-tests'/(name.split('/')[-1]+'.java')
            assert source.is_file(),source
            sources.append(str(source))
        compile_args=[str(f.JAVA/'javac.exe'),'-encoding','UTF-8','-proc:none','-source','8','-target','8','-cp',cp,'-d',str(base/'target/test-classes')]+sources
        f.save(out/'configuration.json',dict(args=args,compile_args=compile_args,suite=suite,schema=db,redis_db=rdb,container_lifecycle_operations=0))
        with (out/'compile.log').open('w',encoding='utf-8') as log:
            compiled=subprocess.run(compile_args,cwd=f.ROOT,env=env,stdout=log,stderr=log,timeout=90,creationflags=subprocess.CREATE_NO_WINDOW)
        assert compiled.returncode==0,'Affected test compilation failed'
        with (out/'tests.log').open('w',encoding='utf-8') as log:
            result=subprocess.run(args,cwd=f.ROOT,env=env,stdout=log,stderr=log,timeout=360,creationflags=subprocess.CREATE_NO_WINDOW)
        reports=f.ROOT/'ticket-risk-platform/target/surefire-reports'
        names=['ticket.ReservationDispatchCoordinationTest','risk.RiskSubjectFenceTest','ticket.ReservationDispatchMysqlTest'] if suite=='dispatch' else ['integration.ReservationV2IT']
        for name in names:
            source=reports/('TEST-com.ticketing.'+name+'.xml')
            assert source.exists(),'Missing test report: '+name
            (out/source.name).write_bytes(source.read_bytes())
            report=ET.parse(source).getroot()
            expected=4 if suite=='locking' else 2 if name.startswith('risk.') else 13
            assert int(report.get('tests','0'))==expected and int(report.get('skipped','0'))==0,'Tests must really execute: '+name
        f.save(out/'result.json',dict(exit_code=result.returncode));assert result.returncode==0,'Correctness checks failed; do not benchmark'
    finally:
        if rdb is not None:
            assert redis(rdb,'GET credit-risk:dispatch-check-owner')==owner
            keys=redis(rdb,'KEYS *').splitlines()
            assert all(k.startswith(('credit-risk:dispatch-check-owner','ticket:','risk:','login:')) for k in keys),keys
            if keys:redis(rdb,'UNLINK '+' '.join(keys))
        if created:
            assert sql(db,'SELECT owner FROM tb_lockdiag_owner;')==owner
            sql('',f"DROP DATABASE {db}; DROP USER '{user}'@'%';")
        f.save(out/'cleanup.json',dict(owned_schema_removed=created,redis_removed=rdb is not None,container_lifecycle_operations=0))
        print('DONE '+str(out),flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--suite',choices=['dispatch','locking'],required=True);run(parser.parse_args().suite)
