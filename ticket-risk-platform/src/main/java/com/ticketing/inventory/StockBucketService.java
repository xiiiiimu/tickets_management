package com.ticketing.inventory;

import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import com.ticketing.ticket.ReservationIntent;
import com.ticketing.ticket.TicketSku;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Opt-in stock topology. Bucket rows are authoritative; the SKU column is an async projection. */
@Service
public class StockBucketService {
    private final Db db;
    private final int count;
    private final Map<String, Boolean> active = new ConcurrentHashMap<>();

    public StockBucketService(Db db, @Value("${ticketing.inventory.bucket-count:1}") int count) {
        if (count != 1 && count != 16) throw new IllegalArgumentException("Supported bucket counts: 1 or 16");
        this.db = db; this.count = count;
    }

    public List<Map<String,Object>> configure(TicketSku sku) {
        if (count == 1) return Collections.emptyList();
        List<Map<String,Object>> rows = db.jdbc.queryForList(
                "SELECT * FROM tb_ticket_stock_bucket WHERE sku_id=? ORDER BY bucket_id", sku.getId());
        if (rows.isEmpty()) { active.put(sku.getId(), false); return rows; }
        BusinessException.require(rows.size() == count, "库存桶配置不完整");
        long total = 0, remaining = 0;
        for (int i=0;i<count;i++) {
            Map<String,Object> row = rows.get(i);
            BusinessException.require(Db.number(row,"bucket_id")==i, "库存桶编号不连续");
            long capacity=Db.number(row,"total_stock"), stock=Db.number(row,"remaining_stock");
            BusinessException.require(stock>=0 && stock<=capacity, "库存桶数量异常");
            total+=capacity; remaining+=stock;
        }
        BusinessException.require(total==sku.getTotalStock(), "库存桶总量与SKU不一致");
        // Never switch a traded legacy SKU into buckets: allocation/recovery would be ambiguous.
        long legacy=db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation r WHERE r.sku_id=? "
                + "AND NOT EXISTS(SELECT 1 FROM tb_stock_bucket_allocation a WHERE a.reservation_id=r.id)", Long.class,sku.getId());
        BusinessException.require(legacy==0, "已有普通预占，不能切换库存分桶");
        active.put(sku.getId(), true);
        // Background refresh projects the aggregate, never a per-reservation write to the hot SKU row.
        db.jdbc.update("UPDATE tb_ticket_sku SET remaining_stock=? WHERE id=? AND remaining_stock<>?",remaining,sku.getId(),remaining);
        sku.setRemainingStock((int)remaining);
        return rows;
    }

    public int bucketCount() { return count; }

    /** First-time topology only. Never convert a traded SKU or reset an existing bucket. */
    public void initializePristine(TicketSku sku) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Bucket initialization requires the caller's transaction");
        if (count == 1) return;
        long existing = db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_stock_bucket WHERE sku_id=?", Long.class, sku.getId());
        if (existing == 0) {
            BusinessException.require(sku.getRemainingStock() == sku.getTotalStock()
                && sku.getSaleStartTime().getTime() > System.currentTimeMillis()
                && db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reserve_request WHERE sku_id=?", Long.class, sku.getId()) == 0,
                "只能在首次开售且无交易前初始化库存桶");
            for (int bucket = 0; bucket < count; bucket++) {
                int capacity = sku.getTotalStock() / count + (bucket < sku.getTotalStock() % count ? 1 : 0);
                db.jdbc.update("INSERT INTO tb_ticket_stock_bucket(sku_id,bucket_id,total_stock,remaining_stock) VALUES(?,?,?,?)",
                    sku.getId(), bucket, capacity, capacity);
            }
        }
        configure(sku);
    }

    public boolean active(Object sku) { return count>1 && Boolean.TRUE.equals(active.get(String.valueOf(sku))); }

    public int remaining(Object sku) {
        return db.jdbc.queryForObject("SELECT COALESCE(SUM(remaining_stock),0) FROM tb_ticket_stock_bucket WHERE sku_id=?",Integer.class,sku);
    }

    public boolean decrease(ReservationIntent r) {
        if(r.getInventoryAllocation()!=null){
            if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Vector allocation requires caller transaction");
            Map<Integer,Integer> vector=new TreeMap<>(r.getInventoryAllocation());long sum=0;
            for(Map.Entry<Integer,Integer> item:vector.entrySet()){BusinessException.require(item.getKey()>=0&&item.getKey()<16&&item.getValue()>0,"跨桶分配无效");sum+=item.getValue();}
            BusinessException.require(sum==r.getQuantity()&&r.getInventoryBucket()!=null&&r.getInventoryBucket()==-1,"跨桶分配总量无效");
            for(Map.Entry<Integer,Integer> item:vector.entrySet())BusinessException.require(db.jdbc.update("UPDATE tb_ticket_stock_bucket SET remaining_stock=remaining_stock-?,version=version+1 WHERE sku_id=? AND bucket_id=? AND remaining_stock>=? AND EXISTS(SELECT 1 FROM tb_ticket_sku s WHERE s.id=? AND s.status='ON_SALE' AND s.sale_start_time<=CURRENT_TIMESTAMP AND s.sale_end_time>CURRENT_TIMESTAMP)",item.getValue(),r.getSkuId(),item.getKey(),item.getValue(),r.getSkuId())==1,"跨桶库存确认失败，回滚整笔分配");
            db.jdbc.update("INSERT INTO tb_stock_bucket_allocation(reservation_id,request_id,sku_id,bucket_id,quantity,released) VALUES(?,?,?,-1,?,false)",r.getReservationId(),r.getRequestId(),r.getSkuId(),r.getQuantity());
            for(Map.Entry<Integer,Integer> item:vector.entrySet())db.jdbc.update("INSERT INTO tb_stock_bucket_allocation_item(reservation_id,bucket_id,quantity) VALUES(?,?,?)",r.getReservationId(),item.getKey(),item.getValue());
            return true;
        }
        int changed=db.jdbc.update("UPDATE tb_ticket_stock_bucket SET remaining_stock=remaining_stock-?,version=version+1 "
                + "WHERE sku_id=? AND bucket_id=? AND remaining_stock>=? "
                + "AND EXISTS(SELECT 1 FROM tb_ticket_sku s WHERE s.id=? AND s.status='ON_SALE' "
                + "AND s.sale_start_time<=CURRENT_TIMESTAMP AND s.sale_end_time>CURRENT_TIMESTAMP)",
                r.getQuantity(),r.getSkuId(),r.getInventoryBucket(),r.getQuantity(),r.getSkuId());
        if (changed==0) return false;
        db.jdbc.update("INSERT INTO tb_stock_bucket_allocation(reservation_id,request_id,sku_id,bucket_id,quantity,released) VALUES(?,?,?,?,?,false)",
                r.getReservationId(),r.getRequestId(),r.getSkuId(),r.getInventoryBucket(),r.getQuantity());
        return true;
    }

    /** Returns true for a bucket allocation, including duplicate release. Caller owns the transaction. */
    public boolean restore(String reservation) {
        if (count==1) return false;
        Map<String,Object> a=db.one("SELECT * FROM tb_stock_bucket_allocation WHERE reservation_id=? FOR UPDATE",reservation);
        if (a==null) return false;
        if(Db.number(a,"bucket_id")==-1&&!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Vector release requires caller transaction");
        if (db.jdbc.update("UPDATE tb_stock_bucket_allocation SET released=true WHERE reservation_id=? AND released=false",reservation)==1) {
            if(Db.number(a,"bucket_id")==-1){
                Map<Integer,Integer> vector=allocation(a);
                for(Map.Entry<Integer,Integer> item:vector.entrySet())BusinessException.require(db.jdbc.update("UPDATE tb_ticket_stock_bucket SET remaining_stock=remaining_stock+?,version=version+1 WHERE sku_id=? AND bucket_id=? AND remaining_stock+?<=total_stock",item.getValue(),a.get("sku_id"),item.getKey(),item.getValue())==1,"跨桶库存回补异常");
                return true;
            }
            int changed=db.jdbc.update("UPDATE tb_ticket_stock_bucket SET remaining_stock=remaining_stock+?,version=version+1 "
                    + "WHERE sku_id=? AND bucket_id=? AND remaining_stock+?<=total_stock",
                    a.get("quantity"),a.get("sku_id"),a.get("bucket_id"),a.get("quantity"));
            BusinessException.require(changed==1,"库存桶回补异常");
        }
        return true;
    }
    /** Legacy headers need no item migration; vector headers must have a complete authoritative vector. */
    public Map<Integer,Integer> allocation(Map<String,Object> header){
        if(Db.number(header,"bucket_id")>=0)return Collections.singletonMap((int)Db.number(header,"bucket_id"),(int)Db.number(header,"quantity"));
        BusinessException.require(Db.number(header,"bucket_id")==-1,"库存分配协议异常");Map<Integer,Integer> vector=new TreeMap<>();long sum=0;
        for(Map<String,Object> row:db.jdbc.queryForList("SELECT bucket_id,quantity FROM tb_stock_bucket_allocation_item WHERE reservation_id=? ORDER BY bucket_id",header.get("reservation_id"))){int b=(int)Db.number(row,"bucket_id"),n=(int)Db.number(row,"quantity");BusinessException.require(b>=0&&b<16&&n>0&&vector.put(b,n)==null,"库存明细异常");sum+=n;}
        BusinessException.require(sum==Db.number(header,"quantity")&&!vector.isEmpty(),"库存分配明细不完整");return vector;
    }
    public String vectorJson(Map<String,Object> header){List<Integer> vector=new ArrayList<>(Collections.nCopies(16,0));allocation(header).forEach(vector::set);return db.json(vector);}
}
