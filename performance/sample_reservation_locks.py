"""Bounded read-only lock diagnostic on an owned fresh benchmark schema."""
import argparse,json,pathlib,re,shlex,subprocess,time
p=argparse.ArgumentParser();p.add_argument('directory',type=pathlib.Path);args=p.parse_args()
row=json.loads((args.directory/'new-valid0.json').read_text(encoding='utf-8'));schema=row['db']
assert re.fullmatch(r'credit_risk_v2_bench_[0-9_]+_new',schema),schema
query="SELECT JSON_OBJECT('at',UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)),'waits',(SELECT JSON_ARRAYAGG(JSON_OBJECT('table',l.OBJECT_NAME,'index',l.INDEX_NAME,'mode',l.LOCK_MODE,'requesting',w.REQUESTING_ENGINE_TRANSACTION_ID,'blocking',w.BLOCKING_ENGINE_TRANSACTION_ID)) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA='"+schema+"'));"
command='for i in $(seq 1 12); do mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B -e '+shlex.quote(query)+'; sleep 1; done'
result=subprocess.run(['docker','exec','credit-risk-dev-mysql','sh','-c',command],capture_output=True,encoding='utf-8',errors='replace',timeout=90)
name='lock-samples-'+str(time.time_ns())
(args.directory/(name+'.jsonl')).write_text(result.stdout,encoding='utf-8')
(args.directory/(name+'.stderr.log')).write_text(result.stderr,encoding='utf-8')
if result.returncode:raise SystemExit('Lock diagnostic failed; raw output retained')
rows=[json.loads(line) for line in result.stdout.splitlines() if line]
print('Samples:',len(rows),'with waits:',sum(bool(r['waits']) for r in rows))
