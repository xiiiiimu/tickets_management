CREATE TABLE IF NOT EXISTS tb_order_notification (
 id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL, user_id BIGINT NOT NULL,
 kind VARCHAR(24) NOT NULL, status VARCHAR(16) NOT NULL, body TEXT NOT NULL,
 delivery_attempts INT NOT NULL DEFAULT 0, delivered_at TIMESTAMP(3), read_at TIMESTAMP(3),
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_order_notification(order_id,kind), KEY idx_user_notification(user_id,status,created_at)
);
