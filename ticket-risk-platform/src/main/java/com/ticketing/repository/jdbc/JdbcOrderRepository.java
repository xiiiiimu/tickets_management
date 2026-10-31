package com.ticketing.repository.jdbc;

import com.ticketing.infrastructure.Db;
import com.ticketing.order.TicketOrder;
import com.ticketing.repository.OrderRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;

@Repository
public class JdbcOrderRepository implements OrderRepository {

    private final Db db;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.ticketing.risk.RiskHistorySummary summaries;

    public JdbcOrderRepository(Db db) {
        this.db = db;
    }

    @Override
    public void insertWaitingPayment(Object orderId, Object reservationId, Object userId, Object skuId, int quantity,
                                     BigDecimal amount, Object deviceId, Object ip, Object traceId, Timestamp expiresAt) {
        db.jdbc.update(
                "INSERT INTO tb_ticket_order(id,order_no,reservation_id,user_id,sku_id,quantity,amount,device_id,ip,status,trace_id,expires_at) VALUES(?,?,?,?,?,?,?,?,?,'WAITING_PAYMENT',?,?)",
                orderId, orderId, reservationId, userId, skuId, quantity, amount, deviceId, ip, traceId, expiresAt);
        if(summaries!=null)summaries.changed(userId,"ORDER:"+orderId+":0");
    }

    @Override
    public TicketOrder findById(String orderId) {
        return map(db.one("SELECT * FROM tb_ticket_order WHERE id=?", orderId));
    }

    @Override
    public TicketOrder findByIdForUpdate(String orderId) {
        return map(db.one("SELECT * FROM tb_ticket_order WHERE id=? FOR UPDATE", orderId));
    }

    @Override
    public String findStatusById(Object orderId) {
        Map<String, Object> row = db.one("SELECT status FROM tb_ticket_order WHERE id=?", orderId);
        return row == null || row.get("status") == null ? null : row.get("status").toString();
    }

    @Override
    public boolean tryTransition(Object orderId, String expectedStatus, Object expectedVersion, String targetStatus) {
        int changed=db.jdbc.update(
                "UPDATE tb_ticket_order SET status=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status=? AND version=?",
                targetStatus, orderId, expectedStatus, expectedVersion);
        if(changed==1 && summaries!=null && summaries.isEnabled()){
            Map<String,Object> owner=db.one("SELECT user_id FROM tb_ticket_order WHERE id=?",orderId);
            summaries.changed(owner.get("user_id"),"ORDER:"+orderId+":"+(((Number)expectedVersion).longValue()+1));
        }
        return changed==1;
    }

    static TicketOrder map(Map<String, Object> row) {
        if (row == null) return null;
        TicketOrder order = new TicketOrder();
        order.setId(str(row.get("id")));
        order.setOrderNo(str(row.get("order_no")));
        order.setReservationId(str(row.get("reservation_id")));
        order.setUserId(num(row.get("user_id")));
        order.setSkuId(str(row.get("sku_id")));
        order.setQuantity((int) num(row.get("quantity")));
        Object amount = row.get("amount");
        order.setAmount(amount == null ? null : amount instanceof BigDecimal ? (BigDecimal) amount : new BigDecimal(amount.toString()));
        order.setDeviceId(str(row.get("device_id")));
        order.setIp(str(row.get("ip")));
        order.setStatus(str(row.get("status")));
        order.setVersion((int) num(row.get("version")));
        order.setTraceId(str(row.get("trace_id")));
        order.setExpiresAt((Timestamp) row.get("expires_at"));
        order.setCreatedAt((Timestamp) row.get("created_at"));
        order.setUpdatedAt((Timestamp) row.get("updated_at"));
        return order;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static long num(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }
}
