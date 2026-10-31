"""Correctness only. Localhost JDBC/RESP, fresh owned schemas, NO Docker commands or load generator."""
import argparse,json,os,pathlib,secrets,socket,subprocess,time,uuid,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1]
JAVA=pathlib.Path('C:/Program Files/Java/jdk-21.0.11/bin')
MAVEN=pathlib.Path('E:/maven/apache-maven-3.9.12')

class RedisAdmin:
    def __init__(self,password):
        self.sock=socket.create_connection(('127.0.0.1',26379),timeout=5);self.file=self.sock.makefile('rb');self.command('AUTH',password)
    def response(self):
        kind=self.file.read(1);line=self.file.readline().rstrip(b'\r\n')
        if kind==b'+':return line.decode()
        if kind==b':':return int(line)
        if kind==b'-':raise RuntimeError('Redis command failed: '+line.decode())
        if kind==b'$':
            length=int(line)
            if length<0:return None
            value=self.file.read(length);assert self.file.read(2)==b'\r\n';return value.decode()
        if kind==b'*':return [self.response() for _ in range(int(line))]
        raise RuntimeError('Invalid Redis response')
    def command(self,*args):
        data=[str(a).encode() for a in args];self.sock.sendall(b'*'+str(len(data)).encode()+b'\r\n'+b''.join(b'$'+str(len(x)).encode()+b'\r\n'+x+b'\r\n' for x in data));return self.response()
    def close(self):self.file.close();self.sock.close()

