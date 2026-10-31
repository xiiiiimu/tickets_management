-- Additive only. Activate after all nodes are compatible and old attempts/intents are drained.
ALTER TABLE tb_reservation_dispatch ADD COLUMN quick_review_enabled INT NOT NULL DEFAULT 0;
