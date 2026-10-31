"""Windows-only V3 provisioning. No DROP/TRUNCATE/FLUSH/volume removal implementation.

Creates a run-specific MySQL, Redis, jar and app. Existing resources are never reset.
All mutating actions require --execute and an exact run manifest/owner whitelist.
"""
import argparse
import ctypes
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import random
import secrets
import shutil
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
RUNS = ROOT / 'docker/credit_risk_v3_bench/runs'
JAVA = Path('C:/Program Files/Java/jdk-21.0.11/bin')
MAVEN = Path('E:/maven/apache-maven-3.9.12')


def save(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')


def free_memory():
    class Memory(ctypes.Structure):
        _fields_ = [('length', ctypes.c_uint32), ('load', ctypes.c_uint32)] + [(n, ctypes.c_uint64) for n in
            ('total', 'available', 'page_total', 'page_available', 'virtual_total', 'virtual_available', 'extended')]
    memory = Memory(); memory.length = ctypes.sizeof(memory)
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(memory)):
        raise RuntimeError('Cannot measure Windows memory')
    return dict(available=memory.available, total=memory.total, load=memory.load)


class Environment:
    def __init__(self, directory):
        self.path = directory.resolve()
        if self.path.parent != RUNS.resolve():
            raise RuntimeError('Run directory outside the exact V3 whitelist')
        self.state = json.loads((self.path / 'manifest.json').read_text(encoding='utf-8'))
        self.private = dict(line.split('=', 1) for line in (self.path / '.env').read_text().splitlines() if '=' in line)
        self.owner = self.state['owner']
        if not re.fullmatch('[a-f0-9]{32}', self.owner) or self.state['id'] != self.path.name:
            raise RuntimeError('Invalid owned manifest')
        expected = 'credit_risk_v3_' + self.state['id']
        if self.state['schema'] != expected or self.state['mysql_port'] != 13316 or self.state['redis_port'] != 16389 or self.state['app_port'] != 18083:
            raise RuntimeError('Development resource collision rejected')

    def persist(self):
        save(self.path / 'manifest.json', self.state)

    def command(self, args, data=None, timeout=45):
        result = subprocess.run(args, input=data, capture_output=True, timeout=timeout, cwd=ROOT,
                                creationflags=subprocess.CREATE_NO_WINDOW)
        if result.returncode:
            error = result.stderr.decode('utf-8', errors='replace')[-3000:]
            for value in self.private.values():
                if len(value) >= 16:
                    error = error.replace(value, '[redacted]')
            raise RuntimeError('Command failed: ' + str(args[0]) + '\n' + error)
        return result.stdout.decode('utf-8', errors='replace').strip()

    def docker(self, *args, data=None, timeout=45):
        return self.command(['docker', *args], data, timeout)

    def compose(self, *args, timeout=240):
        return self.docker('compose', '--env-file', str(self.path / '.env'), '-p', self.state['project'],
                           '-f', str(self.path / 'compose.yaml'), *args, timeout=timeout)

    def assert_container(self, service):
        name = self.state[service + '_container']
        if name != 'credit-risk-v3-' + service + '-' + self.state['id']:
            raise RuntimeError('Container name outside whitelist')
        c = json.loads(self.docker('inspect', name))[0]
        labels = c['Config']['Labels']
        if labels.get('credit-risk.v3.owner') != self.owner or labels.get('com.docker.compose.project') != self.state['project']:
            raise RuntimeError('Foreign container: ' + name)
        expected_port = str(self.state[service + '_port'])
        bindings = c['HostConfig']['PortBindings']
        internal = '3306/tcp' if service == 'mysql' else '6379/tcp'
        if bindings[internal] != [{'HostIp': '127.0.0.1', 'HostPort': expected_port}]:
            raise RuntimeError('Container port mapping differs from whitelist')
        return c

    def sql(self, sql, database=True, timeout=120):
        self.assert_container('mysql')
        schema = self.state['schema']
        if not re.fullmatch('credit_risk_v3_[a-f0-9]{8}', schema):
            raise RuntimeError('Schema whitelist rejected')
        if re.search(r'\b(DROP|TRUNCATE|FLUSH)\b', sql, re.I):
            raise RuntimeError('Destructive SQL is not implemented by this tool')
        command = 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql --protocol=TCP -h127.0.0.1 -uroot --default-character-set=utf8mb4 -N -B'
        if database:
            command += ' ' + schema
        return self.docker('exec', '-i', self.state['mysql_container'], 'sh', '-ec', command,
                           data=sql.encode('utf-8'), timeout=timeout)

    def http(self, method, path, token=None, body=None, timeout=15, headers=None):
        if self.state['app_port'] != 18083:
            raise RuntimeError('Unexpected application port')
        h = {'Content-Type': 'application/json', **(headers or {})}
        if token:
            h['authorization'] = token
        request = urllib.request.Request('http://127.0.0.1:18083' + path,
            data=json.dumps(body).encode() if body is not None else None, method=method, headers=h)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                raw = response.read().decode(); return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raw=error.read().decode(); return error.code, json.loads(raw) if raw else None

    def redis(self):
        self.assert_container('redis')
        return Redis(self.state['redis_port'], self.private['V3_REDIS_PASSWORD'], self.owner)

    def build(self):
        if self.state.get('jar'):
            raise RuntimeError('Build already exists; create another run to build a different code version')
        if free_memory()['available'] < 1024**3:
            raise RuntimeError('At least 1 GiB free Windows memory required before build')
        build = self.path / 'build'; build.mkdir()
        shutil.copy2(ROOT / 'ticket-risk-platform/pom.xml', build / 'pom.xml')
        shutil.copytree(ROOT / 'ticket-risk-platform/src/main', build / 'src/main')
        hashes = {p.relative_to(build).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in build.rglob('*') if p.is_file()}
        save(self.path / 'build-input-sha256.json', hashes)
        command = [str(JAVA / 'java.exe'), '-Xmx256m', '-Dmaven.home=' + str(MAVEN),
                   '-Dclassworlds.conf=' + str(MAVEN / 'bin/m2.conf'), '-Dmaven.multiModuleProjectDirectory=' + str(build),
                   '-cp', str(MAVEN / 'boot/plexus-classworlds-2.9.0.jar'),
                   'org.codehaus.plexus.classworlds.launcher.Launcher', '-o', '-B', '-f', str(build / 'pom.xml'),
                   '-Dmaven.test.skip=true', 'package']
        save(self.path / 'build-command.json', command)
        start = time.monotonic()
        with (self.path / 'build.log').open('wb') as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, cwd=build, timeout=180,
                                    creationflags=subprocess.CREATE_NO_WINDOW)
        if result.returncode:
            raise RuntimeError('Isolated Maven build failed; inspect build.log')
        jar = build / 'target/ticket-risk-platform-1.0.0-SNAPSHOT.jar'
        import zipfile
        with zipfile.ZipFile(jar) as z:
            if 'BOOT-INF/classes/com/ticketing/ticket/V3Entry.class' not in z.namelist():
                raise RuntimeError('Built jar is missing V3')
        self.state.update(jar=str(jar), jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                          build_seconds=time.monotonic()-start)
        self.persist()

    def create(self):
        if free_memory()['available'] < 1024**3:
            raise RuntimeError('At least 1 GiB free Windows memory required before creating dependencies')
        if self.state.get('dependencies_created'):
            self.assert_container('mysql'); self.assert_container('redis'); return
        names = set(self.docker('ps', '-a', '--format', '{{.Names}}').splitlines())
        volumes = set(self.docker('volume', 'ls', '--format', '{{.Name}}').splitlines())
        for service in ('mysql', 'redis'):
            if self.state[service + '_container'] in names or self.state[service + '_volume'] in volumes:
                raise RuntimeError('Target resource already exists; refusing automatic reuse')
        baseline = []
        ids = self.docker('ps', '-aq').splitlines()
        if ids:
            for c in json.loads(self.docker('inspect', *ids)):
                baseline.append(dict(id=c['Id'], name=c['Name'], started=c['State']['StartedAt'],
                                     mounts=[{k:m.get(k) for k in ('Type','Name','Source','Destination')} for m in c['Mounts']]))
        save(self.path / 'existing-container-baseline.json', baseline)
        self.compose('config', '--quiet')
        self.compose('up', '-d', '--wait', '--wait-timeout', '180', '--pull', 'never')
        for service in ('mysql','redis'):
            self.assert_container(service)
        self.state['dependencies_created'] = True; self.persist()

    def initialize(self):
        self.assert_container('mysql')
        self.sql("CREATE TABLE IF NOT EXISTS tb_v3_bench_owner(owner VARCHAR(32) PRIMARY KEY); CREATE TABLE IF NOT EXISTS tb_v3_bench_migration(version INT PRIMARY KEY,name VARCHAR(200),sha256 VARCHAR(64));")
        owner = self.sql('SELECT owner FROM tb_v3_bench_owner;')
        if not owner:
            self.sql("INSERT INTO tb_v3_bench_owner VALUES('"+self.owner+"');")
        elif owner != self.owner:
            raise RuntimeError('Database owner mismatch')
        applied = []
        for migration in sorted((ROOT / 'ticket-risk-platform/src/main/resources/db/migration').glob('V1*.sql')):
            version = int(re.match(r'V(\d+)', migration.name)[1])
            if version == 101:  # demo orders/SKUs are not benchmark data; real rules are seeded below.
                continue
            text = migration.read_text(encoding='utf-8-sig'); digest = hashlib.sha256(migration.read_bytes()).hexdigest()
            old = self.sql(f'SELECT sha256 FROM tb_v3_bench_migration WHERE version={version};')
            if old and old != digest:
                raise RuntimeError('Applied migration checksum changed')
            if not old:
                self.sql(text, timeout=180)
                self.sql(f"INSERT INTO tb_v3_bench_migration VALUES({version},'{migration.name}','{digest}');")
            applied.append(dict(version=version, name=migration.name, sha256=digest))
        if self.sql('SELECT COUNT(*) FROM tb_reservation_job;') != '0' and not self.state.get('initialized'):
            raise RuntimeError('Unexpected existing jobs; no protocol switch allowed')
        if not self.state.get('initialized'):
            self.sql('INSERT INTO tb_reservation_dispatch(id,pass_weight,capacity,active_limit,review_age_ms,runtime_protocol,ordered_lua_enabled,quick_review_enabled,vector_inventory_enabled,redis_lane_enabled) VALUES(1,2,512,16,2000,4,1,1,1,1);')
            # Original repository rules; no threshold/policy edits.
            data = (ROOT / 'ticket-risk-platform/src/main/resources/db/migration/V101__ticketing_demo.sql').read_text(encoding='utf-8-sig')
            rules = re.findall(r'^INSERT INTO tb_risk_rule .*?;$', data, re.M)
            if len(rules) != 12:
                raise RuntimeError('Unexpected canonical rule seed structure')
            self.sql('\n'.join(rules))
        check = self.sql("SELECT runtime_protocol,ordered_lua_enabled,redis_lane_enabled,quick_review_enabled,vector_inventory_enabled,capacity,active_limit FROM tb_reservation_dispatch WHERE id=1;")
        if check != '4\t1\t1\t1\t1\t512\t16':
            raise RuntimeError('Protocol/flags/coordinator verification failed')
        self.sql('SELECT attempt_protocol,waiting_deadline,processing_started_at,qualification_seq,lua_turn_finished FROM tb_reservation_job LIMIT 0; SELECT * FROM tb_stock_bucket_allocation_item LIMIT 0;')
        save(self.path / 'migrations-applied.json', applied)
        self.state['initialized'] = True; self.persist()
        with self.redis() as r:
            old = r.call('GET','credit-risk:v3-bench:owner')
            if old is None:
                if r.call('DBSIZE') != 0:
                    raise RuntimeError('Nonempty unowned Redis; refusing reuse')
                r.call('SET','credit-risk:v3-bench:owner',self.owner,'NX')
            if r.call('GET','credit-risk:v3-bench:owner') != self.owner:
                raise RuntimeError('Redis owner mismatch')

    def start(self):
        if not self.state.get('initialized') or not self.state.get('jar'):
            raise RuntimeError('Build and initialization must finish first')
        self.assert_container('mysql'); self.assert_container('redis')
        if free_memory()['available'] < 768*1024**2:
            raise RuntimeError('Less than 768 MiB available before app start; no existing service will be stopped')
        with socket.socket() as probe:
            probe.bind(('127.0.0.1',self.state['app_port']))
        env = os.environ.copy()
        for k in list(env):
            if k.startswith(('SPRING_','TICKETING_','SERVER_','DB_')) or k in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','RISK_AGENT_URL','INTERNAL_API_KEY'):
                env.pop(k)
        env.update(self.private)
        command = [str(JAVA / 'java.exe'), '-Xms128m', '-Xmx384m', '-Duser.timezone=UTC',
                   '-Dspring.jdbc.getParameterType.ignore=true','-Dcredit.risk.v3.owner='+self.owner,
                   '-jar',self.state['jar'],'--spring.config.location=classpath:/application.yml,file:'+str(self.path/'application-v3-local.yml').replace('\\','/')]
        save(self.path/'start-command.json',command)
        with (self.path/'app.stdout.log').open('ab') as stdout,(self.path/'app.stderr.log').open('ab') as stderr:
            proc = subprocess.Popen(command,cwd=self.path,env=env,stdout=stdout,stderr=stderr,
                                    creationflags=subprocess.CREATE_NO_WINDOW)
        self.state['app_pid']=proc.pid;self.persist()
        deadline=time.monotonic()+150
        while time.monotonic()<deadline:
            if proc.poll() is not None:
                raise RuntimeError('V3 app exited; inspect app logs')
            if free_memory()['available']<384*1024**2:
                proc.terminate();proc.wait(15);raise RuntimeError('Low-memory stop of owned app; other resources retained')
            try:
                code,body=self.http('GET','/actuator/health',timeout=2)
                if code==200 and body.get('status')=='UP':
                    self.state['started']=True;self.persist();return
            except (OSError,ValueError):
                pass
            time.sleep(1)
        proc.terminate();proc.wait(15);raise RuntimeError('Bounded startup timeout; owned JVM stopped')

    def prepare(self):
        if self.state.get('fixtures_prepared'):
            raise RuntimeError('Fixtures already exist; no clearing or rearming is implemented')
        if self.sql('SELECT owner FROM tb_v3_bench_owner;') != self.owner:
            raise RuntimeError('Database ownership check failed')
        # All buyer ids/devices/IPs are distinct; 30% real REVIEW, 70% real PASS portraits.
        requests=[];statements=['START TRANSACTION;']
        for first in range(0,5010,250):
            user_values=[];wallet_values=[];profile_values=[]
            for i in range(first,min(first+250,5010)):
                user=3000000+i;decision='REVIEW' if i%10<3 else 'PASS'
                user_values.append(f"({user},'19{user:09d}','v3-buyer','USER','2025-01-01')")
                wallet_values.append(f'({user},10000)')
                profile_values.append(f"('{uuid.uuid4()}','USER','{user}','{'MEDIUM' if decision=='REVIEW' else 'LOW'}','{decision}',{50 if decision=='REVIEW' else 0},1,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 2 DAY))")
                requests.append(dict(userId=user,token=secrets.token_hex(20),deviceId=f'v3-device-{user}',ip=f'10.96.{i//256}.{i%256}',profile=decision))
            statements += ['INSERT INTO tb_user(id,phone,nick_name,role,create_time) VALUES '+','.join(user_values)+';',
                           'INSERT INTO tb_wallet_account(user_id,available_balance) VALUES '+','.join(wallet_values)+';',
                           'INSERT INTO tb_risk_profile(id,subject_type,subject_value,risk_level,decision,score,version,expires_at) VALUES '+','.join(profile_values)+';']
        statements.append("INSERT INTO tb_user(id,phone,nick_name,role,create_time) VALUES(1,'13800000001','v3-admin','ADMIN','2025-01-01');COMMIT;")
        self.sql('\n'.join(statements),timeout=180)
        admin=secrets.token_hex(24)
        with self.redis() as r:
            r.owned()
            commands=[]
            for item in requests:
                commands += [('HSET','login:token:'+item['token'],'id',str(item['userId']),'role','USER'),('EXPIRE','login:token:'+item['token'],172800)]
            commands += [('HSET','login:token:'+admin,'id','1','role','ADMIN'),('EXPIRE','login:token:'+admin,172800)]
            r.pipeline(commands)
        self.private['V3_ADMIN_TOKEN']=admin
        with (self.path/'.env').open('a',encoding='utf-8') as f:f.write('V3_ADMIN_TOKEN='+admin+'\n')
        # 5 x 1000 stays within the demonstrated per-SKU scale; each uses the unchanged 16 buckets.
        now=dt.datetime.now(dt.timezone.utc);sale_start=now+dt.timedelta(seconds=90);sale_end=now+dt.timedelta(hours=6)
        def timestamp(d):return d.strftime('%Y-%m-%d %H:%M:%S')
        sessions={'formal':'v3-formal-'+self.state['id'],'smoke':'v3-smoke-'+self.state['id']}
        stmts=['START TRANSACTION;']
        for kind,session in sessions.items():
            event='event-'+session
            stmts += [f"INSERT INTO tb_event(id,name,description,venue,status) VALUES('{event}','V3 {kind}','owned benchmark','local','ON_SALE');",f"INSERT INTO tb_event_session VALUES('{session}','{event}','{timestamp(sale_end)}');"]
            for index in range(5 if kind=='formal' else 1):
                sku=f'{session}-{index}';quantity=1000 if kind=='formal' else 20
                stmts.append(f"INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,sale_start_time,sale_end_time,purchase_limit,single_purchase_limit,status) VALUES('{sku}','{session}','{kind}-{index}',10,{quantity},{quantity},'{timestamp(sale_start)}','{timestamp(sale_end)}',1,1,'ON_SALE');")
        stmts.append('COMMIT;');self.sql('\n'.join(stmts))
        for kind,session in sessions.items():
            code,body=self.http('POST',f'/api/admin/sessions/{session}/assembly',admin,dict(expectedVersion=0,requestId='assemble-'+session),timeout=60)
            if code!=200 or not body.get('success') or body.get('data',{}).get('state')!='READY' or body['data'].get('cacheReady') is not True:
                save(self.path/('armory-'+kind+'.json'),dict(status=code,response=body));raise RuntimeError('Real Armory assembly failed')
            save(self.path/('armory-'+kind+'.json'),body)
        for i,item in enumerate(requests):
            kind='formal' if i<5000 else 'smoke';session=sessions[kind]
            item.update(eventSessionId=session,ticketSkuId=f'{session}-{i//1000 if kind=="formal" else 0}',quantity=1)
        fixtures=dict(isolated=True,owner=self.owner,schema=self.state['schema'],app_port=self.state['app_port'],
                      sale_start_epoch_ms=int(sale_start.timestamp()*1000),sale_end_epoch_ms=int(sale_end.timestamp()*1000),requests=requests[:5000])
        save(self.path/'fixtures.json',fixtures);save(self.path/'smoke-fixtures.json',requests[5000:])
        self.state.update(fixtures_prepared=True,sessions=sessions,sale_start_epoch_ms=fixtures['sale_start_epoch_ms'],
                          sale_end_epoch_ms=fixtures['sale_end_epoch_ms'],formal_buyers=5000,formal_stock=5000)
        self.persist()

    def smoke(self):
        if self.state.get('smoke_passed'):raise RuntimeError('Smoke already recorded; no automatic rerun or overwrite')
        fixtures=json.loads((self.path/'smoke-fixtures.json').read_text())
        while time.time()*1000<self.state['sale_start_epoch_ms']:
            time.sleep(min(1,(self.state['sale_start_epoch_ms']-time.time()*1000)/1000))
        results=[]
        for i,item in enumerate(fixtures[:3]):
            rid='v3-smoke-'+self.state['id']+'-'+str(i)
            request={k:item[k] for k in ('userId','eventSessionId','ticketSkuId','quantity','deviceId')};request['requestId']=rid
            code,receipt=self.http('POST','/api/tickets/reservations',item['token'],request,headers={'X-Forwarded-For':item['ip']})
            if code!=202 or not receipt.get('success') or receipt.get('data',{}).get('receiptSemantics')!='V3_PROCESSING':
                save(self.path/'smoke-failure.json',dict(status=code,response=receipt));raise RuntimeError('V3 HTTP acceptance failed')
            deadline=time.monotonic()+30;terminal=None
            while time.monotonic()<deadline:
                row=self.sql(f"SELECT state FROM tb_reservation_job WHERE request_id='{rid}';")
                if row=='RESERVED':terminal=row;break
                if row in ('REJECTED','FAILED','EXPIRED','SOLD_OUT','QUEUE_TIMEOUT'):
                    raise RuntimeError('Smoke terminal failure: '+self.sql(f"SELECT state,result_code FROM tb_reservation_job WHERE request_id='{rid}';"))
                time.sleep(.25)
            if terminal!='RESERVED':raise RuntimeError('Smoke did not reach reliable HELD within 30s')
            duplicate_code,duplicate=self.http('POST','/api/tickets/reservations',item['token'],request,headers={'X-Forwarded-For':item['ip']})
            rows=self.sql(f"SELECT COUNT(*) FROM tb_reservation WHERE request_id='{rid}' AND status='HELD';")
            if duplicate_code!=202 or rows!='1':raise RuntimeError('Smoke duplicate/HELD invariant failed')
            committed=self.sql(f"SELECT j.state,r.status,o.status,q.status FROM tb_reservation_job j JOIN tb_reservation r ON r.id=j.reservation_id JOIN tb_ticket_order o ON o.id=j.order_id JOIN tb_reserve_request q ON q.request_id=j.request_id WHERE j.request_id='{rid}';")
            if committed!='RESERVED\tHELD\tWAITING_PAYMENT\tCOMPLETED':raise RuntimeError('Committed transaction invariants failed')
            if self.sql(f"SELECT ip FROM tb_reservation_job WHERE request_id='{rid}';")!=item['ip']:
                raise RuntimeError('Forwarded client IP was not applied; real risk would share one IP')
            results.append(dict(requestId=rid,profile=item['profile'],http=code,duplicate_http=duplicate_code,committed=committed))
        smoke_sku=self.state['sessions']['smoke']+'-0'
        with self.redis() as r:
            r.owned();redis_stock=sum(int(r.call('GET','ticket:{'+smoke_sku+'}:stock:bucket:'+str(i))) for i in range(16))
            stream=r.call('XLEN','{tkv3}:stream');groups=r.call('XINFO','GROUPS','{tkv3}:stream')
        mysql_stock=int(self.sql(f"SELECT SUM(remaining_stock) FROM tb_ticket_stock_bucket WHERE sku_id='{smoke_sku}';"))
        if mysql_stock!=17 or redis_stock!=17:raise RuntimeError('Smoke stock mismatch')
        formal=self.state['sessions']['formal']
        formal_stock=int(self.sql(f"SELECT SUM(b.remaining_stock) FROM tb_ticket_stock_bucket b JOIN tb_ticket_sku s ON s.id=b.sku_id WHERE s.event_session_id='{formal}';"))
        if formal_stock!=5000:raise RuntimeError('Formal stock was consumed by smoke')
        report=dict(orders=results,mysql_smoke_stock=mysql_stock,redis_smoke_stock=redis_stock,formal_stock=formal_stock,
                    stream_length=stream,groups=groups,memory=free_memory(),observed_at=dt.datetime.now().astimezone().isoformat())
        save(self.path/'smoke-results.json',report)
        self.state['smoke_passed']=True;self.persist()

    def stop(self):
        self.assert_container('mysql');self.assert_container('redis')
        pid=int(self.state.get('app_pid',0))
        if pid:
            marker='-Dcredit.risk.v3.owner='+self.owner
            script=f"$p=Get-CimInstance Win32_Process -Filter 'ProcessId={pid}'; if($null -ne $p){{if($p.Name -ne 'java.exe' -or -not $p.CommandLine.Contains('{marker}')){{throw 'PID ownership mismatch'}}; Stop-Process -Id {pid} -ErrorAction Stop}}"
            self.command(['pwsh','-NoProfile','-Command',script],timeout=30)
        self.compose('stop');self.state['started']=False;self.persist()

    def load_arena(self, destination):
        """Only the separately authorized formal runner calls this: NEW scene, never reset stock.

        Create/assemble before a fresh opening time so 'open to 5000 HELD' is measurable,
        rather than backdating the old prepared scene. Existing arena/data are retained.
        """
        if self.state.get('formal_test_started'):raise RuntimeError('A formal run was already started; no automatic rerun')
        if self.sql('SELECT owner FROM tb_v3_bench_owner;')!=self.owner:raise RuntimeError('Database owner mismatch')
        if self.sql("SELECT COUNT(*) FROM tb_risk_profile WHERE subject_type='USER' AND CAST(subject_value AS UNSIGNED)>=3000000 AND CAST(subject_value AS UNSIGNED)<3005000 AND expires_at>UTC_TIMESTAMP();")!='5000':raise RuntimeError('Formal profiles expired/missing; do not replace them with PASS')
        tag=uuid.uuid4().hex[:8];session='v3-load-'+self.state['id']+'-'+tag;event='e-'+session
        start=(dt.datetime.now(dt.timezone.utc)+dt.timedelta(seconds=45)).replace(microsecond=0);end=start+dt.timedelta(hours=6)
        fmt=lambda value:value.strftime('%Y-%m-%d %H:%M:%S')
        sql=['START TRANSACTION;',f"INSERT INTO tb_event(id,name,description,venue,status) VALUES('{event}','V3 measured','owned formal arena','local','ON_SALE');",f"INSERT INTO tb_event_session VALUES('{session}','{event}','{fmt(end)}');"]
        for i in range(5):sql.append(f"INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,sale_start_time,sale_end_time,purchase_limit,single_purchase_limit,status) VALUES('s-{tag}-{i}','{session}','formal-{i}',10,1000,1000,'{fmt(start)}','{fmt(end)}',1,1,'ON_SALE');")
        sql.append('COMMIT;');self.sql('\n'.join(sql))
        admin=self.private['V3_ADMIN_TOKEN']
        with self.redis() as r:
            r.owned()
            if r.call('HMGET','login:token:'+admin,'id','role')!=['1','ADMIN']:
                if self.sql("SELECT role FROM tb_user WHERE id=1;")!='ADMIN':raise RuntimeError('Test administrator identity changed')
                # New test login session, still authenticated by the real Redis interceptor.
                admin=secrets.token_hex(24);r.call('HSET','login:token:'+admin,'id','1','role','ADMIN');r.call('EXPIRE','login:token:'+admin,1800)
        status,body=self.http('POST',f'/api/admin/sessions/{session}/assembly',admin,dict(expectedVersion=0,requestId='arm-'+tag),timeout=30)
        save(destination/'armory.json',dict(status=status,response=body))
        if status!=200 or body.get('data',{}).get('state')!='READY' or not body['data'].get('cacheReady'):raise RuntimeError('New formal Armory did not become READY')
        source=json.loads((self.path/'fixtures.json').read_text());random.Random(42).shuffle(source['requests'])
        source['requests']=[dict(row,eventSessionId=session,ticketSkuId='s-'+tag+'-'+str(i%5)) for i,row in enumerate(source['requests'])]
        source.update(sale_start_epoch_ms=int(start.timestamp()*1000),sale_end_epoch_ms=int(end.timestamp()*1000))
        save(destination/'fixtures.json',source)
        self.state['formal_test_started']=True;self.persist()
        return session,source['sale_start_epoch_ms'],destination/'fixtures.json'

    def status(self):
        result={'manifest':self.state,'memory':free_memory()}
        result['containers']={service:self.assert_container(service)['State']['Status'] for service in ('mysql','redis')}
        try:result['http']=self.http('GET','/actuator/health',timeout=3)
        except OSError:result['http']='not reachable'
        print(json.dumps(result,ensure_ascii=False,indent=2))


