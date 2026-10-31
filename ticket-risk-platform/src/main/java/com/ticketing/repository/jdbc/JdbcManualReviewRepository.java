package com.ticketing.repository.jdbc;

import com.ticketing.infrastructure.Db;
import com.ticketing.repository.ManualReviewRepository;
import org.springframework.stereotype.Repository;

import java.util.Map;

@Repository
public class JdbcManualReviewRepository implements ManualReviewRepository {

    private final Db db;

    public JdbcManualReviewRepository(Db db) {
        this.db = db;
    }

    @Override
    public void insertPending(String id, Object orderId, Object riskDecisionId, String phase) {
        db.jdbc.update(
                "INSERT INTO tb_manual_review_ticket(id,order_id,risk_decision_id,phase,status) VALUES(?,?,?,?,'PENDING')",
                id, orderId, riskDecisionId, phase);
    }

    @Override
    public Map<String, Object> findById(String id) {
        return db.one("SELECT * FROM tb_manual_review_ticket WHERE id=?", id);
    }

    @Override
    public Map<String, Object> findByIdForUpdate(String id) {
        return db.one("SELECT * FROM tb_manual_review_ticket WHERE id=? FOR UPDATE", id);
    }

    @Override
    public boolean tryDecidePending(String id, String action, String requestId, long reviewerId, Object expectedVersion) {
        return db.jdbc.update(
                "UPDATE tb_manual_review_ticket SET status=?,request_id=?,reviewer_id=?,version=version+1 WHERE id=? AND status='PENDING' AND version=?",
                action, requestId, reviewerId, id, expectedVersion) == 1;
    }

    @Override
    public Map<String, Object> findLatestPaymentApproval(String orderId, String contextKeyJson) {
        return db.one(
                "SELECT t.id FROM tb_manual_review_ticket t JOIN tb_risk_decision d ON d.id=t.risk_decision_id WHERE t.order_id=? AND t.phase='PAYMENT' AND t.status='APPROVE' AND d.context_key=? ORDER BY t.created_at DESC LIMIT 1",
                orderId, contextKeyJson);
    }
}
