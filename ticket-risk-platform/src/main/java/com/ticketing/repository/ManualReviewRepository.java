package com.ticketing.repository;

import java.util.Map;

/**
 * Manual review tickets with PENDING + version CAS.
 */
public interface ManualReviewRepository {

    void insertPending(String id, Object orderId, Object riskDecisionId, String phase);

    Map<String, Object> findById(String id);

    Map<String, Object> findByIdForUpdate(String id);

    /**
     * CAS: {@code WHERE id=? AND status='PENDING' AND version=?}.
     * @return true iff exactly one row updated
     */
    boolean tryDecidePending(String id, String action, String requestId, long reviewerId, Object expectedVersion);

    Map<String, Object> findLatestPaymentApproval(String orderId, String contextKeyJson);
}