class Redis:
    def __init__(self,port,password,owner):
        if port in (6379,16379,26379) or not 1024<port<65536:raise RuntimeError('Redis port whitelist failed')
        self.socket=socket.create_connection(('127.0.0.1',port),timeout=10);self.socket.settimeout(15);self.file=self.socket.makefile('rb');self.owner=owner
        if self.call('AUTH',password)!='OK':raise RuntimeError('Redis auth failed')
    def __enter__(self):return self
    def __exit__(self,*args):self.file.close();self.socket.close()
    def read(self):
        line=self.file.readline()
        if not line:raise EOFError('Redis connection closed')
        kind=line[:1];value=line[1:-2]
        if kind==b'+':return value.decode()
        if kind==b'-':raise RuntimeError('Redis error: '+value.decode())
        if kind==b':':return int(value)
        if kind==b'$':
            n=int(value)
            if n<0:return None
            result=self.file.read(n);self.file.read(2);return result.decode()
        if kind==b'*':return [self.read() for _ in range(int(value))]
        raise RuntimeError('Unknown Redis reply')
    @staticmethod
    def encode(args):
        parts=[str(a).encode() for a in args];return b'*'+str(len(parts)).encode()+b'\r\n'+b''.join(b'$'+str(len(p)).encode()+b'\r\n'+p+b'\r\n' for p in parts)
    def call(self,*args):
        if str(args[0]).upper() in ('FLUSHALL','FLUSHDB','SWAPDB'):raise RuntimeError('Redis reset operations are forbidden')
        self.socket.sendall(self.encode(args));return self.read()
    def owned(self):
        if self.call('GET','credit-risk:v3-bench:owner')!=self.owner:raise RuntimeError('Redis owner marker mismatch')
    def pipeline(self,commands):
        for begin in range(0,len(commands),400):
            self.owned();batch=commands[begin:begin+400]
            if any(str(a[0]).upper() not in ('HSET','EXPIRE') for a in batch):raise RuntimeError('Seed pipeline whitelist rejected')
            self.socket.sendall(b''.join(self.encode(a) for a in batch))
            for _ in batch:self.read()


