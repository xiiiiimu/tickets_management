-- Post-run correctness checks for perf_* fixtures.
-- Script prints each check; non-zero row counts = FAIL.

SELECT 'NEG_STOCK' AS check_name, COUNT(*) AS bad_rows FROM tb_ticket_sku WHERE id LIKE 'perf_%' AND (remaining_stock<0 OR remaining_stock>total_stock);
SELECT 'HELD_EXCEEDS_TOTAL' AS check_name, COUNT(*) AS bad_rows FROM (
  SELECT s.id FROM tb_ticket_sku s
  LEFT JOIN tb_reservation r ON r.sku_id=s.id AND r.status='HELD'
  WHERE s.id LIKE 'perf_%'
  GROUP BY s.id,s.total_stock,s.remaining_stock
  HAVING COALESCE(SUM(r.quantity),0)+s.remaining_stock<>s.total_stock
) t;
SELECT 'PURCHASE_OVER_LIMIT' AS check_name, COUNT(*) AS bad_rows FROM tb_user_purchase p
 JOIN tb_ticket_sku s ON s.id=p.sku_id
 WHERE s.id LIKE 'perf_%' AND p.quantity>s.purchase_limit;
SELECT 'DUP_REQUEST_RESERVATION' AS check_name, COUNT(*) AS bad_rows FROM (
  SELECT request_id FROM tb_reservation WHERE id LIKE 'perf_%' OR request_id LIKE 'perf_%' GROUP BY request_id HAVING COUNT(*)>1
) t;
SELECT 'DUP_RESERVATION_ORDER' AS check_name, COUNT(*) AS bad_rows FROM (
  SELECT reservation_id FROM tb_ticket_order WHERE sku_id LIKE 'perf_%' GROUP BY reservation_id HAVING COUNT(*)>1
) t;
SELECT 'DUP_PAYMENT_SUCCESS' AS check_name, COUNT(*) AS bad_rows FROM (
  SELECT order_id FROM tb_payment_transaction WHERE order_id IN (SELECT id FROM tb_ticket_order WHERE sku_id LIKE 'perf_%') AND status='SUCCESS' AND type='PAYMENT'
  GROUP BY order_id HAVING COUNT(*)>1
) t;
SELECT 'DUP_ISSUED_SEQ' AS check_name, COUNT(*) AS bad_rows FROM (
  SELECT order_id,sequence_no FROM tb_issued_ticket WHERE order_id IN (SELECT id FROM tb_ticket_order WHERE sku_id LIKE 'perf_%')
  GROUP BY order_id,sequence_no HAVING COUNT(*)>1
) t;
SELECT 'ORDERS_OVER_STOCK' AS check_name, COUNT(*) AS bad_rows FROM (
  SELECT s.id FROM tb_ticket_sku s
  LEFT JOIN tb_ticket_order o ON o.sku_id=s.id AND o.status NOT IN ('CANCELLED','PAY_TIMEOUT','RISK_BLOCKED')
  WHERE s.id LIKE 'perf_%'
  GROUP BY s.id,s.total_stock
  HAVING COALESCE(SUM(o.quantity),0)>s.total_stock
) t;
SELECT 'OUTBOX_FAILED' AS check_name, COUNT(*) AS bad_rows FROM tb_outbox_message WHERE (aggregate_id LIKE 'perf_%' OR message_key LIKE '%perf_%') AND status='FAILED';
SELECT 'PERF_SUMMARY_STOCK5' AS metric, remaining_stock AS value FROM tb_ticket_sku WHERE id='perf_sku_stock5';
SELECT 'PERF_SUMMARY_STOCK20' AS metric, remaining_stock AS value FROM tb_ticket_sku WHERE id='perf_sku_stock20';
SELECT 'PERF_SUMMARY_STOCK100' AS metric, remaining_stock AS value FROM tb_ticket_sku WHERE id='perf_sku_stock100';
SELECT 'PERF_ORDERS_STOCK5' AS metric, COUNT(*) AS value FROM tb_ticket_order WHERE sku_id='perf_sku_stock5' AND status NOT IN ('CANCELLED','PAY_TIMEOUT','RISK_BLOCKED');
SELECT 'PERF_ORDERS_STOCK20' AS metric, COUNT(*) AS value FROM tb_ticket_order WHERE sku_id='perf_sku_stock20' AND status NOT IN ('CANCELLED','PAY_TIMEOUT','RISK_BLOCKED');
SELECT 'PERF_PAY_SUCCESS' AS metric, COUNT(*) AS value FROM tb_payment_transaction WHERE order_id IN (SELECT id FROM tb_ticket_order WHERE sku_id LIKE 'perf_%') AND status='SUCCESS';
SELECT 'PERF_ISSUED_TICKETS' AS metric, COUNT(*) AS value FROM tb_issued_ticket WHERE order_id IN (SELECT id FROM tb_ticket_order WHERE sku_id LIKE 'perf_%');
