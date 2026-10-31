-- Apply only to explicitly selected project databases. No destructive backfill.
CREATE TABLE IF NOT EXISTS tb_risk_summary_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, source_key VARCHAR(128) NOT NULL UNIQUE,
 processed BOOLEAN NOT NULL DEFAULT false, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 KEY idx_risk_summary_pending(processed,user_id,id)
);
CREATE TABLE IF NOT EXISTS tb_risk_user_summary (
 user_id BIGINT PRIMARY KEY, payload TEXT NOT NULL, captured_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE IF NOT EXISTS tb_risk_known_device (
 user_id BIGINT NOT NULL, device_id VARCHAR(100) NOT NULL, PRIMARY KEY(user_id,device_id)
);
