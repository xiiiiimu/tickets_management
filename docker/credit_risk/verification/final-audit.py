"""Read-only final audit. Does not start, stop or change Docker resources."""
from pathlib import Path
import json,subprocess,hashlib,datetime
base=Path(__file__).resolve().parents[1];root=base.parents[1]
def docker(*args):
    result=subprocess.run(['docker',*args],capture_output=True,text=True,encoding="utf-8",errors="replace",timeout=30)
    if result.returncode:raise RuntimeError(result.stderr)
    return result.stdout.strip()
before=json.loads((base/'verification/before-resources.json').read_text(encoding='utf-8-sig'))
ids=docker('ps','-aq').splitlines();all_containers=json.loads(docker('inspect',*ids)) if ids else []
by_id={c['Id']:c for c in all_containers}
for old in before['containers']:
    current=by_id[old['Id']]
    assert current['Name']==old['Name'] and current['State']['Status']==old['Status']
    assert current['State']['StartedAt']==old['StartedAt'] and current['State']['FinishedAt']==old['FinishedAt']
    assert current['HostConfig']['RestartPolicy']['Name']==old['RestartPolicy']
    def mounts(rows):return sorted((m['Type'],m['Source'],m['Destination'],m['RW']) for m in rows)
    assert mounts(current['Mounts'])==mounts(old['Mounts'])
    assert set(current['NetworkSettings']['Networks'])==set(old['Networks'])
ours=[c for c in all_containers if c['Config']['Labels'].get('com.docker.compose.project')=='credit-risk-dev']
assert len(ours)==6
assert all(c['State']['Status']=='exited' and not c['State']['Running'] for c in ours)
assert all(c['HostConfig']['RestartPolicy']['Name']=='no' for c in ours)
assert all(c['State']['ExitCode'] in (0,143) for c in ours),'Unexpected stop exit code'
new_ids=set(by_id)-{c['Id'] for c in before['containers']}
assert new_ids=={c['Id'] for c in ours}
networks=set(docker('network','ls','--format','{{.Name}}').splitlines())
volumes=set(docker('volume','ls','--format','{{.Name}}').splitlines())
assert networks==set(before['networks'])|{'credit-risk-dev-network'}
expected_named={'credit-risk-mysql-data','credit-risk-redis-data'}
assert volumes==set(before['volumes'])|expected_named
for name in expected_named:
    metadata=json.loads(docker('volume','inspect',name))[0]
    assert metadata['Labels']['com.docker.compose.project']=='credit-risk-dev'
for service,destination,name in [('mysql','/var/lib/mysql','credit-risk-mysql-data'),('redis','/data','credit-risk-redis-data')]:
    container=next(c for c in ours if c['Name']=='/credit-risk-dev-'+service)
    mounts=[m for m in container['Mounts'] if m['Destination']==destination]
    assert len(mounts)==1 and mounts[0]['Type']=='volume' and mounts[0]['Name']==name
hashes=json.loads((base/'verification/business-files-before.json').read_text(encoding='utf-8'))
for name,expected in hashes.items():assert hashlib.sha256((root/name).read_bytes()).hexdigest()==expected,name
assert subprocess.run(['git','diff','--cached','--binary'],cwd=root,capture_output=True,check=True).stdout==(base/'verification/original-index.patch').read_bytes()
assert subprocess.run(['git','check-ignore','--quiet','docker/credit_risk/.env'],cwd=root).returncode==0
# Old bind data remains ignored for rollback; inspect storage through Docker volumes, not auto.cnf.
assert subprocess.run(['git','check-ignore','--quiet','docker/credit_risk/data/'],cwd=root).returncode==0
assert subprocess.run(['git','check-ignore','--quiet','docker/credit_risk/backup/'],cwd=root).returncode==0
service=json.loads((base/'verification/services-checks.json').read_text(encoding='utf-8'))
assert service['manual_restart_persistence_verified']
java=json.loads((base/'verification/java-metrics-check.json').read_text(encoding='utf-8-sig'))
assert java['prometheusUp']==1 and java['shutdown']=='stopped-own-probe'
secrets=[]
for line in (base/'.env').read_text(encoding='utf-8').splitlines():
    if '=' in line and ('PASSWORD' in line.split('=',1)[0] or 'API_KEY' in line.split('=',1)[0]):secrets.append(line.split('=',1)[1])
for file in base.rglob('*'):
    if {'data','backup'} & set(file.relative_to(base).parts) or file.name=='.env' or file.suffix=='.log':continue
    if not file.is_file():continue
    text=file.read_text(encoding='utf-8-sig',errors='replace')
    assert not any(secret in text for secret in secrets),'Secret leaked outside private .env: '+str(file)
report={'verified_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'project':'credit-risk-dev','directory':str(base),
 'containers':[{'name':c['Name'].lstrip('/'),'id':c['Id'],'state':c['State']['Status'],'exit_code':c['State']['ExitCode'],'restart_policy':c['HostConfig']['RestartPolicy']['Name']} for c in ours],
 'network':'credit-risk-dev-network','new_named_volumes':sorted(expected_named-set(before['volumes'])),'new_anonymous_volumes':[],
 'other_containers_unchanged':len(before['containers']),'other_networks_unchanged':True,'other_volumes_unchanged':True,
 'business_files_unchanged':len(hashes),'original_git_index_unchanged':True,'secrets_git_ignored':True,
 'mysql_persistence_verified':True,'redis_persistence_verified':True,'container_ids_preserved':True,
 'java_metrics_verified':True,'java_probe_stopped':True,'automatic_startup_configured':False,
 'ports':{'mysql':13306,'redis':16379,'namesrv':19876,'broker':20911,'broker_vip':20909,'prometheus':19090,'grafana':13000,'local_java':18082}}
(base/'verification/final-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
(base/'verification/docker-ps-final.txt').write_text(docker('ps')+'\n',encoding='utf-8')
(base/'verification/docker-ps-a-final.txt').write_text(docker('ps','-a')+'\n',encoding='utf-8')
print(json.dumps({'own_containers':'6 x Exited (4 x code 0, RocketMQ 2 x SIGTERM 143)','restart_policy':'6 x no','other_containers_unchanged':len(before['containers']),'new_networks':1,'new_volumes':len(expected_named-set(before['volumes'])),'persistent_data_preserved':True,'business_files_unchanged':len(hashes),'java_metrics':'verified; temporary Java stopped'},ensure_ascii=False))
