-- Preserve existing deadlines; V2 stores Redis server millisecond timestamps without rounding past 10 minutes.
ALTER TABLE tb_reservation MODIFY expires_at TIMESTAMP(3) NOT NULL;
ALTER TABLE tb_ticket_order MODIFY expires_at TIMESTAMP(3) NOT NULL;
