package com.ticketing.repository.jdbc;

import com.ticketing.infrastructure.Db;
import com.ticketing.repository.ReservationRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

@Repository
public class JdbcReservationRepository implements ReservationRepository {

    private final Db db;

    public JdbcReservationRepository(Db db) {
        this.db = db;
    }

    @Override
    public void insertHeld(Object reservationId, String requestId, Object userId, Object skuId, int quantity, Timestamp expiresAt) {
        db.jdbc.update(
                "INSERT INTO tb_reservation(id,request_id,user_id,sku_id,quantity,status,expires_at) VALUES(?,?,?,?,?,'HELD',?)",
                reservationId, requestId, userId, skuId, quantity, expiresAt);
    }

    @Override
    public boolean tryReleaseHeld(Object reservationId) {
        return db.jdbc.update(
                "UPDATE tb_reservation SET status='RELEASED' WHERE id=? AND status='HELD'",
                reservationId) == 1;
    }
}