def new_run():
    if os.name!='nt':raise RuntimeError('Use Windows native PowerShell, not a cloud/Linux environment')
    if free_memory()['available']<1024**3:raise RuntimeError('Need 1 GiB available Windows RAM; no other process will be stopped')
    for port in (13316,16389,18083):
        with socket.socket() as probe:probe.bind(('127.0.0.1',port))
    identity=uuid.uuid4().hex[:8];owner=uuid.uuid4().hex;directory=RUNS/identity;directory.mkdir(parents=True)
    state=dict(id=identity,owner=owner,project='credit-risk-v3-bench-'+identity,schema='credit_risk_v3_'+identity,
               mysql_container='credit-risk-v3-mysql-'+identity,redis_container='credit-risk-v3-redis-'+identity,
               mysql_volume='credit-risk-v3-mysql-'+identity+'-data',redis_volume='credit-risk-v3-redis-'+identity+'-data',
               mysql_port=13316,redis_port=16389,app_port=18083,created_at=dt.datetime.now().astimezone().isoformat())
    save(directory/'manifest.json',state)
    private=dict(V3_MYSQL_ROOT_PASSWORD=secrets.token_hex(24),V3_MYSQL_PASSWORD=secrets.token_hex(24),V3_REDIS_PASSWORD=secrets.token_hex(24))
    (directory/'.env').write_text('\n'.join(k+'='+v for k,v in private.items())+'\n',encoding='utf-8')
    compose=f'''name: {state['project']}
services:
  mysql:
    image: mysql:8.0.32
    container_name: {state['mysql_container']}
    restart: "no"
    labels: {{credit-risk.v3.owner: "{owner}"}}
    environment:
      MYSQL_ROOT_PASSWORD: ${{V3_MYSQL_ROOT_PASSWORD}}
      MYSQL_DATABASE: {state['schema']}
      MYSQL_USER: v3bench
      MYSQL_PASSWORD: ${{V3_MYSQL_PASSWORD}}
      TZ: UTC
    command: ["--default-authentication-plugin=mysql_native_password", "--character-set-server=utf8mb4", "--default-time-zone=+00:00", "--innodb-buffer-pool-size=67108864", "--max-connections=80", "--innodb-flush-log-at-trx-commit=1", "--sync-binlog=1"]
    ports: ["127.0.0.1:13316:3306"]
    volumes: ["mysql-data:/var/lib/mysql"]
    healthcheck:
      test: ["CMD-SHELL", "MYSQL_PWD=$$MYSQL_ROOT_PASSWORD mysqladmin --protocol=TCP -h127.0.0.1 -uroot ping --silent"]
      interval: 3s
      timeout: 3s
      retries: 60
  redis:
    image: redis:7.2.0
    container_name: {state['redis_container']}
    restart: "no"
    labels: {{credit-risk.v3.owner: "{owner}"}}
    environment: {{REDIS_PASSWORD: "${{V3_REDIS_PASSWORD}}"}}
    command: ["sh", "-ec", "exec redis-server --appendonly yes --appendfsync everysec --maxmemory-policy noeviction --requirepass \\"$$REDIS_PASSWORD\\""]
    ports: ["127.0.0.1:16389:6379"]
    volumes: ["redis-data:/data"]
    healthcheck:
      test: ["CMD-SHELL", "REDISCLI_AUTH=$$REDIS_PASSWORD redis-cli --no-auth-warning ping | grep -qx PONG"]
      interval: 3s
      timeout: 3s
      retries: 30
volumes:
  mysql-data:
    name: {state['mysql_volume']}
    labels: {{credit-risk.v3.owner: "{owner}"}}
  redis-data:
    name: {state['redis_volume']}
    labels: {{credit-risk.v3.owner: "{owner}"}}
networks:
  default:
    name: {state['project']}-network
    labels: {{credit-risk.v3.owner: "{owner}"}}
'''
    (directory/'compose.yaml').write_text(compose,encoding='utf-8')
    url=f"jdbc:mysql://127.0.0.1:13316/{state['schema']}?useSSL=false&serverTimezone=UTC&useLegacyDatetimeCode=false&sessionVariables=time_zone='%2B00:00'&allowPublicKeyRetrieval=true&useLocalSessionState=true&useLocalTransactionState=true&cacheServerConfiguration=true&enableQueryTimeouts=false&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048&maintainTimeStats=false&connectTimeout=5000&socketTimeout=30000"
    config=f'''server:
  address: 127.0.0.1
  port: 18083
  forward-headers-strategy: native
  tomcat:
    remoteip:
      remote-ip-header: x-forwarded-for
      internal-proxies: '127\\.0\\.0\\.1'
spring:
  datasource:
    driver-class-name: com.mysql.jdbc.Driver
    url: "{url}"
    username: v3bench
    password: ${{V3_MYSQL_PASSWORD}}
    initialization-mode: never
    hikari:
      maximum-pool-size: 30
      minimum-idle: 2
  redis:
    host: 127.0.0.1
    port: 16389
    password: ${{V3_REDIS_PASSWORD}}
    database: 0
    timeout: 2s
  autoconfigure:
    exclude: org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration
ticketing:
  sale:
    armory:
      enabled: true
    plan-cache-ms: 500
  inventory:
    bucket-count: 16
    vector-reservations: true
  ingress:
    post-rate: 100000
    query-rate: 2000
  reservations-v2:
    enabled: true
    workers: 16
    legacy-reserve-accepting: false
    attempts:
      enabled: true
      workers: 16
      ordered-lua: true
      redis-ordered-lane: true
      quick-review: true
      batch-register: false
      redis-gate: false
      batch-claim: false
    dispatch:
      isolated: true
      wakeup: true
    mock-payment:
      enabled: false
  reservations-v3:
    enabled: true
    workers: 16
    autostart: true
  pools:
    sale: {{core: 16, max: 16, queue: 0}}
  risk:
    summary:
      enabled: true
  mq:
    enabled: false
  jobs:
    enabled: false
  metrics:
    snapshot:
      enabled: false
  agent:
    url: http://127.0.0.1:18084
logging:
  level:
    root: INFO
'''
    (directory/'application-v3-local.yml').write_text(config,encoding='utf-8')
    return directory


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('action',choices=['new','build','create','initialize','start','prepare','smoke','status','stop'])
    p.add_argument('--run',type=Path);p.add_argument('--execute',action='store_true');args=p.parse_args()
    if not args.execute:raise SystemExit('No action taken. Explicit --execute required.')
    if args.action=='new':
        directory=new_run();print(directory);return
    if not args.run:raise SystemExit('--run required')
    env=Environment(args.run)
    if args.action=='status':env.status();return
    getattr(env,args.action)()
    print(json.dumps({'action':args.action,'ok':True,'run':str(env.path),'memory':free_memory()}))


if __name__=='__main__':main()
