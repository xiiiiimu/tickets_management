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
