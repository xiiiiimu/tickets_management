INSERT INTO tb_user(id,phone,nick_name,role,create_time) VALUES (1,'13800000001','演示管理员','ADMIN','2025-01-01'),(2,'13800000002','演示观众','USER','2025-01-01');
INSERT INTO tb_wallet_account(user_id,available_balance) VALUES(1,10000),(2,10000);
INSERT INTO tb_event VALUES('event-1','星河之夜 · 城市巡演','两场现场演出，实名限购。','城市体育中心','ON_SALE');
INSERT INTO tb_event_session VALUES('session-1','event-1','2027-12-18 19:30:00'),('session-2','event-1','2027-12-19 19:30:00');
INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,sale_start_time,sale_end_time,purchase_limit,status) VALUES
 ('sku-1','session-1','看台',380,1000,1000,'2026-01-01','2027-12-18',2,'ON_SALE'),
 ('sku-2','session-1','内场',680,500,500,'2026-01-01','2027-12-18',2,'ON_SALE'),
 ('sku-3','session-2','VIP',1280,200,200,'2026-01-01','2027-12-19',2,'ON_SALE');
INSERT INTO tb_risk_rule VALUES('IP_FREQUENCY',true,100,'{"feature": "ipMinute", "threshold": 120, "fast": true}',1);
INSERT INTO tb_risk_rule VALUES('DEVICE_FREQUENCY',true,100,'{"feature": "deviceMinute", "threshold": 60, "fast": true}',1);
INSERT INTO tb_risk_rule VALUES('USER_FREQUENCY',true,100,'{"feature": "userMinute", "threshold": 30, "fast": true}',1);
INSERT INTO tb_risk_rule VALUES('DEVICE_ACCOUNTS',true,60,'{"feature": "deviceAccounts", "threshold": 5, "fast": false}',1);
INSERT INTO tb_risk_rule VALUES('IP_ACCOUNTS',true,40,'{"feature": "ipAccounts", "threshold": 20, "fast": false}',1);
INSERT INTO tb_risk_rule VALUES('NEW_ACCOUNT_FREQUENCY',true,60,'{"feature": "userTenMinutes", "threshold": 10, "fast": false, "maxAccountAgeHours": 24}',1);
INSERT INTO tb_risk_rule VALUES('CANCEL_RATE',true,50,'{"feature": "cancelRate", "threshold": 0.6, "fast": false, "minHistory": 5}',1);
INSERT INTO tb_risk_rule VALUES('REFUND_RATE',true,50,'{"feature": "refundRate", "threshold": 0.5, "fast": false, "minHistory": 5}',1);
INSERT INTO tb_risk_rule VALUES('PAYMENT_FAILURES',true,60,'{"feature": "paymentFailures", "threshold": 3, "fast": false}',1);
INSERT INTO tb_risk_rule VALUES('NEW_DEVICE_AMOUNT',true,60,'{"feature": "paymentAmount", "threshold": 1000, "fast": false, "requiresNewDevice": true}',1);
INSERT INTO tb_risk_rule VALUES('PAYMENT_VELOCITY',true,60,'{"feature": "paymentMinute", "threshold": 10, "fast": false}',1);
INSERT INTO tb_risk_rule VALUES('DECISION_POLICY',true,0,'{"review":50,"block":100,"agentMaxAdjustment":20,"rateLimit":180}',1);
