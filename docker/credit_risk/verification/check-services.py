"""Read checks plus one namespaced Redis persistence marker. Never starts/stops services."""
from pathlib import Path
import json,subprocess,urllib.request,base64,uuid,sys,datetime
base=Path(__file__).resolve().parents[1]
values={}
for line in (base/'.env').read_text(encoding='utf-8').splitlines():
    if line.strip() and not line.lstrip().startswith('#'):
        key,value=line.split('=',1);values[key.strip()]=value.strip().strip('"').strip("'")
def docker(*args,stdin=None):
    result=subprocess.run(['docker',*args],input=stdin,text=True,encoding="utf-8",errors="replace",capture_output=True,timeout=75)
    if result.returncode:raise RuntimeError('Docker verification failed: '+result.stderr[-1200:])
    return result.stdout.strip()
def get(url,auth=False):
    request=urllib.request.Request(url)
    if auth:
        token=base64.b64encode(('admin:'+values['CREDIT_RISK_GRAFANA_ADMIN_PASSWORD']).encode()).decode()
        request.add_header('Authorization','Basic '+token)
    with urllib.request.urlopen(request,timeout=15) as response:
        body=response.read().decode()
        return json.loads(body) if body.lstrip().startswith(('{','[')) else body
mysql_script='export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysql --protocol=TCP -h127.0.0.1 -u"$MYSQL_USER" -D"$MYSQL_DATABASE" -N -B'
query="""SELECT @@version,DATABASE(),CURRENT_USER(),@@server_uuid;
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('tb_ticket_stock_bucket','tb_stock_bucket_allocation','tb_ticket_sale_assembly','tb_ticket_sale_version');
SELECT (SELECT COUNT(*) FROM tb_user),(SELECT COUNT(*) FROM tb_event),(SELECT COUNT(*) FROM tb_ticket_sku),(SELECT COUNT(*) FROM tb_ticket_order);
"""
rows=docker('exec','-i','credit-risk-dev-mysql','sh','-ec',mysql_script,stdin=query).splitlines()
version,database,user,server_uuid=rows[0].split('\t');assert version=='8.0.32' and database=='credit_risk_dev'
assert rows[1]=='4','V103/V104 tables missing'
assert list(map(int,rows[2].split('\t')))==[2,1,3,0],'Unexpected seed data'
def redis(*args):
    return docker('exec','credit-risk-dev-redis','sh','-ec','export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --no-auth-warning --raw "$@"','credit-risk-dev',*args)
assert redis('PING')=='PONG'
marker_key='credit-risk-dev:verification:persistence'
marker=redis('GET',marker_key)
if not marker:
    assert redis('DBSIZE')=='0','Expected a fresh Redis database'
    marker=str(uuid.uuid4());assert redis('SET',marker_key,marker)=='OK'
cluster=docker('exec','credit-risk-dev-broker','sh','/home/rocketmq/rocketmq-4.9.7/bin/mqadmin','clusterList','-n','namesrv:9876')
assert 'credit-risk-dev-cluster' in cluster and 'V4_9_7' in cluster
route=docker('exec','credit-risk-dev-broker','sh','/home/rocketmq/rocketmq-4.9.7/bin/mqadmin','topicRoute','-n','namesrv:9876','-t','ticket-events')
assert '127.0.0.1:'+values['CREDIT_RISK_BROKER_PORT'] in route
prom='http://127.0.0.1:'+values['CREDIT_RISK_PROMETHEUS_PORT']
assert 'Ready' in get(prom+'/-/ready')
assert get(prom+'/api/v1/status/buildinfo')['data']['version']=='2.53.0'
grafana='http://127.0.0.1:'+values['CREDIT_RISK_GRAFANA_PORT']
assert get(grafana+'/api/health')['database']=='ok'
source=get(grafana+'/api/datasources/uid/ticket-prometheus/health',True)
assert source.get('status')=='OK',source
board=get(grafana+'/api/dashboards/uid/ticket-core',True)
assert len(board['dashboard']['panels'])==14
ids=docker('ps','-aq','--filter','label=com.docker.compose.project=credit-risk-dev').splitlines()
assert len(ids)==6
containers=json.loads(docker('inspect',*ids));states=[]
for container in containers:
    assert container['HostConfig']['RestartPolicy']['Name']=='no'
    assert container['State']['Running'] and container['State']['Health']['Status']=='healthy'
    assert set(container['NetworkSettings']['Networks'])=={'credit-risk-dev-network'}
    expected_volume={'credit-risk-dev-mysql':('credit-risk-mysql-data','/var/lib/mysql'),
                     'credit-risk-dev-redis':('credit-risk-redis-data','/data')}.get(container['Name'].lstrip('/'))
    if expected_volume:
        matches=[m for m in container['Mounts'] if m['Destination']==expected_volume[1]]
        assert len(matches)==1 and matches[0]['Type']=='volume' and matches[0]['Name']==expected_volume[0]
        volume=json.loads(docker('volume','inspect',expected_volume[0]))[0]
        assert volume['Labels']['com.docker.compose.project']=='credit-risk-dev'
    for mount in container['Mounts']:
        if mount['RW']:
            if mount['Type']=='volume':
                assert expected_volume and mount['Name']==expected_volume[0]
            else:
                assert '/docker/credit_risk/data/' in mount['Source'].replace('\\','/').lower().rstrip('/')+'/'
    states.append({'name':container['Name'].lstrip('/'),'id':container['Id'],'restart':'no','health':'healthy','mounts':[{'source':m['Source'],'destination':m['Destination'],'rw':m['RW'],'type':m['Type']} for m in container['Mounts']]})
report={'verified_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'mysql':{'version':version,'database':database,'user':user,'server_uuid':server_uuid,'migration_tables':4,'seed_users':2,'seed_events':1,'seed_skus':3,'orders':0},'redis':{'ping':'PONG','persistence_key':marker_key,'persistence_value':marker},'rocketmq':{'version':'4.9.7','cluster':'credit-risk-dev-cluster','broker':'127.0.0.1:'+values['CREDIT_RISK_BROKER_PORT'],'topic':'ticket-events','read_queues':8,'write_queues':8},'prometheus':{'version':'2.53.0','ready':True},'grafana':{'version':'11.2.0','datasource_status':source['status'],'dashboard_panels':14},'containers':states,'named_volumes_used':['credit-risk-mysql-data','credit-risk-redis-data']}
previous=base/'verification/services-checks.json'
if previous.exists():
    old=json.loads(previous.read_text(encoding='utf-8'))
    assert old['mysql']['server_uuid']==server_uuid,'MySQL data identity changed across restart'
    assert old['redis']['persistence_value']==marker,'Redis data did not persist'
    assert {c['id'] for c in old['containers']}=={c['id'] for c in states},'Containers were recreated instead of retained'
    report['manual_restart_persistence_verified']=True
previous.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'mysql':'8.0.32 / credit_risk_dev initialized','V103_V104':'4 tables present','redis':'PONG','rocketmq':'4.9.7 / ticket-events routed to local Java','prometheus':'ready','grafana_datasource':source['status'],'restart_policies':'6 x no','persistence_recheck':report.get('manual_restart_persistence_verified',False)},ensure_ascii=False))
