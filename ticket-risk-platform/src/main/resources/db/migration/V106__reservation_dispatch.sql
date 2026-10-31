CREATE TABLE IF NOT EXISTS tb_risk_subject_guard (
 subject_type VARCHAR(16) NOT NULL, subject_value VARCHAR(128) NOT NULL,
 blocked_until TIMESTAMP(3), version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(subject_type,subject_value)
);
CREATE TABLE IF NOT EXISTS tb_reservation_dispatch (
 id INT PRIMARY KEY, pass_run INT NOT NULL DEFAULT 0,
 pass_weight INT NOT NULL, capacity INT NOT NULL, active_limit INT NOT NULL,
 review_age_ms BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS tb_reservation_job (
 id VARCHAR(36) PRIMARY KEY, request_id VARCHAR(80) NOT NULL UNIQUE, fingerprint VARCHAR(128) NOT NULL,
 user_id BIGINT NOT NULL, session_id VARCHAR(36) NOT NULL, sku_id VARCHAR(36) NOT NULL,
 quantity INT NOT NULL, device_id VARCHAR(100) NOT NULL, ip VARCHAR(64) NOT NULL,
 sale_version BIGINT NOT NULL, state VARCHAR(24) NOT NULL, decision VARCHAR(10), result_code VARCHAR(48),
 admission TEXT, lease_token VARCHAR(36), lease_until TIMESTAMP(3), queue_expires_at TIMESTAMP(3) NOT NULL,
 queued_at TIMESTAMP(3), claimed_at TIMESTAMP(3),
 reservation_id VARCHAR(36) NOT NULL UNIQUE, order_id VARCHAR(36) NOT NULL UNIQUE,
 active_owner VARCHAR(128) UNIQUE, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 KEY idx_reservation_dispatch(state,decision,created_at), KEY idx_reservation_job_lease(state,lease_until)
);
CREATE TABLE IF NOT EXISTS tb_reservation_risk_audit (
 id VARCHAR(36) PRIMARY KEY, job_id VARCHAR(36) NOT NULL, phase VARCHAR(24) NOT NULL,
 decision VARCHAR(10), result_code VARCHAR(48) NOT NULL, detail TEXT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), KEY idx_reservation_audit_job(job_id,created_at)
);
CREATE TABLE IF NOT EXISTS tb_reservation_request_alias (
 request_id VARCHAR(80) PRIMARY KEY, job_id VARCHAR(36) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), KEY idx_reservation_alias_job(job_id)
);
