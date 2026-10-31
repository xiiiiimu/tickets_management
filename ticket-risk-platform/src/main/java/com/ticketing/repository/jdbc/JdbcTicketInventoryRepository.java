package com.ticketing.repository.jdbc;

import com.ticketing.infrastructure.Db;
import com.ticketing.repository.TicketInventoryRepository;
import com.ticketing.ticket.TicketSku;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;

@Repository
public class JdbcTicketInventoryRepository implements TicketInventoryRepository {

    private final Db db;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.ticketing.inventory.StockBucketService buckets;

    public JdbcTicketInventoryRepository(Db db) {
        this.db = db;
    }

    @Override
    public TicketSku findStaticById(Object skuId) {
        return map(db.one("SELECT * FROM tb_ticket_sku WHERE id=?", skuId));
    }

    @Override
    public TicketSku findById(Object skuId) {
        TicketSku sku=map(db.one("SELECT * FROM tb_ticket_sku WHERE id=?", skuId));
        if(sku!=null && buckets!=null && buckets.active(skuId)) sku.setRemainingStock(buckets.remaining(skuId));
        return sku;
    }

    @Override
    public TicketSku findByIdForUpdate(Object skuId) {
        return com.ticketing.config.RequestTimings.measure("sku.for_update", () -> map(db.one("SELECT * FROM tb_ticket_sku WHERE id=? FOR UPDATE", skuId)));
    }

    @Override
    public boolean lockIdForUpdate(Object skuId) {
        return db.one("SELECT id FROM tb_ticket_sku WHERE id=? FOR UPDATE", skuId) != null;
    }

    @Override
    public boolean tryDecreaseStock(Object skuId, int quantity) {
        return db.jdbc.update(
                "UPDATE tb_ticket_sku SET remaining_stock=remaining_stock-?,version=version+1 WHERE id=? AND remaining_stock>=? AND status='ON_SALE' AND sale_start_time<=CURRENT_TIMESTAMP AND sale_end_time>CURRENT_TIMESTAMP",
                quantity, skuId, quantity) == 1;
    }

    @Override
    public void increaseStock(Object skuId, Object quantity) {
        db.jdbc.update(
                "UPDATE tb_ticket_sku SET remaining_stock=remaining_stock+?,version=version+1 WHERE id=?",
                quantity, skuId);
    }

    @Override public boolean tryDecreaseStockForReservation(com.ticketing.ticket.ReservationIntent r) {
        if (r.getInventoryBucket()!=null) {
            if (buckets==null || !buckets.active(r.getSkuId())) throw new IllegalStateException("Bucket topology is not ready");
            return buckets.decrease(r);
        }
        return tryDecreaseStock(r.getSkuId(),r.getQuantity());
    }

    @Override public void increaseStockForReservation(String reservation, Object skuId, Object quantity) {
        if(buckets==null || !buckets.restore(reservation)) increaseStock(skuId,quantity);
    }

    static TicketSku map(Map<String, Object> row) {
        if (row == null) return null;
        TicketSku sku = new TicketSku();
        sku.setId(str(row.get("id")));
        sku.setEventSessionId(str(row.get("event_session_id")));
        sku.setName(str(row.get("name")));
        Object price = row.get("price");
        sku.setPrice(price == null ? null : price instanceof BigDecimal ? (BigDecimal) price : new BigDecimal(price.toString()));
        sku.setTotalStock((int) num(row.get("total_stock")));
        sku.setRemainingStock((int) num(row.get("remaining_stock")));
        sku.setSaleStartTime((Timestamp) row.get("sale_start_time"));
        sku.setSaleEndTime((Timestamp) row.get("sale_end_time"));
        sku.setPurchaseLimit((int) num(row.get("purchase_limit")));
        sku.setSinglePurchaseLimit(row.get("single_purchase_limit") instanceof Number?((Number)row.get("single_purchase_limit")).intValue():100);
        sku.setStatus(str(row.get("status")));
        sku.setVersion((int) num(row.get("version")));
        return sku;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static long num(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }
}
