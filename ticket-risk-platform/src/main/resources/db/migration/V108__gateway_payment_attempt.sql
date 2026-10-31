CREATE TABLE IF NOT EXISTS tb_payment_request_claim (
 payment_request_id VARCHAR(80) PRIMARY KEY, order_id VARCHAR(36) NOT NULL, user_id BIGINT NOT NULL,
 operation VARCHAR(24) NOT NULL, device_id VARCHAR(100) NOT NULL
);
CREATE TABLE IF NOT EXISTS tb_gateway_payment_attempt (
 id VARCHAR(36) PRIMARY KEY, payment_request_id VARCHAR(80) NOT NULL UNIQUE,
 order_id VARCHAR(36) NOT NULL, user_id BIGINT NOT NULL, amount DECIMAL(18,2) NOT NULL,
 currency VARCHAR(8) NOT NULL, device_id VARCHAR(100) NOT NULL, status VARCHAR(24) NOT NULL,
 settlement_kind VARCHAR(16) NOT NULL DEFAULT 'NONE', active_order VARCHAR(36) UNIQUE,
 provider_transaction_id VARCHAR(100) UNIQUE, paid_at TIMESTAMP(3), expires_at TIMESTAMP(3) NOT NULL,
 lease_token VARCHAR(36), lease_until TIMESTAMP(3), next_check_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 retry_count INT NOT NULL DEFAULT 0, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 KEY idx_gateway_due(status,next_check_at), KEY idx_gateway_order(order_id,settlement_kind)
);
