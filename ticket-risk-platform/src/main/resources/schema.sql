CREATE TABLE IF NOT EXISTS tb_user (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, phone VARCHAR(32) NOT NULL UNIQUE, password VARCHAR(255),
 nick_name VARCHAR(80), role VARCHAR(20) NOT NULL DEFAULT 'USER', icon VARCHAR(255),
 create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS tb_event (
 id VARCHAR(36) PRIMARY KEY, name VARCHAR(150) NOT NULL, description TEXT, venue VARCHAR(200) NOT NULL, status VARCHAR(20) NOT NULL
);
CREATE TABLE IF NOT EXISTS tb_event_session (
 id VARCHAR(36) PRIMARY KEY, event_id VARCHAR(36) NOT NULL, starts_at TIMESTAMP NOT NULL,
 FOREIGN KEY(event_id) REFERENCES tb_event(id)
);
CREATE TABLE IF NOT EXISTS tb_ticket_sku (
 id VARCHAR(36) PRIMARY KEY, event_session_id VARCHAR(36) NOT NULL, name VARCHAR(80) NOT NULL,
 price DECIMAL(18,2) NOT NULL, total_stock INT NOT NULL, remaining_stock INT NOT NULL,
 sale_start_time TIMESTAMP NOT NULL, sale_end_time TIMESTAMP NOT NULL, purchase_limit INT NOT NULL,
 single_purchase_limit INT NOT NULL DEFAULT 100,
 status VARCHAR(20) NOT NULL, version INT NOT NULL DEFAULT 0,
 FOREIGN KEY(event_session_id) REFERENCES tb_event_session(id),
 CHECK (price > 0), CHECK (remaining_stock >= 0), CHECK (total_stock >= remaining_stock), CHECK (purchase_limit > 0)
);
CREATE TABLE IF NOT EXISTS tb_reserve_request (
 request_id VARCHAR(80) PRIMARY KEY, fingerprint VARCHAR(128) NOT NULL, reservation_id VARCHAR(36) NOT NULL UNIQUE,
 order_id VARCHAR(36) NOT NULL UNIQUE, user_id BIGINT NOT NULL, sku_id VARCHAR(36) NOT NULL,
 quantity INT NOT NULL, device_id VARCHAR(100) NOT NULL, ip VARCHAR(64) NOT NULL, trace_id VARCHAR(64),
 status VARCHAR(20) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_request_recovery(status,created_at)
);
CREATE TABLE IF NOT EXISTS tb_user_purchase (
 user_id BIGINT NOT NULL, sku_id VARCHAR(36) NOT NULL, quantity INT NOT NULL DEFAULT 0,
 PRIMARY KEY(user_id,sku_id), CHECK(quantity>=0)
);
CREATE TABLE IF NOT EXISTS tb_reservation (
 id VARCHAR(36) PRIMARY KEY, request_id VARCHAR(80) NOT NULL UNIQUE, user_id BIGINT NOT NULL,
 sku_id VARCHAR(36) NOT NULL, quantity INT NOT NULL, status VARCHAR(20) NOT NULL,
 expires_at TIMESTAMP(3) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_reservation_expiry(status,expires_at)
);
CREATE TABLE IF NOT EXISTS tb_ticket_order (
 id VARCHAR(36) PRIMARY KEY, order_no VARCHAR(36) NOT NULL UNIQUE, reservation_id VARCHAR(36) NOT NULL UNIQUE,
 user_id BIGINT NOT NULL, sku_id VARCHAR(36) NOT NULL, quantity INT NOT NULL, amount DECIMAL(18,2) NOT NULL,
 device_id VARCHAR(100) NOT NULL, ip VARCHAR(64) NOT NULL, status VARCHAR(30) NOT NULL, version INT NOT NULL DEFAULT 0,
 trace_id VARCHAR(64), expires_at TIMESTAMP(3) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(reservation_id) REFERENCES tb_reservation(id), KEY idx_order_user(user_id,created_at), KEY idx_order_expiry(status,expires_at)
);
CREATE TABLE IF NOT EXISTS tb_outbox_message (
 id VARCHAR(36) PRIMARY KEY, aggregate_type VARCHAR(32) NOT NULL, aggregate_id VARCHAR(36) NOT NULL,
 topic VARCHAR(80) NOT NULL, message_key VARCHAR(100) NOT NULL UNIQUE, payload TEXT NOT NULL,
 status VARCHAR(20) NOT NULL, retry_count INT NOT NULL DEFAULT 0, next_retry_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 lease_token VARCHAR(36), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_outbox_due(status,next_retry_time)
);
CREATE TABLE IF NOT EXISTS tb_message_consume_record (
 consumer_group VARCHAR(80) NOT NULL, message_key VARCHAR(100) NOT NULL, business_key VARCHAR(100) NOT NULL,
 status VARCHAR(20) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(consumer_group,message_key), UNIQUE KEY uk_consume_business(consumer_group,business_key)
);
CREATE TABLE IF NOT EXISTS tb_risk_rule (
 rule_code VARCHAR(64) PRIMARY KEY, enabled BOOLEAN NOT NULL, weight INT NOT NULL, config TEXT NOT NULL, version INT NOT NULL
);
CREATE TABLE IF NOT EXISTS tb_risk_decision (
 id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL, phase VARCHAR(20) NOT NULL,
 decision VARCHAR(10) NOT NULL, score INT NOT NULL, features TEXT NOT NULL, matched_rules TEXT NOT NULL,
 agent_analysis TEXT, context_key VARCHAR(512) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, KEY idx_decision_order(order_id)
);
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
CREATE TABLE IF NOT EXISTS tb_manual_review_ticket (
 id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL, risk_decision_id VARCHAR(36) NOT NULL UNIQUE,
 phase VARCHAR(20) NOT NULL, status VARCHAR(20) NOT NULL, request_id VARCHAR(80) UNIQUE,
 reviewer_id BIGINT, version INT NOT NULL DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS tb_wallet_account (
 user_id BIGINT PRIMARY KEY, available_balance DECIMAL(18,2) NOT NULL DEFAULT 0,
 frozen_balance DECIMAL(18,2) NOT NULL DEFAULT 0, version INT NOT NULL DEFAULT 0,
 CHECK(available_balance>=0), CHECK(frozen_balance>=0)
);
CREATE TABLE IF NOT EXISTS tb_payment_transaction (
 id VARCHAR(36) PRIMARY KEY, payment_request_id VARCHAR(80) NOT NULL UNIQUE, order_id VARCHAR(36) NOT NULL,
 user_id BIGINT NOT NULL, amount DECIMAL(18,2) NOT NULL, type VARCHAR(20) NOT NULL, status VARCHAR(20) NOT NULL,
 success_key VARCHAR(80) UNIQUE, device_id VARCHAR(100) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 KEY idx_payment_user(user_id,status,created_at)
);
CREATE TABLE IF NOT EXISTS tb_wallet_ledger (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, order_id VARCHAR(36) NOT NULL,
 transaction_id VARCHAR(36) NOT NULL UNIQUE, type VARCHAR(20) NOT NULL, amount DECIMAL(18,2) NOT NULL,
 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS tb_issued_ticket (
 id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL, sequence_no INT NOT NULL,
 UNIQUE KEY uk_ticket_order(order_id,sequence_no)
);

-- Phase 1: Workflow 状态持久化 + Checkpoint + 节点执行链路


CREATE TABLE IF NOT EXISTS `tb_workflow` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `workflow_id` varchar(64) NOT NULL COMMENT '全链路唯一业务ID',
  `status` varchar(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/MANUAL_REVIEW',
  `current_node` varchar(64) DEFAULT NULL,
  `retry_count` int NOT NULL DEFAULT 0,
  `trace_id` varchar(64) DEFAULT NULL,
  `task_id` bigint(20) DEFAULT NULL,
  `order_id` varchar(36) DEFAULT NULL,
  `result_json` mediumtext COMMENT 'Agent 成功结果快照',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workflow_id` (`workflow_id`),
  KEY `idx_workflow_status` (`status`),
  KEY `idx_workflow_task` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Workflow 主表';

CREATE TABLE IF NOT EXISTS `tb_workflow_node` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `workflow_id` varchar(64) NOT NULL,
  `node_name` varchar(64) NOT NULL,
  `agent_name` varchar(64) DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/SKIPPED',
  `input_json` mediumtext,
  `output_json` mediumtext,
  `error_code` varchar(64) DEFAULT NULL,
  `error_msg` varchar(512) DEFAULT NULL,
  `start_time` timestamp NULL DEFAULT NULL,
  `end_time` timestamp NULL DEFAULT NULL,
  `retry_count` int NOT NULL DEFAULT 0,
  `cost_time_ms` int DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_workflow_node` (`workflow_id`, `node_name`),
  KEY `idx_workflow_node_time` (`workflow_id`, `start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Workflow 节点执行记录';

CREATE TABLE IF NOT EXISTS `tb_workflow_checkpoint` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `workflow_id` varchar(64) NOT NULL,
  `current_node` varchar(64) DEFAULT NULL,
  `state_json` mediumtext,
  `history_json` mediumtext,
  `retry_count` int NOT NULL DEFAULT 0,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_checkpoint_workflow` (`workflow_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Workflow checkpoint';

-- Phase 4: 审计日志


CREATE TABLE IF NOT EXISTS `tb_audit_log` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `workflow_id` varchar(64) DEFAULT NULL,
  `trace_id` varchar(64) DEFAULT NULL,
  `node_name` varchar(64) DEFAULT NULL,
  `call_type` varchar(32) NOT NULL COMMENT 'LLM / TOOL',
  `prompt_version` int DEFAULT NULL,
  `rule_version` varchar(128) DEFAULT NULL,
  `request_json` text,
  `response_json` text,
  `token_count` int DEFAULT 0,
  `cost_time_ms` int DEFAULT 0,
  `cache_hit` tinyint(1) NOT NULL DEFAULT 0,
  `success` tinyint(1) NOT NULL DEFAULT 1,
  `error_msg` varchar(512) DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_audit_workflow` (`workflow_id`, `create_time`),
  KEY `idx_audit_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM/Tool 审计日志';

-- Optional inventory buckets. Empty tables leave the original single-row stock path intact.
CREATE TABLE IF NOT EXISTS tb_ticket_stock_bucket (
 sku_id VARCHAR(36) NOT NULL,
 bucket_id INT NOT NULL,
 total_stock INT NOT NULL,
 remaining_stock INT NOT NULL,
 version INT NOT NULL DEFAULT 0,
 PRIMARY KEY(sku_id,bucket_id),
 CHECK(total_stock>=0), CHECK(remaining_stock>=0), CHECK(remaining_stock<=total_stock)
);
CREATE TABLE IF NOT EXISTS tb_stock_bucket_allocation (
 reservation_id VARCHAR(36) PRIMARY KEY,
 request_id VARCHAR(80) NOT NULL UNIQUE,
 sku_id VARCHAR(36) NOT NULL,
 bucket_id INT NOT NULL,
 quantity INT NOT NULL,
 released BOOLEAN NOT NULL DEFAULT FALSE,
 KEY idx_bucket_allocation(sku_id,bucket_id), CHECK(quantity>0)
);

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

CREATE TABLE IF NOT EXISTS tb_risk_subject_guard (
 subject_type VARCHAR(16) NOT NULL, subject_value VARCHAR(128) NOT NULL,
 blocked_until TIMESTAMP(3), version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(subject_type,subject_value)
);
CREATE TABLE IF NOT EXISTS tb_reservation_dispatch (
 id INT PRIMARY KEY, pass_run INT NOT NULL DEFAULT 0,
 pass_weight INT NOT NULL, capacity INT NOT NULL, active_limit INT NOT NULL,
 review_age_ms BIGINT NOT NULL, runtime_protocol INT NOT NULL DEFAULT 2,
 ordered_lua_enabled INT NOT NULL DEFAULT 0,
 quick_review_enabled INT NOT NULL DEFAULT 0,
 vector_inventory_enabled INT NOT NULL DEFAULT 0,
 redis_lane_enabled INT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS tb_reservation_job (
 id VARCHAR(36) PRIMARY KEY, request_id VARCHAR(80) NOT NULL UNIQUE, fingerprint VARCHAR(128) NOT NULL,
 user_id BIGINT NOT NULL, session_id VARCHAR(36) NOT NULL, sku_id VARCHAR(36) NOT NULL,
 quantity INT NOT NULL, device_id VARCHAR(100) NOT NULL, ip VARCHAR(64) NOT NULL,
 sale_version BIGINT NOT NULL, state VARCHAR(24) NOT NULL, decision VARCHAR(10), result_code VARCHAR(48),
 admission TEXT, lease_token VARCHAR(36), lease_until TIMESTAMP(3), queue_expires_at TIMESTAMP(3) NOT NULL,
 queued_at TIMESTAMP(3), claimed_at TIMESTAMP(3),
 attempt_protocol INT NOT NULL DEFAULT 2, waiting_deadline TIMESTAMP(3), processing_started_at TIMESTAMP(3),
 qualification_seq BIGINT, lua_turn_finished INT NOT NULL DEFAULT 0,
 reservation_id VARCHAR(36) NOT NULL UNIQUE, order_id VARCHAR(36) NOT NULL UNIQUE,
 active_owner VARCHAR(128) UNIQUE, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 KEY idx_reservation_dispatch(state,decision,created_at), KEY idx_reservation_job_lease(state,lease_until),
 KEY idx_attempt_wait(attempt_protocol,state,waiting_deadline,created_at),
 KEY idx_attempt_lease(attempt_protocol,state,lease_until), KEY idx_attempt_user(attempt_protocol,user_id,state),
 UNIQUE KEY uk_job_qualification(sku_id,sale_version,qualification_seq)
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

CREATE TABLE IF NOT EXISTS tb_order_notification (
 id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL, user_id BIGINT NOT NULL,
 kind VARCHAR(24) NOT NULL, status VARCHAR(16) NOT NULL, body TEXT NOT NULL,
 delivery_attempts INT NOT NULL DEFAULT 0, delivered_at TIMESTAMP(3), read_at TIMESTAMP(3),
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_order_notification(order_id,kind), KEY idx_user_notification(user_id,status,created_at)
);

-- Stage C: fresh-schema qualification order (existing schemas use V111 once).
CREATE TABLE IF NOT EXISTS tb_reservation_sku_order (
 sku_id VARCHAR(64) NOT NULL,
 sale_version BIGINT NOT NULL,
 published_seq BIGINT NOT NULL DEFAULT 0,
 next_lua_seq BIGINT NOT NULL DEFAULT 1,
 PRIMARY KEY(sku_id,sale_version)
);
CREATE TABLE IF NOT EXISTS tb_stock_bucket_allocation_item (
 reservation_id VARCHAR(36) NOT NULL, bucket_id INT NOT NULL, quantity INT NOT NULL,
 PRIMARY KEY(reservation_id,bucket_id), CHECK(bucket_id>=0 AND bucket_id<16), CHECK(quantity>0)
);
