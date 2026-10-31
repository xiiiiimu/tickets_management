-- Additive only: no stock/order changes and no conversion of existing jobs.
-- Protocol activation is a separate, reviewed operation after old work is drained.
ALTER TABLE tb_reservation_dispatch ADD COLUMN runtime_protocol INT NOT NULL DEFAULT 2;
ALTER TABLE tb_reservation_job ADD COLUMN attempt_protocol INT NOT NULL DEFAULT 2;
ALTER TABLE tb_reservation_job ADD COLUMN waiting_deadline TIMESTAMP(3) NULL;
ALTER TABLE tb_reservation_job ADD COLUMN processing_started_at TIMESTAMP(3) NULL;
CREATE INDEX idx_attempt_wait ON tb_reservation_job(attempt_protocol,state,waiting_deadline,created_at);
CREATE INDEX idx_attempt_lease ON tb_reservation_job(attempt_protocol,state,lease_until);
CREATE INDEX idx_attempt_user ON tb_reservation_job(attempt_protocol,user_id,state);
