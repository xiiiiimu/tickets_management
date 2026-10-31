package com.ticketing.repository.jdbc;

import com.ticketing.infrastructure.Db;
import com.ticketing.repository.PaymentRepository;
import org.springframework.stereotype.Repository;

import java.util.Map;

@Repository
public class JdbcPaymentRepository implements PaymentRepository {

    private final Db db;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.ticketing.risk.RiskHistorySummary summaries;

    public JdbcPaymentRepository(Db db) {
        this.db = db;
    }

    @Override
    public Map<String, Object> findByPaymentRequestId(String paymentRequestId) {
        return db.one("SELECT * FROM tb_payment_transaction WHERE payment_request_id=?", paymentRequestId);
    }

    @Override
    public Map<String, Object> findById(String id) {
        return db.one("SELECT * FROM tb_payment_transaction WHERE id=?", id);
    }

    @Override
    public void insert(String id, String paymentRequestId, String orderId, long userId, Object amount,
                       String type, String status, String successKey, String deviceId) {
        db.jdbc.update(
                "INSERT INTO tb_payment_transaction(id,payment_request_id,order_id,user_id,amount,type,status,success_key,device_id) VALUES(?,?,?,?,?,?,?,?,?)",
                id, paymentRequestId, orderId, userId, amount, type, status, successKey, deviceId);
        if(summaries!=null)summaries.changed(userId,"PAYMENT:"+id);
    }
}