def run(suite):
    stamp=time.strftime('%Y%m%d_%H%M%S')+'_'+uuid.uuid4().hex[:4];owner=uuid.uuid4().hex;schema='credit_risk_controlled_'+stamp;user='crctrl_'+uuid.uuid4().hex[:16];password=secrets.token_hex(18)
    out=ROOT/'performance/results'/('controlled-correctness-'+suite+'-'+stamp);out.mkdir(parents=True)
    values=dict(x.split('=',1) for x in (ROOT/'docker/credit_risk/.env').read_text(encoding='utf-8-sig').splitlines() if '=' in x and not x.startswith('#'))
    assert values['CREDIT_RISK_MYSQL_PORT']=='13306'
    base=ROOT/'ticket-risk-platform';deps=(base/'target/benchmark-classpath.txt').read_text(encoding='utf-8-sig').strip();helper=ROOT/'.tmp/controlled-dispatch/helper-classes';helper.mkdir(exist_ok=True)
    cp=str(base/'target/classes')+os.pathsep+str(base/'target/test-classes')+os.pathsep+deps
    env=os.environ.copy();env.update(CONTROLLED_MYSQL_ROOT_PASSWORD=values['CREDIT_RISK_MYSQL_ROOT_PASSWORD'])
    url="jdbc:mysql://127.0.0.1:13306/?useSSL=false&serverTimezone=UTC&useLegacyDatetimeCode=false&sessionVariables=time_zone='%2B00:00'&allowPublicKeyRetrieval=true"
    resource=dict(schema=schema,user=user,password=password,owner=owner,url=url)
    def resource_op(mode):
        result=subprocess.run([str(JAVA/'java.exe'),'-Duser.timezone=UTC','-cp',str(helper)+os.pathsep+deps,'ControlledTestSql'],input=json.dumps(dict(resource,mode=mode)),text=True,env=env,capture_output=True,timeout=30,creationflags=subprocess.CREATE_NO_WINDOW)
        (out/('resource-'+mode+'.log')).write_text(result.stdout+result.stderr,encoding='utf-8');assert result.returncode==0,'Owned resource '+mode+' failed; see log'
    created=False;redis=None;redis_db=None;passed=False
    print('RESULT_DIRECTORY='+str(out),flush=True)
    try:
        with (out/'helper-compile.log').open('w',encoding='utf-8') as log:
            compiled=subprocess.run([str(JAVA/'javac.exe'),'-encoding','UTF-8','-proc:none','-cp',deps,'-d',str(helper),str(ROOT/'performance/ControlledTestSql.java')],stdout=log,stderr=log,timeout=60,creationflags=subprocess.CREATE_NO_WINDOW)
        assert compiled.returncode==0
        resource_op('create');created=True
        test_url=url.replace('13306/','13306/'+schema)
        env.update(CONTROLLED_TEST_URL=test_url,CONTROLLED_TEST_USER=user,CONTROLLED_TEST_PASSWORD=password,CONTROLLED_TEST_OUT=str(out),CONTROLLED_CHILD_CP=cp)
        names=['ticket/ReservationDispatchTest','ticket/ReservationDispatchBatchTest','ticket/ControlledDispatchMysqlTest','ticket/DispatchClaimProcess']
        selection='ControlledDispatchMysqlTest';reports={'ticket.ControlledDispatchMysqlTest':16}
        if suite in ('locking','isolated'):
            redis=RedisAdmin(values['CREDIT_RISK_REDIS_PASSWORD'])
            for index in range(1,256):
                redis.command('SELECT',index)
                if redis.command('EVAL',"if redis.call('DBSIZE')==0 then redis.call('SET',KEYS[1],ARGV[1]);return 1 else return 0 end",1,'credit-risk:controlled-owner',owner)==1:redis_db=index;break
            assert redis_db is not None,'No empty Redis DB; no data was cleared'
            env.update(SPRING_DATASOURCE_URL=test_url,SPRING_DATASOURCE_USERNAME=user,SPRING_DATASOURCE_PASSWORD=password,SPRING_REDIS_HOST='127.0.0.1',SPRING_REDIS_PORT='26379',SPRING_REDIS_DATABASE=str(redis_db),SPRING_REDIS_PASSWORD=values['CREDIT_RISK_REDIS_PASSWORD'])
            names=['integration/ReservationV2IT','integration/ControlledDispatchIT','integration/ControlledDispatchIsolationIT']
            selection='ControlledDispatchIT#passQueueDoesNotReserveAndConfirmedHoldHasExactlyTenMinutes+reviewCanReserveWithoutAgent+mysqlQuotaFailureAfterLuaIsRolledBackAndCompensatedExactlyOnce+recoveryCannotCrossLuaAndV2DatabaseCommitBoundary+manifestDeletedAfterQueueingCannotBeMaskedByParsedCache+versionChangedWhileQueuedRejectsOldAuthorization,ControlledDispatchIsolationIT#committedDecisionWakesIsolatedControlAndCreatesOneReliableHold'
            selection=selection.split(',ControlledDispatchIsolationIT#')[0]
            reports={'integration.ControlledDispatchIT':6}
            if suite=='isolated':
                selection='ControlledDispatchIsolationIT#committedDecisionWakesIsolatedControlAndCreatesOneReliableHold'
                reports={'integration.ControlledDispatchIsolationIT':1}
        compile_args=[str(JAVA/'javac.exe'),'-encoding','UTF-8','-proc:none','-parameters','-source','8','-target','8','-cp',cp,'-d',str(base/'target/test-classes')]+[str(base/'src/test/java/com/ticketing'/(x+'.java')) for x in names]
        with (out/'compile.log').open('w',encoding='utf-8') as log:compiled=subprocess.run(compile_args,stdout=log,stderr=log,timeout=90,creationflags=subprocess.CREATE_NO_WINDOW)
        assert compiled.returncode==0,'Test compilation failed'
        args=[str(JAVA/'java.exe'),'-Duser.timezone=UTC','-Dmaven.home='+str(MAVEN),'-Dclassworlds.conf='+str(MAVEN/'bin/m2.conf'),'-Dmaven.multiModuleProjectDirectory='+str(base),'-cp',str(MAVEN/'boot/plexus-classworlds-2.9.0.jar'),'org.codehaus.plexus.classworlds.launcher.Launcher','-B','-f',str(base/'pom.xml'),'-Dtest='+selection,'-Dticketing.v2.integration=true','-Duser.timezone=UTC','surefire:test']
        (out/'configuration.json').write_text(json.dumps(dict(args=args,schema=schema,redis_db=redis_db,no_docker_commands=True,kind='correctness; NOT performance'),indent=2),encoding='utf-8')
        with (out/'tests.log').open('w',encoding='utf-8') as log:result=subprocess.run(args,cwd=ROOT,env=env,stdout=log,stderr=log,timeout=420,creationflags=subprocess.CREATE_NO_WINDOW)
        counts={}
        for name,expected in reports.items():
            source=base/'target/surefire-reports'/('TEST-com.ticketing.'+name+'.xml');assert source.exists();(out/source.name).write_bytes(source.read_bytes());report=ET.parse(source).getroot();counts[name]={k:int(report.get(k,'0')) for k in ('tests','failures','errors','skipped')};assert counts[name]==dict(tests=expected,failures=0,errors=0,skipped=0),counts[name]
        (out/'result.json').write_text(json.dumps(dict(exit_code=result.returncode,counts=counts),indent=2),encoding='utf-8');assert result.returncode==0,'Correctness failed; no performance test is allowed'
        passed=True
    finally:
        if redis is not None:
            if redis_db is not None:
                redis.command('SELECT',redis_db);assert redis.command('GET','credit-risk:controlled-owner')==owner
                keys=redis.command('KEYS','*');assert all(x.startswith(('credit-risk:controlled-owner','ticket:','risk:','login:')) for x in keys),keys
                if keys:redis.command('UNLINK',*keys)
            redis.close()
        if created:resource_op('cleanup')
        (out/'cleanup.json').write_text(json.dumps(dict(passed=passed,owned_schema_removed=created,owned_redis_removed=redis_db is not None,docker_commands=0,performance_runs=0),indent=2),encoding='utf-8');print('DONE '+str(out),flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--suite',choices=['mysql','locking','isolated'],required=True);run(parser.parse_args().suite)
