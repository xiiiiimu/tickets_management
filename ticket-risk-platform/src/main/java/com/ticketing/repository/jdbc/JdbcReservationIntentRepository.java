package com.ticketing.repository.jdbc;

import com.ticketing.infrastructure.Db;
import com.ticketing.repository.ReservationIntentRepository;
import com.ticketing.ticket.ReservationIntent;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Map;

@Repository
public class JdbcReservationIntentRepository implements ReservationIntentRepository {

    private final Db db;

    public JdbcReservationIntentRepository(Db db) {
        this.db = db;
    }

    @Override
    public ReservationIntent findByRequestId(String requestId) {
        return map(db.one("SELECT * FROM tb_reserve_request WHERE request_id=?", requestId));
    }

    @Override
    public ReservationIntent findByRequestIdForUpdate(String requestId) {
        return map(db.one("SELECT * FROM tb_reserve_request WHERE request_id=? FOR UPDATE", requestId));
    }

    @Override
    public void insertRequested(String requestId, String fingerprint, String reservationId, String orderId,
                                Object userId, Object skuId, Object quantity, Object deviceId, String ip, String traceId) {
        db.jdbc.update(
                "INSERT INTO tb_reserve_request(request_id,fingerprint,reservation_id,order_id,user_id,sku_id,quantity,device_id,ip,trace_id,status) VALUES(?,?,?,?,?,?,?,?,?,?,'REQUESTED') ON DUPLICATE KEY UPDATE request_id=request_id",
                requestId, fingerprint, reservationId, orderId, userId, skuId, quantity, deviceId, ip, traceId);
    }

    @Override
    public void markCompleted(String requestId) {
        db.jdbc.update("UPDATE tb_reserve_request SET status='COMPLETED' WHERE request_id=?", requestId);
    }

    @Override
    public boolean tryMarkFailedIfRequested(String requestId) {
        return db.jdbc.update(
                "UPDATE tb_reserve_request SET status='FAILED' WHERE request_id=? AND status='REQUESTED'",
                requestId) == 1;
    }

    @Override
    public long countBySkuId(Object skuId) {
        Long count = db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reserve_request WHERE sku_id=?", Long.class, skuId);
        return count == null ? 0L : count;
    }

    static ReservationIntent map(Map<String, Object> row) {
        if (row == null) return null;
        ReservationIntent intent = new ReservationIntent();
        intent.setRequestId(str(row.get("request_id")));
        intent.setFingerprint(str(row.get("fingerprint")));
        intent.setReservationId(str(row.get("reservation_id")));
        intent.setOrderId(str(row.get("order_id")));
        intent.setUserId(num(row.get("user_id")));
        intent.setSkuId(str(row.get("sku_id")));
        intent.setQuantity((int) num(row.get("quantity")));
        intent.setDeviceId(str(row.get("device_id")));
        intent.setIp(str(row.get("ip")));
        intent.setTraceId(str(row.get("trace_id")));
        intent.setStatus(str(row.get("status")));
        intent.setCreatedAt((Timestamp) row.get("created_at"));
        return intent;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static long num(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }
}
