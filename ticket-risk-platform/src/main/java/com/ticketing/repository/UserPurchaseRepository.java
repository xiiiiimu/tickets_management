package com.ticketing.repository;

/**
 * Per-user purchase quota on {@code tb_user_purchase}. Limit enforcement is a database CAS UPDATE.
 */
public interface UserPurchaseRepository {

    void ensureZeroRow(Object userId, Object skuId);

    /**
     * Atomically increases quantity when {@code quantity + delta <= purchaseLimit}.
     * @return true iff exactly one row was updated
     */
    boolean tryIncreaseWithinLimit(Object userId, Object skuId, int quantity, Object purchaseLimit);

    void decreaseQuantity(Object userId, Object skuId, Object quantity);
}
