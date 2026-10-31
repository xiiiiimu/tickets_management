from build_ticketing import put,java,root

schema='''
CREATE TABLE IF NOT EXISTS tb_user (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, phone VARCHAR(32) NOT NULL UNIQUE, password VARCHAR(255),
 nick_name VARCHAR(80), role VARCHAR(20) NOT NULL DEFAULT 'USER', icon VARCHAR(255),
 create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS tb_event (
 id VARCHAR(36) PRIMARY KEY, name VARCHAR(150) NOT NULL, description TEXT, venue VARCHAR(200) NOT NULL, status VARCHAR(20) NOT NULL
);
CREATE TABLE IF NOT EXISTS tb_event_session (
 id VARCHAR(36) PRIMARY KEY, event_id VARCHAR(36) NOT NULL, starts_at TIMESTAMP NOT NULL,
 FOREIGN KEY(event_id) REFERENCES tb_event(id)
);
CREATE TABLE IF NOT EXISTS tb_ticket_sku (
 id VARCHAR(36) PRIMARY KEY, event_session_id VARCHAR(36) NOT NULL, name VARCHAR(80) NOT NULL,
 price DECIMAL(18,2) NOT NULL, total_stock INT NOT NULL, remaining_stock INT NOT NULL,
 sale_start_time TIMESTAMP NOT NULL, sale_end_time TIMESTAMP NOT NULL, purchase_limit INT NOT NULL,
 status VARCHAR(20) NOT NULL, version INT NOT NULL DEFAULT 0,
 FOREIGN KEY(event_session_id) REFERENCES tb_event_session(id),
 CHECK (price > 0), CHECK (remaining_stock >= 0), CHECK (total_stock >= remaining_stock), CHECK (purchase_limit > 0)
);
CREATE TABLE IF NOT EXISTS tb_reserve_request (
 request_id VARCHAR(80) PRIMARY KEY, fingerprint VARCHAR(128) NOT NULL, reservation_id VARCHAR(36) NOT NULL UNIQUE,
 order_id VARCHAR(36) NOT NULL UNIQUE, user_id BIGINT NOT NULL, sku_id VARCHAR(36) NOT NULL,
 quantity INT NOT NULL, device_id VARCHAR(100) NOT NULL, ip VARCHAR(64) NOT NULL, trace_id VARCHAR(64),
 status VARCHAR(20) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_request_recovery(status,created_at)
);
CREATE TABLE IF NOT EXISTS tb_user_purchase (
 user_id BIGINT NOT NULL, sku_id VARCHAR(36) NOT NULL, quantity INT NOT NULL DEFAULT 0,
 PRIMARY KEY(user_id,sku_id), CHECK(quantity>=0)
);
CREATE TABLE IF NOT EXISTS tb_reservation (
 id VARCHAR(36) PRIMARY KEY, request_id VARCHAR(80) NOT NULL UNIQUE, user_id BIGINT NOT NULL,
 sku_id VARCHAR(36) NOT NULL, quantity INT NOT NULL, status VARCHAR(20) NOT NULL,
 expires_at TIMESTAMP NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_reservation_expiry(status,expires_at)
);
CREATE TABLE IF NOT EXISTS tb_ticket_order (
 id VARCHAR(36) PRIMARY KEY, order_no VARCHAR(36) NOT NULL UNIQUE, reservation_id VARCHAR(36) NOT NULL UNIQUE,
 user_id BIGINT NOT NULL, sku_id VARCHAR(36) NOT NULL, quantity INT NOT NULL, amount DECIMAL(18,2) NOT NULL,
 device_id VARCHAR(100) NOT NULL, ip VARCHAR(64) NOT NULL, status VARCHAR(30) NOT NULL, version INT NOT NULL DEFAULT 0,
 trace_id VARCHAR(64), expires_at TIMESTAMP NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(reservation_id) REFERENCES tb_reservation(id), KEY idx_order_user(user_id,created_at), KEY idx_order_expiry(status,expires_at)
);
CREATE TABLE IF NOT EXISTS tb_outbox_message (
 id VARCHAR(36) PRIMARY KEY, aggregate_type VARCHAR(32) NOT NULL, aggregate_id VARCHAR(36) NOT NULL,
 topic VARCHAR(80) NOT NULL, message_key VARCHAR(100) NOT NULL UNIQUE, payload TEXT NOT NULL,
 status VARCHAR(20) NOT NULL, retry_count INT NOT NULL DEFAULT 0, next_retry_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 lease_token VARCHAR(36), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_outbox_due(status,next_retry_time)
);
CREATE TABLE IF NOT EXISTS tb_message_consume_record (
 consumer_group VARCHAR(80) NOT NULL, message_key VARCHAR(100) NOT NULL, business_key VARCHAR(100) NOT NULL,
 status VARCHAR(20) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(consumer_group,message_key), UNIQUE KEY uk_consume_business(consumer_group,business_key)
);
CREATE TABLE IF NOT EXISTS tb_risk_rule (
 rule_code VARCHAR(64) PRIMARY KEY, enabled BOOLEAN NOT NULL, weight INT NOT NULL, config TEXT NOT NULL, version INT NOT NULL
);
CREATE TABLE IF NOT EXISTS tb_risk_decision (
 id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL, phase VARCHAR(20) NOT NULL,
 decision VARCHAR(10) NOT NULL, score INT NOT NULL, features TEXT NOT NULL, matched_rules TEXT NOT NULL,
 agent_analysis TEXT, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, KEY idx_decision_order(order_id)
);
CREATE TABLE IF NOT EXISTS tb_manual_review_ticket (
 id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL, risk_decision_id VARCHAR(36) NOT NULL UNIQUE,
 phase VARCHAR(20) NOT NULL, status VARCHAR(20) NOT NULL, request_id VARCHAR(80) UNIQUE,
 reviewer_id BIGINT, version INT NOT NULL DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS tb_wallet_account (
 user_id BIGINT PRIMARY KEY, available_balance DECIMAL(18,2) NOT NULL DEFAULT 0,
 frozen_balance DECIMAL(18,2) NOT NULL DEFAULT 0, version INT NOT NULL DEFAULT 0,
 CHECK(available_balance>=0), CHECK(frozen_balance>=0)
);
CREATE TABLE IF NOT EXISTS tb_payment_transaction (
 id VARCHAR(36) PRIMARY KEY, payment_request_id VARCHAR(80) NOT NULL UNIQUE, order_id VARCHAR(36) NOT NULL,
 user_id BIGINT NOT NULL, amount DECIMAL(18,2) NOT NULL, type VARCHAR(20) NOT NULL, status VARCHAR(20) NOT NULL,
 success_key VARCHAR(80) UNIQUE, device_id VARCHAR(100) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 KEY idx_payment_user(user_id,status,created_at)
);
CREATE TABLE IF NOT EXISTS tb_wallet_ledger (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, order_id VARCHAR(36) NOT NULL,
 transaction_id VARCHAR(36) NOT NULL UNIQUE, type VARCHAR(20) NOT NULL, amount DECIMAL(18,2) NOT NULL,
 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS tb_issued_ticket (
 id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL, sequence_no INT NOT NULL,
 UNIQUE KEY uk_ticket_order(order_id,sequence_no)
);
'''
old=root/'ticket-risk-platform/src/main/resources/db/migration'
for name in ['V001_workflow_persistence.sql','V003_audit_log.sql']:
 s=(old/name).read_text(encoding='utf-8').replace('USE credit;','').replace('application_id','order_id').replace('LangGraph State Checkpoint','Workflow checkpoint')
 if 'audit' in name: s=s.replace('`success` tinyint','`cache_hit` tinyint(1) NOT NULL DEFAULT 0,\n  `success` tinyint')
 schema+='\n'+s
