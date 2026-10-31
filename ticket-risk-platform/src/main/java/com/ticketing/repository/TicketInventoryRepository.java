package com.ticketing.repository;

import com.ticketing.ticket.TicketSku;

/**
 * Atomic stock operations on {@code tb_ticket_sku}. CAS predicates are part of the concurrency protocol.
 */
public interface TicketInventoryRepository {

    TicketSku findById(Object skuId);
    /** Static sale row only; callers must never treat its projection as authoritative bucket stock. */
    default TicketSku findStaticById(Object skuId) { return findById(skuId); }

    TicketSku findByIdForUpdate(Object skuId);

    /**
     * Locks the SKU row; used to serialize concurrent reservation-intent creates.
     * @return true iff the SKU id exists and was locked
     */
    boolean lockIdForUpdate(Object skuId);

    /**
     * Atomically decreases remaining stock when the SKU is on sale and in window.
     * @return true iff exactly one row was updated
     */
    boolean tryDecreaseStock(Object skuId, int quantity);

    default boolean tryDecreaseStockForReservation(com.ticketing.ticket.ReservationIntent r) {
        return tryDecreaseStock(r.getSkuId(),r.getQuantity());
    }

    void increaseStock(Object skuId, Object quantity);

    default void increaseStockForReservation(String reservationId, Object skuId, Object quantity) {
        increaseStock(skuId,quantity);
    }
}
