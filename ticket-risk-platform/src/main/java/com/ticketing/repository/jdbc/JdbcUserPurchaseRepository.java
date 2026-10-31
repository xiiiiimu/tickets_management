package com.ticketing.repository.jdbc;

import com.ticketing.infrastructure.Db;
import com.ticketing.repository.UserPurchaseRepository;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserPurchaseRepository implements UserPurchaseRepository {

    private final Db db;

    public JdbcUserPurchaseRepository(Db db) {
        this.db = db;
    }

    @Override
    public void ensureZeroRow(Object userId, Object skuId) {
        db.jdbc.update(
                "INSERT INTO tb_user_purchase(user_id,sku_id,quantity) VALUES(?,?,0) ON DUPLICATE KEY UPDATE user_id=user_id",
                userId, skuId);
    }

    @Override
    public boolean tryIncreaseWithinLimit(Object userId, Object skuId, int quantity, Object purchaseLimit) {
        return db.jdbc.update(
                "UPDATE tb_user_purchase SET quantity=quantity+? WHERE user_id=? AND sku_id=? AND quantity+?<=?",
                quantity, userId, skuId, quantity, purchaseLimit) == 1;
    }

    @Override
    public void decreaseQuantity(Object userId, Object skuId, Object quantity) {
        db.jdbc.update(
                "UPDATE tb_user_purchase SET quantity=quantity-? WHERE user_id=? AND sku_id=?",
                quantity, userId, skuId);
    }
}
