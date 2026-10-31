-- Additive. Activate only after all nodes are compatible and old attempts/intents are drained.
ALTER TABLE tb_reservation_dispatch ADD COLUMN ordered_lua_enabled INT NOT NULL DEFAULT 0;
ALTER TABLE tb_reservation_job ADD COLUMN qualification_seq BIGINT NULL;
ALTER TABLE tb_reservation_job ADD COLUMN lua_turn_finished INT NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX uk_job_qualification ON tb_reservation_job(sku_id,sale_version,qualification_seq);
CREATE TABLE tb_reservation_sku_order (
 sku_id VARCHAR(64) NOT NULL,
 sale_version BIGINT NOT NULL,
 published_seq BIGINT NOT NULL DEFAULT 0,
 next_lua_seq BIGINT NOT NULL DEFAULT 1,
 PRIMARY KEY(sku_id,sale_version)
);
