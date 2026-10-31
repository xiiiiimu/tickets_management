from pathlib import Path
from xml.sax.saxutils import escape
root=Path(__file__).parent
def put(path,s):
 p=root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s.strip()+'\n',encoding='utf-8')
cases=[('01_normal_reserve',10,'reserve',False),('02_flash_sale_10000_users',10000,'reserve',False),('03_duplicate_request',100,'reserve',True),('04_user_purchase_limit',100,'reserve',False),('05_concurrent_payment',100,'pay',True),('06_risk_attack_traffic',200,'reserve',False)]
for name,threads,action,duplicate in cases:
 request='${__P(requestId,duplicate-test)}' if duplicate else '${__UUID()}'
 if action=='pay':body='{"paymentRequestId":"'+request+'","deviceId":"${deviceId}"}';path='/api/orders/${orderId}/pay'
 else:body='{"requestId":"'+request+'","userId":${userId},"eventSessionId":"${__P(sessionId,session-1)}","ticketSkuId":"${__P(skuId,sku-1)}","quantity":1,"deviceId":"${deviceId}"}';path='/api/tickets/reserve'
 put('performance/jmeter/'+name+'.jmx','''<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3"><hashTree>
 <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="'''+name+'''" enabled="true"><stringProp name="TestPlan.comments">Use disposable test fixtures; verify database invariants after completion.</stringProp><elementProp name="TestPlan.user_defined_variables" elementType="Arguments"><collectionProp name="Arguments.arguments"/></elementProp></TestPlan><hashTree>
 <ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="Concurrent requests" enabled="true">
  <stringProp name="ThreadGroup.on_sample_error">continue</stringProp><elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController"><boolProp name="LoopController.continue_forever">false</boolProp><stringProp name="LoopController.loops">${__P(loops,1)}</stringProp></elementProp><stringProp name="ThreadGroup.num_threads">${__P(threads,'''+str(threads)+''')}</stringProp><stringProp name="ThreadGroup.ramp_time">${__P(ramp,10)}</stringProp><boolProp name="ThreadGroup.scheduler">false</boolProp>
 </ThreadGroup><hashTree>
 <CSVDataSet guiclass="TestBeanGUI" testclass="CSVDataSet" testname="Authenticated test identities" enabled="true"><stringProp name="filename">${__P(users,users.csv)}</stringProp><stringProp name="fileEncoding">UTF-8</stringProp><stringProp name="variableNames">userId,token,deviceId,orderId</stringProp><stringProp name="delimiter">,</stringProp><boolProp name="ignoreFirstLine">true</boolProp><boolProp name="recycle">true</boolProp><boolProp name="stopThread">false</boolProp><stringProp name="shareMode">shareMode.all</stringProp></CSVDataSet><hashTree/>
 <HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="Request headers" enabled="true"><collectionProp name="HeaderManager.headers"><elementProp name="" elementType="Header"><stringProp name="Header.name">Content-Type</stringProp><stringProp name="Header.value">application/json</stringProp></elementProp><elementProp name="" elementType="Header"><stringProp name="Header.name">authorization</stringProp><stringProp name="Header.value">${token}</stringProp></elementProp></collectionProp></HeaderManager><hashTree/>
 <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="'''+action+'''" enabled="true"><boolProp name="HTTPSampler.postBodyRaw">true</boolProp><elementProp name="HTTPsampler.Arguments" elementType="Arguments"><collectionProp name="Arguments.arguments"><elementProp name="" elementType="HTTPArgument"><boolProp name="HTTPArgument.always_encode">false</boolProp><stringProp name="Argument.value">'''+escape(body)+'''</stringProp><stringProp name="Argument.metadata">=</stringProp></elementProp></collectionProp></elementProp><stringProp name="HTTPSampler.domain">${__P(host,localhost)}</stringProp><stringProp name="HTTPSampler.port">${__P(port,8082)}</stringProp><stringProp name="HTTPSampler.protocol">http</stringProp><stringProp name="HTTPSampler.path">'''+path+'''</stringProp><stringProp name="HTTPSampler.method">POST</stringProp><boolProp name="HTTPSampler.use_keepalive">true</boolProp><stringProp name="HTTPSampler.connect_timeout">5000</stringProp><stringProp name="HTTPSampler.response_timeout">30000</stringProp></HTTPSamplerProxy><hashTree>
 <JSR223Assertion guiclass="TestBeanGUI" testclass="JSR223Assertion" testname="Response contract" enabled="true"><stringProp name="scriptLanguage">groovy</stringProp><stringProp name="cacheKey">true</stringProp><stringProp name="script">def code=prev.getResponseCode().toInteger(); if(code==200){def body=new groovy.json.JsonSlurper().parseText(prev.getResponseDataAsString()); if(body.success != true || body.data == null){AssertionResult.setFailure(true);AssertionResult.setFailureMessage('Missing successful business result');}} else if(!(code in [409,429])){AssertionResult.setFailure(true);AssertionResult.setFailureMessage('Unexpected HTTP status '+code);}</stringProp></JSR223Assertion><hashTree/>
 </hashTree></hashTree></hashTree></hashTree></jmeterTestPlan>''')
