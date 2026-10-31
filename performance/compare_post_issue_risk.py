"""A/B reserve and fulfillment benchmark against ONLY the isolated credit-risk-ab containers.

Run each label after starting that jar on port 18082. Uses 32 clients, 160 orders/run,
three repetitions of PASS and REVIEW. REVIEW AI replies have a controlled 600 ms delay.
No existing application database or Redis instance is touched.
"""
import concurrent.futures
import json
import math
import pathlib
import statistics
import subprocess
import sys
import threading
import time
import urllib.request
import urllib.error

OUT = pathlib.Path(__file__).resolve().parent / 'results' / 'post-issue-risk'
BASE = 'http://127.0.0.1:18082'
N = 160
CLIENTS = 32

def docker(*args, stdin=None):
    result = subprocess.run(['docker', *args], input=stdin, text=True, capture_output=True, timeout=60)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout.strip()

def sql(statement):
    return docker('exec', '-i', 'credit-risk-ab-mysql', 'mysql', '-uticketing', '-pticketing-local',
                  '-N', '-B', 'ticketing', stdin=statement)

def redis(commands):
    return docker('exec', '-i', 'credit-risk-ab-redis', 'redis-cli', stdin=commands)

def http(path, data=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['authorization'] = token
    request = urllib.request.Request(BASE + path, data=json.dumps(data).encode() if data is not None else None,
                                     headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=40) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()

def prepare(mode):
    # Every destructive operation below targets a fresh, disposable ab container by exact name.
    sql('''DELETE FROM tb_issued_ticket; DELETE FROM tb_payment_transaction;
DELETE FROM tb_wallet_ledger; DELETE FROM tb_manual_review_ticket; DELETE FROM tb_message_consume_record;
DELETE FROM tb_outbox_message; DELETE FROM tb_risk_decision; DELETE FROM tb_risk_profile;
DELETE FROM tb_ticket_order; DELETE FROM tb_reservation; DELETE FROM tb_reserve_request;
DELETE FROM tb_user_purchase; DELETE FROM tb_ticket_sku; DELETE FROM tb_event_session; DELETE FROM tb_event;
DELETE FROM tb_user WHERE id>=90000;
UPDATE tb_risk_rule SET enabled=false WHERE rule_code<>'DECISION_POLICY';
UPDATE tb_risk_rule SET config='{"review":50,"block":100,"agentMaxAdjustment":20,"rateLimit":100000}' WHERE rule_code='DECISION_POLICY';
INSERT INTO tb_event VALUES('ab-event','A/B benchmark','isolated fixture','Test','ON_SALE');
INSERT INTO tb_event_session VALUES('ab-session','ab-event','2027-12-31 19:30:00');
INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,sale_start_time,sale_end_time,purchase_limit,status,version)
VALUES('ab-sku','ab-session','A/B',10,1000,1000,'2020-01-01','2030-12-31',5,'ON_SALE',0);
''')
    if mode == 'REVIEW':
        sql('''UPDATE tb_risk_rule SET enabled=true,weight=60,
config='{"feature":"deviceAccounts","threshold":0,"fast":false}' WHERE rule_code='DEVICE_ACCOUNTS';''')
    users = ','.join(f"({90000+i},'1378{i:07d}','ab-{i}','USER','2025-01-01')" for i in range(N+1))
    sql('INSERT INTO tb_user(id,phone,nick_name,role,create_time) VALUES ' + users + ';')
    redis('FLUSHDB\n' + '\n'.join(f'HSET login:token:ab-token-{i} id {90000+i} role USER nickName ab-{i}' for i in range(N+1)) + '\n')

def reserve(i, prefix):
    begin = time.perf_counter()
    status, body = http('/api/tickets/reserve', dict(requestId=f'ab-{prefix}-{i}', userId=90000+i,
        eventSessionId='ab-session', ticketSkuId='ab-sku', quantity=1, deviceId=f'ab-device-{i}'), f'ab-token-{i}')
    return dict(i=i, status=status, success=isinstance(body, dict) and body.get('success') is True,
                ms=1000*(time.perf_counter()-begin), body=body)

def counts():
    line = sql("""SELECT
(SELECT COUNT(*) FROM tb_ticket_order WHERE user_id>90000 AND status='ISSUED'),
(SELECT COUNT(*) FROM tb_message_consume_record WHERE status='DONE' AND message_key LIKE 'RISK_CHECK:%'),
(SELECT COUNT(*) FROM tb_ticket_order WHERE user_id>90000),
(SELECT COUNT(*) FROM tb_issued_ticket t JOIN tb_ticket_order o ON o.id=t.order_id WHERE o.user_id>90000),
(SELECT remaining_stock FROM tb_ticket_sku WHERE id='ab-sku');""")
    return list(map(int, line.split()))

def wait_drain(expected_risk, deadline=180):
    end = time.monotonic() + deadline
    while time.monotonic()<end:
        c = counts()
        if c[0] == N and c[1] == expected_risk:
            return c
        time.sleep(0.3)
    raise RuntimeError(f'Failed to drain: {counts()}')

def run(label, mode, repeat):
    prepare(mode)
    # Warm the path and initialize the inventory before measured clients begin.
    warm = reserve(0, f'{label}-{mode}-{repeat}-warm')
    assert warm['success'], warm
    for _ in range(180):
        status = sql("SELECT status FROM tb_ticket_order WHERE user_id=90000;")
        if status == 'ISSUED' and (mode == 'PASS' or sql("SELECT COUNT(*) FROM tb_message_consume_record WHERE message_key LIKE 'RISK_CHECK:%' AND status='DONE';") == '1'):
            break
        time.sleep(0.3)
    else:
        raise RuntimeError('Warmup failed to complete')
    # Baseline investigates even PASS; wait for its warm task as well before measuring.
    if label == 'baseline':
        for _ in range(180):
            if sql("SELECT COUNT(*) FROM tb_message_consume_record WHERE message_key LIKE 'RISK_CHECK:%' AND status='DONE';") == '1':
                break
            time.sleep(0.3)
    stop = threading.Event()
    samples = []
    begin = time.perf_counter()
    def monitor():
        while not stop.is_set():
            c = counts()
            samples.append(dict(seconds=time.perf_counter()-begin, issued=c[0], risk_done=c[1]))
            if c[0] == N:
                return
            stop.wait(0.3)
    monitor_thread = threading.Thread(target=monitor, daemon=True)
    monitor_thread.start()
    with concurrent.futures.ThreadPoolExecutor(max_workers=CLIENTS) as pool:
        results = list(pool.map(lambda i: reserve(i, f'{label}-{mode}-{repeat}'), range(1,N+1)))
    request_seconds = time.perf_counter()-begin
    monitor_thread.join(180)
    stop.set()
    assert not monitor_thread.is_alive(), 'Fulfillment did not complete'
    issue_seconds = samples[-1]['seconds']
    expected_risk = N+1 if label == 'baseline' or mode == 'REVIEW' else 0
    final = wait_drain(expected_risk)
    assert all(r['success'] for r in results), [r for r in results if not r['success']][:5]
    assert final == [N, expected_risk, N, N, 1000-N-1], final
    latencies = sorted(r['ms'] for r in results)
    prom = urllib.request.urlopen(BASE+'/actuator/prometheus', timeout=20).read().decode()
    OUT.joinpath(f'{label}-{mode}-{repeat}-prometheus.txt').write_text(prom, encoding='utf-8')
    summary = dict(label=label,mode=mode,repeat=repeat,clients=CLIENTS,requests=N,
                   successful=N,request_seconds=request_seconds,reserve_qps=N/request_seconds,
                   issue_seconds=issue_seconds,issue_qps=N/issue_seconds,
                   reserve_p95_ms=latencies[math.ceil(N*.95)-1],reserve_p99_ms=latencies[math.ceil(N*.99)-1],
                   issued=final[0],risk_done=final[1],remaining_stock=final[4])
    OUT.joinpath(f'{label}-{mode}-{repeat}.json').write_text(json.dumps(dict(summary=summary,requests=results,samples=samples),indent=2),encoding='utf-8')
    print(json.dumps(summary),flush=True)

if __name__ == '__main__':
    label = sys.argv[1]
    assert label in ('baseline','after')
    OUT.mkdir(parents=True, exist_ok=True)
    for _ in range(180):
        try:
            if http('/actuator/health')[0] == 200:
                break
        except Exception:
            pass
        time.sleep(1)
    else:
        raise RuntimeError('Application did not become healthy')
    for mode in ('PASS','REVIEW'):
        for repeat in range(1,4):
            run(label,mode,repeat)
