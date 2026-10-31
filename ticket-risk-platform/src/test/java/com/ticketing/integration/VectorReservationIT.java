package com.ticketing.integration;

import com.ticketing.infrastructure.Db;
import com.ticketing.ticket.*;
import com.ticketing.inventory.InventoryService;
import com.ticketing.order.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.Timestamp;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@TestPropertySource(properties={"ticketing.reservations-v2.attempts.ordered-lua=true","ticketing.reservations-v2.attempts.quick-review=true","ticketing.inventory.vector-reservations=true"})
class VectorReservationIT extends QuickReviewAttemptIT {
    @org.springframework.beans.factory.annotation.Autowired OrderService orderService;
    @org.springframework.beans.factory.annotation.Autowired ReservationSoldOutSweep sweeper;
    Sale vectorSale(int total,int limit,int single)throws Exception{
        Sale s=new Sale();s.start=(System.currentTimeMillis()/1000+15)*1000;
        db.jdbc.update("INSERT INTO tb_event(id,name,venue,status) VALUES(?,'Vector fixture','test','ON_SALE')",s.event);db.jdbc.update("INSERT INTO tb_event_session VALUES(?,?,?)",s.session,s.event,new Timestamp(s.start+86400000));
        db.jdbc.update("INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,sale_start_time,sale_end_time,purchase_limit,single_purchase_limit,status,version) VALUES(?,?,'vector',10,?,?, ?,?,?,?,'ON_SALE',0)",s.sku,s.session,total,total,new Timestamp(s.start),new Timestamp(s.start+3600000),limit,single);
        armory.assemble(s.session,0,"assemble-"+s.session,1);long wait=s.start-System.currentTimeMillis()+20;if(wait>0)Thread.sleep(wait);return s;
    }
    ReserveRequest quantity(Sale s,int n){ReserveRequest r=request(s);r.setQuantity(n);return r;}
    @Test void fragmentedStockCombinesAndArmoryRecoveryKeepsVectorThenCancelReleasesOnce()throws Exception{
        isolated();Sale s=vectorSale(16,16,8);ReserveRequest r=quantity(s,5);Map<String,Object> held=run(r);assertEquals("RESERVED",held.get("state"));assertEquals(11,stock(s));assertEquals(11,redisStock(s));
        assertEquals(5,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_stock_bucket_allocation_item WHERE reservation_id=?",Integer.class,held.get("reservation_id")));assertEquals(-1,db.jdbc.queryForObject("SELECT bucket_id FROM tb_stock_bucket_allocation WHERE reservation_id=?",Integer.class,held.get("reservation_id")));
        String key=InventoryService.prefix(s.sku)+"reservation:"+held.get("reservation_id");String deadline=redis.opsForHash().get(key,"expiresAt").toString();redis.delete(key);redis.delete(InventoryService.prefix(s.sku)+"stock:bucket:0");armory.recover(s.session,1,"recover-"+s.session,1);
        assertEquals("3",redis.opsForHash().get(key,"protocolVersion"));assertEquals(deadline,redis.opsForHash().get(key,"expiresAt"));assertEquals(11,redisStock(s));
        orderService.cancel(held.get("order_id").toString(),r.getUserId());inventory.release(s.sku,r.getUserId(),held.get("reservation_id").toString());inventory.release(s.sku,r.getUserId(),held.get("reservation_id").toString());assertEquals(16,stock(s));assertEquals(16,redisStock(s));assertEquals(0,db.jdbc.queryForObject("SELECT quantity FROM tb_user_purchase WHERE user_id=? AND sku_id=?",Integer.class,r.getUserId(),s.sku));
    }
    @Test void partialRedisOrMysqlFailureNeverLeavesPartialAllocationAndLateRetriesAreTombstoned()throws Exception{
        isolated();Sale s=vectorSale(16,16,8);ReserveRequest r=quantity(s,5);quota(r,16);Map<String,Object> failed=run(r);assertEquals("FAILED",failed.get("state"));assertEquals(16,stock(s));assertEquals(16,redisStock(s));assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_stock_bucket_allocation_item WHERE reservation_id=?",Integer.class,failed.get("reservation_id")));
        com.ticketing.repository.ReservationIntentRepository intents=(com.ticketing.repository.ReservationIntentRepository)ReflectionTestUtils.getField(transactions,"intents");ReservationIntent intent=intents.findByRequestId(r.getRequestId());assertEquals(-5,inventory.reserveRuntime(intent,1,System.currentTimeMillis()+600000));assertEquals(16,redisStock(s));
        quota(r,0); // Remove the deliberately inconsistent quota fault before testing authoritative recovery.
        ReserveRequest tooLarge=quantity(s,9);Map<String,Object> denied=run(tooLarge);assertEquals("SINGLE_PURCHASE_LIMIT",denied.get("result_code"));assertEquals(16,redisStock(s));
        String missing=InventoryService.prefix(s.sku)+"stock:bucket:15";redis.delete(missing);ReserveRequest unavailable=quantity(s,2);assertThrows(com.ticketing.common.BusinessException.class,()->service.registerAttempt(unavailable,"127.0.0.1"));assertFalse(redis.hasKey(missing));assertEquals(16,stock(s));armory.recover(s.session,1,"recover-"+s.session,1);assertEquals(16,redisStock(s));
    }
    @Test void confirmedSoldOutTerminatesWaitingAndCannotReviveAfterStockRelease()throws Exception{
        isolated();Sale s=vectorSale(16,16,16);ReserveRequest first=quantity(s,16);Map<String,Object> held=run(first);assertEquals("RESERVED",held.get("state"));assertEquals(0,redisStock(s));
        ReserveRequest waiting=request(s);Map<String,Object> receipt=reliable(waiting);sweeper.signal(queue.byRequest(waiting.getRequestId()));sweeper.run();Map<String,Object> sold=queue.byRequest(waiting.getRequestId());assertEquals("SOLD_OUT",sold.get("state"));assertNull(sold.get("lease_token"));
        orderService.cancel(held.get("order_id").toString(),first.getUserId());inventory.release(s.sku,first.getUserId(),held.get("reservation_id").toString());assertEquals(16,redisStock(s));assertEquals(receipt.get("attemptId"),service.registerAttempt(waiting,"127.0.0.1").get("attemptId"));assertEquals("SOLD_OUT",queue.byRequest(waiting.getRequestId()).get("state"));
        Map<String,Object> retry=run(next(waiting));assertEquals("RESERVED",retry.get("state"));assertEquals(15,stock(s));assertEquals(15,redisStock(s));
    }
    @Test void stockInsufficientForQuantityIsDistinctFromEmptyAndDoesNotPartiallyDeduct()throws Exception{
        isolated();Sale s=vectorSale(3,10,10);Map<String,Object> denied=run(quantity(s,4));assertEquals("INSUFFICIENT_STOCK",denied.get("result_code"));assertEquals(3,stock(s));assertEquals(3,redisStock(s));assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id=?",Integer.class,s.sku));
    }
}
