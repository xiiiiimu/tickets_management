"""Fault injection after measured ABC runs, only in the disposable credit-risk-ab fixture."""
import json
import pathlib
import time
from compare_inventory_buckets import setup,catalog,validate_c,OUT,SKUS
from compare_sku_contention import request
from compare_post_issue_risk import docker,sql

setup('C');catalog('C','compensation')
before=validate_c(1000,0)
# Redis admits the request; a deliberately inconsistent SQL quota forces the reserve TX to roll back.
sql("UPDATE tb_user_purchase SET quantity=100 WHERE user_id=90000 AND sku_id='SKU-001';")
failed=request(0,SKUS[0],'abc-compensation')
assert failed['status']==409 and not failed['success'],failed
rid='sku-ab-abc-compensation-0'
reservation=sql(f"SELECT reservation_id FROM tb_reserve_request WHERE request_id='{rid}';")
marker=f'ticket:{{SKU-001}}:reservation:{reservation}'
stock_key=docker('exec','credit-risk-ab-redis','redis-cli','--raw','HGET',marker,'stockKey')
assert stock_key.startswith('ticket:{SKU-001}:stock:bucket:'),stock_key
assert docker('exec','credit-risk-ab-redis','redis-cli','--raw','HGET',marker,'state')=='HELD'
assert sql(f"SELECT COUNT(*) FROM tb_stock_bucket_allocation WHERE reservation_id='{reservation}';")=='0'
assert sql("SELECT SUM(remaining_stock) FROM tb_ticket_stock_bucket WHERE sku_id='SKU-001';")=='999'
keys=[f'ticket:{{SKU-001}}:stock:bucket:{i}' for i in range(16)]
held=sum(map(int,docker('exec','credit-risk-ab-redis','redis-cli','--raw','MGET',*keys).splitlines()))
assert held==998,held
sql("UPDATE tb_user_purchase SET quantity=1 WHERE user_id=90000 AND sku_id='SKU-001';")
sql(f"UPDATE tb_reserve_request SET created_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 130 SECOND) WHERE request_id='{rid}';")
end=time.monotonic()+45
while time.monotonic()<end:
    if sql(f"SELECT status FROM tb_reserve_request WHERE request_id='{rid}';")=='FAILED':break
    time.sleep(.5)
else:raise RuntimeError('Scheduled recovery did not complete')
assert docker('exec','credit-risk-ab-redis','redis-cli','--raw','HGET',marker,'state')=='RELEASED'
script=(pathlib.Path(__file__).resolve().parents[1]/'ticket-risk-platform/src/main/resources/lua/release_ticket.lua').read_text(encoding='utf-8')
duplicate=docker('exec','credit-risk-ab-redis','redis-cli','--raw','EVAL',script,'4',stock_key,
                 'ticket:{SKU-001}:user:90000',marker,'ticket:{SKU-001}:pending',reservation)
assert duplicate=='0',duplicate
after=validate_c(1000,0)
assert after['remaining']==before['remaining']==999
assert sql("SELECT COUNT(*) FROM tb_ticket_order;")=='16'
OUT.joinpath('compensation.json').write_text(json.dumps(dict(failed=failed,selected_stock_key=stock_key,
    sql_remaining_after_rollback=999,redis_remaining_while_held=held,before=before,after=after,
    recovery_state='FAILED',redis_state='RELEASED',duplicate_release_result=0),indent=2),encoding='utf-8')
print('COMPENSATION: rollback SQL intact; Redis HELD recovered to 999; duplicate release adds zero; no failed order created')