put('performance/verify_invariants.sql','''
-- Run against a disposable performance database after queues/compensation drain.
-- Every query below must return zero rows.
SELECT id,remaining_stock,total_stock FROM tb_ticket_sku WHERE remaining_stock<0 OR remaining_stock>total_stock;
SELECT s.id FROM tb_ticket_sku s LEFT JOIN tb_reservation r ON r.sku_id=s.id AND r.status='HELD'
 GROUP BY s.id,s.total_stock,s.remaining_stock HAVING COALESCE(SUM(r.quantity),0)+s.remaining_stock<>s.total_stock;
SELECT user_id,sku_id,SUM(quantity) FROM tb_reservation WHERE status='HELD' GROUP BY user_id,sku_id
 HAVING SUM(quantity)>(SELECT purchase_limit FROM tb_ticket_sku WHERE id=sku_id);
SELECT request_id FROM tb_reservation GROUP BY request_id HAVING COUNT(*)>1;
SELECT reservation_id FROM tb_ticket_order GROUP BY reservation_id HAVING COUNT(*)>1;
SELECT order_id,type FROM tb_wallet_ledger GROUP BY order_id,type HAVING COUNT(*)>1;
SELECT user_id FROM tb_wallet_account WHERE available_balance<0 OR frozen_balance<0;
SELECT id FROM tb_payment_transaction p WHERE status='SUCCESS' AND NOT EXISTS (SELECT 1 FROM tb_wallet_ledger l WHERE l.transaction_id=p.id);
SELECT id FROM tb_outbox_message WHERE status<>'SENT';
''')
put('performance/jmeter/README.md','''
# Correctness load tests

Use a disposable test database and Redis instance. These plans never create fake risk results.

1. Prepare real authenticated users in `users.csv`: `userId,token,deviceId,orderId`. For 10,000 users provide 10,000 distinct accounts/devices. Log in through the demo login API, which creates real Redis tokens. Give test users a wallet balance through SQL fixtures, never a production recharge mock.
2. Seed a dedicated SKU with stock=1000 and purchaseLimit=2. Set the SKU and session with `-JskuId=... -JsessionId=...`. Avoid existing demo traffic.
3. For `03`, `04`, `05`, use a CSV containing one user so all threads share identity. `03` shares requestId; `04` generates distinct request IDs; `05` shares an existing WAITING_PAYMENT order and paymentRequestId.
4. Run `jmeter -n -t 02_flash_sale_10000_users.jmx -Jusers=users.csv -Jthreads=10000 -JskuId=... -l result.jtl`. Size/distribute load generators appropriately; this repository makes no QPS claim. Ramp time defaults to ten seconds and is configurable.
5. Capacity/limit scenarios should use a test-only risk rule configuration with velocity thresholds above generated load. Record original rule versions and restore afterwards. The attack scenario uses normal rules and repeated device identities. Otherwise rate limiting can legitimately stop most requests before inventory is reached.
6. Drain Outbox and compensation, then execute `../verify_invariants.sql`. Additionally assert exact fixture expectations: duplicate request reservation=1/order=1; user quantity<=2; flash successful reservations<=1000 and stock>=0; payment one successful debit. Compare final wallet balance with initial balance minus PAYMENT plus REFUND ledger sums.

The response assertion accepts 409/429 as business rejections, while treating 5xx/auth errors as failures. A successful HTTP response alone does not prove absence of overselling. Inspect database rows and retained Redis reservation markers.

These plans have been XML-validated only until an actual load run is recorded in TEST_RESULTS.md.
''')
