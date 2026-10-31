"""Explicit future load runner. Never called by provisioning; no reset/cleanup SQL exists."""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import subprocess
import os
import threading
import time
import uuid
import urllib.request
from v3_local_env import Environment, ROOT, free_memory, save


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run',type=Path,required=True);p.add_argument('--mode',choices=['ingress','held'],required=True)
    p.add_argument('--vus',type=int,default=64);p.add_argument('--rate',type=int,default=5000)
    p.add_argument('--execute',action='store_true');a=p.parse_args()
    if not a.execute:raise SystemExit('No load started: explicit --execute is required')
    if not 1<=a.vus<=512 or not 1<=a.rate<=10000:raise SystemExit('Bounded VU/rate required')
    env=Environment(a.run);state=env.state
    if not state.get('verification_passed'):raise RuntimeError('Full owned HELD/auth/inventory verification must pass first')
    env.assert_container('mysql');env.assert_container('redis')
    if env.sql('SELECT owner FROM tb_v3_bench_owner;')!=env.owner:raise RuntimeError('Database ownership mismatch')
    with env.redis() as r:
        r.owned();buyers=json.loads((env.path/'fixtures.json').read_text())['requests']
        for first in range(0,len(buyers),200):
            batch=buyers[first:first+200];commands=[]
            for user in batch:commands += [('HMGET','login:token:'+user['token'],'id','role'),('TTL','login:token:'+user['token'])]
            r.socket.sendall(b''.join(r.encode(c) for c in commands))
            for user in batch:
                identity=r.read();ttl=r.read()
                if identity!=[str(user['userId']),'USER'] or ttl<120:raise RuntimeError('Formal login identity expired/near expiry; no load started')
    if free_memory()['available']<1024**3:raise RuntimeError('Need >=1 GiB free Windows memory before formal load; no unrelated process will be stopped')
    session=state['sessions']['formal']
    if env.sql(f"SELECT COUNT(*) FROM tb_reservation_job WHERE session_id='{session}';")!='0':raise RuntimeError('Formal arena already used; never reset it. Prepare another fresh owned arena/run.')
    stock=env.sql(f"SELECT SUM(b.remaining_stock) FROM tb_ticket_stock_bucket b JOIN tb_ticket_sku s ON s.id=b.sku_id WHERE s.event_session_id='{session}';")
    if stock!='5000':raise RuntimeError('Formal stock must still be exactly 5000')
    if not state['sale_start_epoch_ms']<=time.time()*1000<state['sale_end_epoch_ms']:raise RuntimeError('Not in the existing Armory sale window; never edit an assembled plan in place')
    out=env.path/'benchmarks'/uuid.uuid4().hex;out.mkdir(parents=True)
    session,sale_open,fixture_path=env.load_arena(out)
    k6=ROOT/'.tools/k6-v2.3.0-windows-amd64/k6.exe';script=ROOT/'performance/k6/v3_local.js'
    child=os.environ.copy();child.update(V3_EXECUTE='owned-local-environment',V3_MODE=a.mode,V3_SECONDS='30',
       V3_VUS=str(a.vus),V3_RATE=str(a.rate),V3_FIXTURES=str(fixture_path),V3_OWNER=env.owner,V3_SALE_OPEN=str(sale_open),
       V3_RUN='load-'+uuid.uuid4().hex[:12],V3_SUMMARY=str(out/'k6-summary.json'))
    save(out/'configuration.json',dict(mode=a.mode,vus=a.vus,rate=a.rate if a.mode=='ingress' else None,seconds=30,owner=env.owner,
         jar_sha256=state['jar_sha256'],script_sha256=hashlib.sha256(script.read_bytes()).hexdigest(),
         fixture_sha256=hashlib.sha256(fixture_path.read_bytes()).hexdigest(),buyer_shuffle_seed=42,
         sale_open_epoch_ms=sale_open,session=session,created_at=dt.datetime.now().astimezone().isoformat(),
         clock_note='New arena is assembled before opening. k6 setup aligns to its opening; load-to-HELD and sale-open-to-HELD are both reported.'))
    def metrics(name):
        with urllib.request.urlopen('http://127.0.0.1:18083/actuator/prometheus',timeout=5) as response:
            (out/name).write_bytes(response.read())
    metrics('metrics-before.prom')
    samples=[];stop=threading.Event();monitor_failed=threading.Event()
    def sample():
        while not stop.is_set():
            try:
                start=time.time()*1000
                rows=env.sql(f"SELECT state,COUNT(*) FROM tb_reservation_job WHERE session_id='{session}' GROUP BY state;",timeout=5)
                held=env.sql(f"SELECT COUNT(*) FROM tb_reservation r JOIN tb_ticket_sku s ON s.id=r.sku_id WHERE s.event_session_id='{session}' AND r.status='HELD';",timeout=5)
                samples.append(dict(epoch_ms=start,end_epoch_ms=time.time()*1000,states=rows,held=int(held),memory=free_memory()))
            except Exception as e:
                samples.append(dict(error=type(e).__name__,epoch_ms=time.time()*1000));monitor_failed.set();return
            stop.wait(1)
    thread=threading.Thread(target=sample,daemon=True);thread.start()
    proc=None
    try:
        with (out/'k6.stdout.log').open('wb') as stdout,(out/'k6.stderr.log').open('wb') as stderr:
            proc=subprocess.Popen([str(k6),'run','--quiet',str(script)],env=child,stdout=stdout,stderr=stderr,creationflags=subprocess.CREATE_NO_WINDOW)
            deadline=time.monotonic()+max(0,sale_open/1000-time.time())+60
            while proc.poll() is None:
                if time.monotonic()>deadline or free_memory()['available']<384*1024**2 or monitor_failed.is_set():
                    proc.terminate();proc.wait(10);raise RuntimeError('Owned k6 stopped: watchdog or low memory')
                time.sleep(.5)
            if proc.returncode:raise RuntimeError('k6 failed; inspect logs')
        # Bounded tail is reported separately, never counted as success inside the 30s target.
        time.sleep(3)
        code,prom=env.http('GET','/actuator/health')
        save(out/'app-health-after.json',dict(status=code,response=prom))
        metrics('metrics-after.prom')
    finally:
        if proc and proc.poll() is None:proc.terminate();proc.wait(10)
        stop.set();thread.join(30);save(out/'sql-timeline.json',samples)
        print(out)
    # Read-only final invariants. All records retained; no DROP/FLUSH/TRUNCATE or automatic rerun.
    save(out/'invariants.json',dict(
      sql_stock=env.sql(f"SELECT b.sku_id,SUM(b.remaining_stock) FROM tb_ticket_stock_bucket b JOIN tb_ticket_sku s ON s.id=b.sku_id WHERE s.event_session_id='{session}' GROUP BY b.sku_id;"),
      held=env.sql(f"SELECT COUNT(*),COUNT(DISTINCT r.user_id) FROM tb_reservation r JOIN tb_ticket_sku s ON s.id=r.sku_id WHERE s.event_session_id='{session}' AND r.status='HELD';"),
      states=env.sql(f"SELECT state,COUNT(*) FROM tb_reservation_job WHERE session_id='{session}' GROUP BY state;"),
      note='Retain full raw metrics. Do not call 202 or rejected HTTP throughput HELD TPS. No automatic second round.'))
    data=json.loads((out/'k6-summary.json').read_text())
    start=data['k6']['metrics'].get('v3_post_start_epoch_ms',{}).get('values',{}).get('min')
    if start:
        within=[s['held'] for s in samples if 'held' in s and s['end_epoch_ms']<=start+30000]
        within_open=[s['held'] for s in samples if 'held' in s and s['end_epoch_ms']<=sale_open+30000]
        complete=[s for s in samples if s.get('held')==5000]
        save(out/'held-acceptance.json',dict(first_actual_post_epoch_ms=start,
           held_at_last_sample_not_after_30s=max(within,default=0),
           first_all_held_observed_ms=None if not complete else complete[0]['end_epoch_ms']-start,
           sale_open_to_all_held_ms=None if not complete else complete[0]['end_epoch_ms']-sale_open,
           goal_proven_load_to_held=max(within,default=0)==5000,goal_proven_open_to_held=max(within_open,default=0)==5000,sampling_interval_seconds=1,
           note='SQL observation is a conservative COMMIT visibility bound, not exact commit instrumentation. Query/polling cost is included.'))


if __name__=='__main__':main()
