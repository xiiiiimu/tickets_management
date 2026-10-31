-- Post-hoc RiskProfile table for environments that already applied V100 without the profile DDL.
CREATE TABLE IF NOT EXISTS tb_risk_profile (
 id VARCHAR(36) PRIMARY KEY,
 subject_type VARCHAR(16) NOT NULL,
 subject_value VARCHAR(128) NOT NULL,
 risk_level VARCHAR(16) NOT NULL,
 decision VARCHAR(10) NOT NULL,
 score INT NOT NULL,
 reason_codes TEXT,
 source_order_id VARCHAR(36),
 source_decision_id VARCHAR(36),
 version INT NOT NULL DEFAULT 0,
 expires_at TIMESTAMP NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_risk_profile_subject(subject_type, subject_value),
 KEY idx_risk_profile_expires(expires_at),
 KEY idx_risk_profile_source(source_decision_id)
);
