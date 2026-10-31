-- Additive. Persisted mode bit for the Redis-arrival SKU lane (attempts.redis-ordered-lane).
-- Activate only with every node stopped, after draining PROCESSING attempts whose SKU turn is published but unfinished:
--   UPDATE tb_reservation_dispatch SET redis_lane_enabled=1 WHERE id=1;
ALTER TABLE tb_reservation_dispatch ADD COLUMN redis_lane_enabled INT NOT NULL DEFAULT 0;
