package com.ticketing.repository;

import java.sql.Timestamp;

/**
 * Hold / release operations on {@code tb_reservation}.
 * No typed row model: core path only inserts HELD and CAS-releases to RELEASED.
 */
public interface ReservationRepository {

    void insertHeld(Object reservationId, String requestId, Object userId, Object skuId, int quantity, Timestamp expiresAt);

    /**
     * CAS: HELD → RELEASED. Stock/quota restore must only run when this returns true.
     */
    boolean tryReleaseHeld(Object reservationId);
}
