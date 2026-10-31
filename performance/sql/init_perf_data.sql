-- Performance fixtures: IDs prefixed with perf_

DELETE FROM tb_issued_ticket WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id LIKE 'perf_%' OR id LIKE 'perf_%') x);
DELETE FROM tb_payment_transaction WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id LIKE 'perf_%' OR id LIKE 'perf_%') x);
DELETE FROM tb_wallet_ledger WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id LIKE 'perf_%' OR id LIKE 'perf_%') x);
DELETE FROM tb_message_consume_record WHERE message_key LIKE '%perf_%' OR business_key LIKE '%perf_%';
DELETE FROM tb_outbox_message WHERE aggregate_id LIKE 'perf_%' OR message_key LIKE '%perf_%';
DELETE FROM tb_risk_decision WHERE order_id IN (SELECT id FROM (SELECT id FROM tb_ticket_order WHERE sku_id LIKE 'perf_%' OR id LIKE 'perf_%') x);
DELETE FROM tb_manual_review_ticket WHERE order_id LIKE 'perf_%';
DELETE FROM tb_ticket_order WHERE sku_id LIKE 'perf_%' OR id LIKE 'perf_%';
DELETE FROM tb_reservation WHERE sku_id LIKE 'perf_%' OR id LIKE 'perf_%';
DELETE FROM tb_reserve_request WHERE sku_id LIKE 'perf_%' OR request_id LIKE 'perf_%';
DELETE FROM tb_user_purchase WHERE sku_id LIKE 'perf_%';
DELETE FROM tb_risk_profile WHERE subject_value LIKE 'perf_%' OR id LIKE 'perf_%' OR subject_value='90100';
DELETE FROM tb_ticket_sku WHERE id LIKE 'perf_%';
DELETE FROM tb_event_session WHERE id LIKE 'perf_%';
DELETE FROM tb_event WHERE id LIKE 'perf_%';
DELETE FROM tb_wallet_account WHERE user_id BETWEEN 90001 AND 90200;
-- Free 1399* phones held by earlier auto-registered demo users so fixture IDs can own them.
UPDATE tb_user SET phone=CONCAT('1370',LPAD(id,7,'0')) WHERE phone LIKE '1399%' AND id NOT BETWEEN 90001 AND 90200;
DELETE FROM tb_user WHERE id BETWEEN 90001 AND 90200;

INSERT INTO tb_event VALUES('perf_event','PERF 压测演出','performance fixture','Perf Arena','ON_SALE');
INSERT INTO tb_event_session VALUES('perf_session','perf_event','2027-12-31 19:30:00');

INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,sale_start_time,sale_end_time,purchase_limit,status,version) VALUES
 ('perf_sku_stock5','perf_session','STOCK5',10.00,5,5,'2020-01-01','2030-12-31',2,'ON_SALE',0),
 ('perf_sku_stock20','perf_session','STOCK20',10.00,20,20,'2020-01-01','2030-12-31',2,'ON_SALE',0),
 ('perf_sku_stock100','perf_session','STOCK100',10.00,100,100,'2020-01-01','2030-12-31',2,'ON_SALE',0),
 ('perf_sku_limit','perf_session','LIMIT2',10.00,1000,1000,'2020-01-01','2030-12-31',2,'ON_SALE',0),
 ('perf_sku_dup','perf_session','DUP',10.00,100,100,'2020-01-01','2030-12-31',5,'ON_SALE',0),
 ('perf_sku_block','perf_session','BLOCK',10.00,100,100,'2020-01-01','2030-12-31',5,'ON_SALE',0),
 ('perf_sku_risk','perf_session','RISK',10.00,200,200,'2020-01-01','2030-12-31',5,'ON_SALE',0);

UPDATE tb_risk_rule SET config='{"review":50,"block":100,"agentMaxAdjustment":20,"rateLimit":100000}',version=version+1 WHERE rule_code='DECISION_POLICY';
UPDATE tb_risk_rule SET config='{"feature":"ipMinute","threshold":100000,"fast":true}',version=version+1 WHERE rule_code='IP_FREQUENCY';
UPDATE tb_risk_rule SET config='{"feature":"deviceMinute","threshold":100000,"fast":true}',version=version+1 WHERE rule_code='DEVICE_FREQUENCY';
UPDATE tb_risk_rule SET config='{"feature":"userMinute","threshold":100000,"fast":true}',version=version+1 WHERE rule_code='USER_FREQUENCY';

INSERT INTO tb_user(id,phone,nick_name,role,create_time)
SELECT 90000+n, CONCAT('1399',LPAD(n,7,'0')), CONCAT('perf_user_',n), 'USER', '2025-01-01'
FROM (
  SELECT a.N + b.N*10 + 1 AS n
  FROM (SELECT 0 N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
  CROSS JOIN (SELECT 0 N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10 UNION SELECT 11) b
) nums WHERE n BETWEEN 1 AND 120;

INSERT INTO tb_wallet_account(user_id,available_balance,frozen_balance,version)
SELECT id,10000,0,0 FROM tb_user WHERE id BETWEEN 90001 AND 90120
ON DUPLICATE KEY UPDATE available_balance=10000;

INSERT INTO tb_risk_profile(id,subject_type,subject_value,risk_level,decision,score,reason_codes,source_order_id,source_decision_id,version,expires_at)
VALUES('perf_profile_block','USER','90100','HIGH','BLOCK',120,'PERF_BLOCK',NULL,'perf_seed',0,DATE_ADD(NOW(), INTERVAL 1 DAY));
