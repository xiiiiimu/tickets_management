"""Read-only file-wait snapshots for supplementary deferred diagnostic windows."""
import json
import pathlib
import subprocess
import sys
import time
root=pathlib.Path(sys.argv[1])
def snapshot():
    query="SELECT JSON_OBJECT('event',EVENT_NAME,'count',COUNT_STAR,'ms',SUM_TIMER_WAIT/1000000000) FROM performance_schema.events_waits_summary_global_by_event_name WHERE EVENT_NAME LIKE 'wait/io/file/innodb/%' OR EVENT_NAME LIKE 'wait/io/file/sql/%';"
    p=subprocess.run(['docker','exec','-i','credit-risk-dev-mysql','sh','-c','exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B'],input=query,encoding='utf-8',errors='replace',capture_output=True,timeout=30)
    if p.returncode:raise RuntimeError(p.stderr)
    return dict(time=time.time(),events=[json.loads(s) for s in p.stdout.splitlines() if s])
for label in ('A-deferred','B-deferred'):
    deadline=time.monotonic()+7200
    while not (root/(label+'-warm2.json')).exists():
        if time.monotonic()>deadline:raise RuntimeError('Observer deadline exceeded')
        time.sleep(2)
    (root/(label+'-io-before.json')).write_text(json.dumps(snapshot(),indent=2),encoding='utf-8')
    while not (root/(label+'-requests.json')).exists():time.sleep(.5)
    (root/(label+'-io-after.json')).write_text(json.dumps(snapshot(),indent=2),encoding='utf-8')
    print('IO_CAPTURED',label,flush=True)