put('ticket-risk-platform/src/main/resources/schema.sql',schema)
put('ticket-risk-platform/src/main/resources/db/migration/V100__ticketing_schema.sql',schema)
seed='''
INSERT INTO tb_user(id,phone,nick_name,role,create_time) VALUES (1,'13800000001','演示管理员','ADMIN','2025-01-01'),(2,'13800000002','演示观众','USER','2025-01-01');
INSERT INTO tb_wallet_account(user_id,available_balance) VALUES(1,10000),(2,10000);
INSERT INTO tb_event VALUES('event-1','星河之夜 · 城市巡演','两场现场演出，实名限购。','城市体育中心','ON_SALE');
INSERT INTO tb_event_session VALUES('session-1','event-1','2027-12-18 19:30:00'),('session-2','event-1','2027-12-19 19:30:00');
INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,sale_start_time,sale_end_time,purchase_limit,status) VALUES
 ('sku-1','session-1','看台',380,1000,1000,'2026-01-01','2027-12-18',2,'ON_SALE'),
 ('sku-2','session-1','内场',680,500,500,'2026-01-01','2027-12-18',2,'ON_SALE'),
 ('sku-3','session-2','VIP',1280,200,200,'2026-01-01','2027-12-19',2,'ON_SALE');
'''
import json
rules=[('IP_FREQUENCY','ipMinute',120,100,True),('DEVICE_FREQUENCY','deviceMinute',60,100,True),('USER_FREQUENCY','userMinute',30,100,True),('DEVICE_ACCOUNTS','deviceAccounts',5,60,False),('IP_ACCOUNTS','ipAccounts',20,40,False),('NEW_ACCOUNT_FREQUENCY','userTenMinutes',10,60,False),('CANCEL_RATE','cancelRate',0.6,50,False),('REFUND_RATE','refundRate',0.5,50,False),('PAYMENT_FAILURES','paymentFailures',3,60,False),('NEW_DEVICE_AMOUNT','paymentAmount',1000,60,False),('PAYMENT_VELOCITY','paymentMinute',10,60,False)]
for code,feature,threshold,weight,fast in rules:
 config={'feature':feature,'threshold':threshold,'fast':fast}
 if code=='NEW_ACCOUNT_FREQUENCY':config['maxAccountAgeHours']=24
 if code in ['CANCEL_RATE','REFUND_RATE']:config['minHistory']=5
 if code=='NEW_DEVICE_AMOUNT':config['requiresNewDevice']=True
 seed+="INSERT INTO tb_risk_rule VALUES('%s',true,%d,'%s',1);\n"%(code,weight,json.dumps(config))
