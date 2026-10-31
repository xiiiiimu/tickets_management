"""Owned environment verification: one extra PASS order, auth negatives, read-only invariants. Not load."""
import argparse
import hashlib
import json
from pathlib import Path
import time
import urllib.request
from v3_local_env import Environment, ROOT, free_memory, save


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',type=Path,required=True);p.add_argument('--execute',action='store_true');a=p.parse_args()
    if not a.execute:raise SystemExit('Explicit --execute required for the additional smoke order')
    env=Environment(a.run)
    if not env.state.get('smoke_passed'):raise RuntimeError('Initial three REVIEW orders must pass first')
    if (env.path/'verification.json').exists():raise RuntimeError('Verification already recorded; do not overwrite or repeat')
    fixture=json.loads((env.path/'smoke-fixtures.json').read_text())[3]
    assert fixture['profile']=='PASS'
    rid='v3-smoke-pass-'+env.state['id']
    request={k:fixture[k] for k in ('userId','eventSessionId','ticketSkuId','quantity','deviceId')};request['requestId']=rid
    unauthorized,_=env.http('POST','/api/tickets/reservations',body=request)
    wrong=dict(request,userId=fixture['userId']+1)
    mismatched,_=env.http('POST','/api/tickets/reservations',fixture['token'],wrong)
    if unauthorized!=401 or mismatched!=403:raise RuntimeError('Real authentication/identity negative checks failed')
    code,receipt=env.http('POST','/api/tickets/reservations',fixture['token'],request,headers={'X-Forwarded-For':fixture['ip']})
    if code!=202 or receipt.get('data',{}).get('receiptSemantics')!='V3_PROCESSING':raise RuntimeError('PASS request not accepted by V3')
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        state=env.sql(f"SELECT state FROM tb_reservation_job WHERE request_id='{rid}';")
        if state=='RESERVED':break
        if state in ('FAILED','REJECTED','QUEUE_TIMEOUT'):raise RuntimeError('PASS smoke failed: '+state)
        time.sleep(.25)
    else:raise RuntimeError('PASS smoke deadline')
    duplicate,_=env.http('POST','/api/tickets/reservations',fixture['token'],request,headers={'X-Forwarded-For':fixture['ip']})
    if duplicate!=202:raise RuntimeError('PASS duplicate failed')
    formal=env.state['sessions']['formal'];smoke=env.state['sessions']['smoke'];schema=env.state['schema']
    held=env.sql(f"SELECT COUNT(*),COUNT(DISTINCT r.user_id) FROM tb_reservation r JOIN tb_ticket_sku s ON s.id=r.sku_id WHERE s.event_session_id='{smoke}' AND r.status='HELD';")
    if held!='4\t4':raise RuntimeError('Expected exactly four distinct smoke HELD buyers')
    states=env.sql('SELECT state,decision,attempt_protocol,COUNT(*) FROM tb_reservation_job GROUP BY state,decision,attempt_protocol;')
    stock_rows=env.sql(f"SELECT b.sku_id,COUNT(*),SUM(b.total_stock),SUM(b.remaining_stock),MIN(b.remaining_stock) FROM tb_ticket_stock_bucket b JOIN tb_ticket_sku s ON s.id=b.sku_id WHERE s.event_session_id='{formal}' GROUP BY b.sku_id ORDER BY b.sku_id;")
    expected=[]
    for line in stock_rows.splitlines():
        sku,buckets,total,remaining,minimum=line.split('\t')
        if (int(buckets),int(total),int(remaining))!=(16,1000,1000) or int(minimum)<0:raise RuntimeError('Formal bucket invariant failed')
        expected.append(sku)
    if len(expected)!=5:raise RuntimeError('Need five formal SKUs')
    users=env.sql("SELECT COUNT(*) FROM tb_user WHERE id>=3000000 AND id<3005000 AND role='USER';")
    profiles=env.sql("SELECT decision,COUNT(*) FROM tb_risk_profile WHERE subject_type='USER' AND CAST(subject_value AS UNSIGNED)>=3000000 AND CAST(subject_value AS UNSIGNED)<3005000 AND expires_at>UTC_TIMESTAMP() GROUP BY decision ORDER BY decision;")
    if users!='5000' or profiles!='PASS\t3500\nREVIEW\t1500':raise RuntimeError('Formal buyer/profile mismatch')
    fixtures=json.loads((env.path/'fixtures.json').read_text())['requests']
    with env.redis() as r:
        r.owned()
        # Bounded read-only RESP batches; authenticate every identity without bypassing HTTP auth.
        for start in range(0,5000,200):
            batch=fixtures[start:start+200]
            commands=[]
            for user in batch:commands += [('HMGET','login:token:'+user['token'],'id','role'),('TTL','login:token:'+user['token'])]
            r.socket.sendall(b''.join(r.encode(c) for c in commands))
            for user in batch:
                identity=r.read();ttl=r.read()
                if identity!=[str(user['userId']),'USER'] or ttl<=0:raise RuntimeError('Formal login session invalid')
        stocks={sku:sum(int(r.call('GET','ticket:{'+sku+'}:stock:bucket:'+str(i))) for i in range(16)) for sku in expected}
        if set(stocks.values())!={1000}:raise RuntimeError('Formal Redis stock mismatch')
        smoke_stock=sum(int(r.call('GET','ticket:{'+smoke+'-0}:stock:bucket:'+str(i))) for i in range(16))
        if smoke_stock!=16:raise RuntimeError('Smoke Redis stock mismatch')
        ack_deadline=time.monotonic()+5
        while r.call('XLEN','{tkv3}:stream') and time.monotonic()<ack_deadline:time.sleep(.1)
        groups=r.call('XINFO','GROUPS','{tkv3}:stream')
        group_records=[dict(zip(g[::2],g[1::2])) for g in groups]
        if r.call('XLEN','{tkv3}:stream')!=0 or any(g.get('pending')!=0 for g in group_records):raise RuntimeError('Stream did not settle')
        if r.call('HGET','{tkv3}:cfg','mode')!='OPEN':raise RuntimeError('V3 gate not OPEN')
        for row in env.sql(f"SELECT reservation_id FROM tb_reservation_job WHERE session_id='{smoke}';").splitlines():
            marker=r.call('HGETALL','ticket:{'+smoke+'-0}:reservation:'+row);marker=dict(zip(marker[::2],marker[1::2]))
            if marker.get('state')!='HELD' or int(marker['expiresAt'])-int(marker['heldAt'])!=600000:raise RuntimeError('10-minute held marker protocol failed')
    if env.sql(f"SELECT SUM(b.remaining_stock) FROM tb_ticket_stock_bucket b JOIN tb_ticket_sku s ON s.id=b.sku_id WHERE s.event_session_id='{smoke}';")!='16':raise RuntimeError('Smoke MySQL stock mismatch')
    if env.sql("SELECT COUNT(*) FROM tb_reserve_request WHERE status='REQUESTED';")!='0':raise RuntimeError('Requested recovery intent remains')
    outbox=env.sql('SELECT COUNT(*) FROM tb_outbox_message;')
    if outbox!='4':raise RuntimeError('Outbox must have four real committed records')
    leases=env.sql("SELECT COUNT(*) FROM tb_reservation_job WHERE state IN ('WAITING','PROCESSING','CLAIMED') OR lease_token IS NOT NULL;")
    if leases!='0':raise RuntimeError('Live task or lease remains')
    baseline=json.loads((env.path/'existing-container-baseline.json').read_text())
    for old in baseline:
        c=json.loads(env.docker('inspect',old['id']))[0]
        mounts=[{k:m.get(k) for k in ('Type','Name','Source','Destination')} for m in c['Mounts']]
        if c['Id']!=old['id'] or c['State']['StartedAt']!=old['started'] or mounts!=old['mounts']:raise RuntimeError('Existing container changed')
    health=env.http('GET','/actuator/health');mode=env.http('GET','/api/tickets/reservation-mode')
    if health[0]!=200 or mode[1]['data']['receiptSemantics']!='V3_PROCESSING':raise RuntimeError('Runtime V3 health/mode mismatch')
    with urllib.request.urlopen('http://127.0.0.1:18083/actuator/prometheus',timeout=10) as response:
        (env.path/'metrics-after-smoke.prom').write_bytes(response.read())
    sql_indexes=env.sql("SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('tb_reservation_job','tb_stock_bucket_allocation_item','tb_reserve_request') GROUP BY TABLE_NAME,INDEX_NAME,NON_UNIQUE ORDER BY TABLE_NAME,INDEX_NAME;")
    metadata=dict(health=health,mode=mode,extra_pass_http=code,duplicate_http=duplicate,unauthorized_http=unauthorized,identity_mismatch_http=mismatched,
       smoke_held_and_distinct_buyers=held,states=states,formal_users=int(users),formal_profiles=profiles,formal_tokens_valid=5000,
       formal_sku_stock_rows=stock_rows,formal_redis_stock=stocks,formal_tickets=5000,formal_orders=int(env.sql(f"SELECT COUNT(*) FROM tb_reservation_job WHERE session_id='{formal}';")),
       smoke_stock=16,outbox_committed=4,requested_intents=0,leases=0,stream_groups=group_records,stream_length=0,
       existing_containers_unchanged=len(baseline),memory=free_memory(),indexes=sql_indexes,
       persistence=env.sql('SELECT @@version,@@innodb_flush_log_at_trx_commit,@@sync_binlog;'),
       protocol=env.sql('SELECT runtime_protocol,capacity,active_limit,ordered_lua_enabled,redis_lane_enabled,quick_review_enabled,vector_inventory_enabled FROM tb_reservation_dispatch WHERE id=1;'),
       actual_formal_sale_open_ms=int(env.sql(f"SELECT CAST(UNIX_TIMESTAMP(MIN(sale_start_time))*1000 AS UNSIGNED) FROM tb_ticket_sku WHERE event_session_id='{formal}';")))
    save(env.path/'verification.json',metadata);env.state['verification_passed']=True;env.persist()
    print(json.dumps({'verification':'PASS','formal_buyers':5000,'formal_stock':5000,'real_held_orders':4,'old_containers_unchanged':len(baseline),'memory':metadata['memory']}))


if __name__=='__main__':main()
