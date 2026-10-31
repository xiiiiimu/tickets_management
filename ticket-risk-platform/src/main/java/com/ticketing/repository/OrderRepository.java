package com.ticketing.repository;

import com.ticketing.order.TicketOrder;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * Order row access and optimistic status/version CAS on {@code tb_ticket_order}.
 */
public interface OrderRepository {

    /** Insert order already in WAITING_PAYMENT (fast admission passed; ready for auto-pay). */
    void insertWaitingPayment(Object orderId, Object reservationId, Object userId, Object skuId, int quantity,
                              BigDecimal amount, Object deviceId, Object ip, Object traceId, Timestamp expiresAt);

    TicketOrder findById(String orderId);

    TicketOrder findByIdForUpdate(String orderId);

    /** @return current status, or null if the order does not exist */
    String findStatusById(Object orderId);

    /**
     * CAS transition: {@code WHERE id=? AND status=? AND version=?}.
     * @return true iff exactly one row was updated
     */
    boolean tryTransition(Object orderId, String expectedStatus, Object expectedVersion, String targetStatus);
}