seed+="INSERT INTO tb_risk_rule VALUES('DECISION_POLICY',true,0,'{\"review\":50,\"block\":100,\"agentMaxAdjustment\":20,\"rateLimit\":180}',1);\n"
put('ticket-risk-platform/src/main/resources/data.sql',seed)
put('ticket-risk-platform/src/main/resources/db/migration/V101__ticketing_demo.sql',seed)

for path,fields in {
 'event/Event':'String id; String name; String description; String venue; String status;',
 'event/EventSession':'String id; String eventId; java.time.LocalDateTime startsAt;',
 'ticket/TicketSku':'String id; String eventSessionId; String name; java.math.BigDecimal price; int totalStock; int remainingStock; java.time.LocalDateTime saleStartTime; java.time.LocalDateTime saleEndTime; int purchaseLimit; String status;',
 'risk/RiskFeature':'java.util.Map<String,Double> values;',
 'risk/RiskRule':'String ruleCode; boolean enabled; int weight; java.util.Map<String,Object> config; int version;',
 'risk/RiskScore':'int value; java.util.List<String> matchedRules;'
}.items():
 pkg,name=path.split('/'); java(path,'package com.ticketing.'+pkg+';\n@lombok.Data\npublic class '+name+' { private '+fields.replace('; ','; private ')+' }')

java('order/OrderStatus','''
package com.ticketing.order;
public enum OrderStatus {CREATED,RISK_CHECKING,RISK_REVIEW,RISK_BLOCKED,WAITING_PAYMENT,PAYING,PAID,ISSUED,PAY_TIMEOUT,CANCELLED,REFUNDING,REFUNDED}
''')
java('order/OrderStateMachine','''
package com.ticketing.order;
import java.util.*;
import com.ticketing.infrastructure.Db;
import com.ticketing.common.BusinessException;
import org.springframework.stereotype.Service;
@Service
public class OrderStateMachine {
 private static final Map<OrderStatus,Set<OrderStatus>> EDGES=new EnumMap<>(OrderStatus.class);
 static {
  edge("CREATED","RISK_CHECKING","CANCELLED","PAY_TIMEOUT");
  edge("RISK_CHECKING","RISK_REVIEW","RISK_BLOCKED","WAITING_PAYMENT","PAY_TIMEOUT");
  edge("RISK_REVIEW","WAITING_PAYMENT","RISK_BLOCKED","PAY_TIMEOUT");
  edge("WAITING_PAYMENT","PAYING","RISK_REVIEW","RISK_BLOCKED","PAY_TIMEOUT","CANCELLED");
  edge("PAYING","PAID");edge("PAID","ISSUED","REFUNDING");edge("ISSUED","REFUNDING");edge("REFUNDING","REFUNDED");
 }
 private static void edge(String from,String...to){Set<OrderStatus> s=new HashSet<>();for(String t:to)s.add(OrderStatus.valueOf(t));EDGES.put(OrderStatus.valueOf(from),s);}
 public static boolean permits(OrderStatus from,OrderStatus to){return EDGES.getOrDefault(from,Collections.emptySet()).contains(to);}
 private final Db db;
 public OrderStateMachine(Db db){this.db=db;}
 public void transition(Map<String,Object> order,OrderStatus next){
  OrderStatus previous=OrderStatus.valueOf(order.get("status").toString());
  BusinessException.require(permits(previous,next),"订单状态不允许此操作");
  int changed=db.jdbc.update("UPDATE tb_ticket_order SET status=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status=? AND version=?",next.name(),order.get("id"),previous.name(),order.get("version"));
  BusinessException.require(changed==1,"订单已变更，请重试");
  order.put("status",next.name());order.put("version",Db.number(order,"version")+1);
 }
}
''')
