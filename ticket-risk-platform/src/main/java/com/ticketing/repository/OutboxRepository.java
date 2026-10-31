package com.ticketing.repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * Outbox persistence. Lease claim / markSent / retry are atomic CAS operations —
 * callers must not replace them with select-then-update.
 */
public interface OutboxRepository {

    void appendPending(String id, String orderId, String messageKey, String payloadJson);

    List<Map<String, Object>> findPublishable(int limit);

    /**
     * Claim a due row into SENDING with a lease token.
     * @return true iff exactly one row claimed
     */
    boolean tryClaim(String id, String leaseToken, Timestamp leaseUntil);

    boolean markSent(String id, String leaseToken);

    boolean markRetryOrFailed(String id, String leaseToken, String status, int retryCount, Timestamp nextRetryTime);

    void requeueUnconsumedSent(Timestamp olderThan);

    void requeueOldFailed(Timestamp olderThan);
}
