-- Opt-in sale assembly. Does not migrate or rewrite existing inventory/order rows.
CREATE TABLE IF NOT EXISTS tb_ticket_sale_assembly (
 session_id VARCHAR(36) PRIMARY KEY,
 state VARCHAR(20) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 active_version BIGINT NOT NULL DEFAULT 0,
 active_digest VARCHAR(64) NOT NULL DEFAULT '',
 request_id VARCHAR(80) NOT NULL DEFAULT '',
 lease_until TIMESTAMP NULL,
 error VARCHAR(512) NOT NULL DEFAULT '',
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(session_id) REFERENCES tb_event_session(id)
);
CREATE TABLE IF NOT EXISTS tb_ticket_sale_version (
 session_id VARCHAR(36) NOT NULL,
 version BIGINT NOT NULL,
 request_id VARCHAR(80) NOT NULL,
 operation VARCHAR(16) NOT NULL,
 status VARCHAR(20) NOT NULL,
 manifest LONGTEXT NOT NULL,
 digest VARCHAR(64) NOT NULL DEFAULT '',
 operator_id BIGINT NOT NULL,
 error VARCHAR(512) NOT NULL DEFAULT '',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(session_id,version),
 UNIQUE KEY uk_sale_operation(session_id,request_id),
 FOREIGN KEY(session_id) REFERENCES tb_event_session(id)
);
