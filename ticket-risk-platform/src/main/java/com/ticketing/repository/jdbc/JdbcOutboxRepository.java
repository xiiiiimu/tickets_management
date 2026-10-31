package com.ticketing.repository.jdbc;

import com.ticketing.infrastructure.Db;
import com.ticketing.repository.OutboxRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Repository
public class JdbcOutboxRepository implements OutboxRepository {

    private final Db db;

    public JdbcOutboxRepository(Db db) {
        this.db = db;
    }

    @Override
    public void appendPending(String id, String orderId, String messageKey, String payloadJson) {
        db.jdbc.update(
                "INSERT INTO tb_outbox_message(id,aggregate_type,aggregate_id,topic,message_key,payload,status) VALUES(?,'ORDER',?,'ticket-events',?,?,'PENDING')",
                id, orderId, messageKey, payloadJson);
    }

    @Override
    public List<Map<String, Object>> findPublishable(int limit) {
        // Prefer AUTO_PAYMENT / ISSUE so payment fulfillment is not starved behind RISK_CHECK backlog.
        // LIMIT kept inline (same as pre-refactor publisher) — avoid driver quirks with bound LIMIT.
        return db.jdbc.queryForList(
                "SELECT * FROM tb_outbox_message WHERE status IN ('PENDING','RETRY','SENDING') AND next_retry_time<=CURRENT_TIMESTAMP "
                        + "ORDER BY CASE "
                        + "WHEN message_key LIKE 'AUTO_PAYMENT:%' THEN 0 "
                        + "WHEN message_key LIKE 'ISSUE:%' THEN 1 "
                        + "WHEN message_key LIKE 'NOTIFY:%' THEN 2 "
                        + "WHEN message_key LIKE 'RESERVATION_CREATED:%' THEN 3 "
                        + "ELSE 4 END, created_at LIMIT "
                        + limit);
    }

    @Override
    public boolean tryClaim(String id, String leaseToken, Timestamp leaseUntil) {
        return db.jdbc.update(
                "UPDATE tb_outbox_message SET status='SENDING',lease_token=?,next_retry_time=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('PENDING','RETRY','SENDING') AND next_retry_time<=CURRENT_TIMESTAMP",
                leaseToken, leaseUntil, id) == 1;
    }

    @Override
    public boolean markSent(String id, String leaseToken) {
        return db.jdbc.update(
                "UPDATE tb_outbox_message SET status='SENT',updated_at=CURRENT_TIMESTAMP WHERE id=? AND lease_token=? AND status='SENDING'",
                id, leaseToken) == 1;
    }

    @Override
    public boolean markRetryOrFailed(String id, String leaseToken, String status, int retryCount, Timestamp nextRetryTime) {
        return db.jdbc.update(
                "UPDATE tb_outbox_message SET status=?,retry_count=?,next_retry_time=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND lease_token=? AND status='SENDING'",
                status, retryCount, nextRetryTime, id, leaseToken) == 1;
    }

    @Override
    public void requeueUnconsumedSent(Timestamp olderThan) {
        db.jdbc.update(
                "UPDATE tb_outbox_message o SET o.status='RETRY',o.next_retry_time=CURRENT_TIMESTAMP WHERE o.status='SENT' AND o.updated_at<? AND NOT EXISTS (SELECT 1 FROM tb_message_consume_record c WHERE c.consumer_group='ticket-events' AND c.message_key=o.message_key AND c.status='DONE')",
                olderThan);
    }

    @Override
    public void requeueOldFailed(Timestamp olderThan) {
        db.jdbc.update(
                "UPDATE tb_outbox_message SET status='RETRY',retry_count=0,next_retry_time=CURRENT_TIMESTAMP WHERE status='FAILED' AND updated_at<?",
                olderThan);
    }
}
