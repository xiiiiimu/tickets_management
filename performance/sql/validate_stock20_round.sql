-- Per-round correctness for perf_sku_stock20 (initial_stock=20)
SELECT 'NEG_STOCK' AS check_name, COUNT(*) AS bad_rows
FROM tb_ticket_sku WHERE id='perf_sku_stock20' AND (remaining_stock<0 OR remaining_stock>total_stock);

SELECT 'ORDERS_OVER_STOCK' AS check_name, COUNT(*) AS bad_rows FROM (
  SELECT s.id FROM tb_ticket_sku s
  LEFT JOIN tb_ticket_order o ON o.sku_id=s.id AND o.status NOT IN ('CANCELLED','PAY_TIMEOUT','RISK_BLOCKED')
  WHERE s.id='perf_sku_stock20'
  GROUP BY s.id,s.total_stock
  HAVING COALESCE(SUM(o.quantity),0)>s.total_stock
) t;

SELECT 'DUP_REQUEST_RESERVATION' AS check_name, COUNT(*) AS bad_rows FROM (
  SELECT request_id FROM tb_reservation WHERE sku_id='perf_sku_stock20' GROUP BY request_id HAVING COUNT(*)>1
) t;

SELECT 'DUP_RESERVATION_ORDER' AS check_name, COUNT(*) AS bad_rows FROM (
  SELECT reservation_id FROM tb_ticket_order WHERE sku_id='perf_sku_stock20' GROUP BY reservation_id HAVING COUNT(*)>1
) t;

SELECT 'DUP_PAYMENT_SUCCESS' AS check_name, COUNT(*) AS bad_rows FROM (
  SELECT order_id FROM tb_payment_transaction
  WHERE order_id IN (SELECT id FROM tb_ticket_order WHERE sku_id='perf_sku_stock20')
    AND status='SUCCESS' AND type='PAYMENT'
  GROUP BY order_id HAVING COUNT(*)>1
) t;

SELECT 'DUP_ISSUED_SEQ' AS check_name, COUNT(*) AS bad_rows FROM (
  SELECT order_id,sequence_no FROM tb_issued_ticket
  WHERE order_id IN (SELECT id FROM tb_ticket_order WHERE sku_id='perf_sku_stock20')
  GROUP BY order_id,sequence_no HAVING COUNT(*)>1
) t;

SELECT 'PURCHASE_OVER_LIMIT' AS check_name, COUNT(*) AS bad_rows
FROM tb_user_purchase p JOIN tb_ticket_sku s ON s.id=p.sku_id
WHERE s.id='perf_sku_stock20' AND p.quantity>s.purchase_limit;

SELECT 'STOCK_CONSISTENCY' AS check_name, COUNT(*) AS bad_rows FROM (
  SELECT s.id FROM tb_ticket_sku s
  LEFT JOIN tb_reservation r ON r.sku_id=s.id AND r.status='HELD'
  WHERE s.id='perf_sku_stock20'
  GROUP BY s.id,s.total_stock,s.remaining_stock
  HAVING COALESCE(SUM(r.quantity),0)+s.remaining_stock<>s.total_stock
     AND COALESCE((SELECT SUM(o.quantity) FROM tb_ticket_order o WHERE o.sku_id=s.id AND o.status NOT IN ('CANCELLED','PAY_TIMEOUT','RISK_BLOCKED')),0) > s.total_stock
) t;

SELECT 'METRIC_INITIAL_STOCK' AS metric, total_stock AS value FROM tb_ticket_sku WHERE id='perf_sku_stock20';
SELECT 'METRIC_REMAINING' AS metric, remaining_stock AS value FROM tb_ticket_sku WHERE id='perf_sku_stock20';
SELECT 'METRIC_VALID_ORDERS' AS metric, COUNT(*) AS value
FROM tb_ticket_order WHERE sku_id='perf_sku_stock20' AND status NOT IN ('CANCELLED','PAY_TIMEOUT','RISK_BLOCKED');
SELECT 'METRIC_RESERVATIONS' AS metric, COUNT(*) AS value FROM tb_reservation WHERE sku_id='perf_sku_stock20';
