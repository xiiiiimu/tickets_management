package com.ticketing.repository;

import com.ticketing.ticket.ReservationIntent;

/**
 * Persistence for {@code tb_reserve_request} (reservation intent / request-id idempotency).
 */
public interface ReservationIntentRepository {

    ReservationIntent findByRequestId(String requestId);

    ReservationIntent findByRequestIdForUpdate(String requestId);

    /**
     * Inserts a REQUESTED intent. On duplicate PK, leaves the existing row unchanged
     * ({@code ON DUPLICATE KEY UPDATE request_id=request_id}).
     */
    void insertRequested(String requestId, String fingerprint, String reservationId, String orderId,
                         Object userId, Object skuId, Object quantity, Object deviceId, String ip, String traceId);

    void markCompleted(String requestId);

    /** CAS: REQUESTED → FAILED. Returns true iff one row changed. */
    boolean tryMarkFailedIfRequested(String requestId);

    long countBySkuId(Object skuId);
}
