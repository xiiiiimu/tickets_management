package com.ticketing.integration;

import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.InventoryService;
import com.ticketing.ticket.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.Timestamp;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated MySQL + Redis, reusing existing Armory fixtures; never an external payment/AI call. */
@EnabledIfSystemProperty(named="ticketing.attempts.integration",matches="true")
@TestPropertySource(properties={"ticketing.reservations-v2.attempts.enabled=true","ticketing.reservations-v2.dispatch.isolated=true"})
class DurableAttemptLifecycleIT extends ReservationV2IT {
    @Autowired ReservationAttemptStore attempts;
    @Autowired ReservationV2Controller controller;
    @Autowired TicketController legacyController;
    @Autowired ReservationDispatchScheduler control;
    @org.junit.jupiter.api.BeforeEach void manualClaimsOnly(){assertTrue(control.isIsolated());control.close();}
    void isolated(){
        String url=System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(url);assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13306/credit_risk_controlled_"));
    }
    Map<String,Object> reliable(ReserveRequest request){
        Map<String,Object> result;
        try{
            String token="phase-b-"+Db.id();Map<String,String> identity=new HashMap<>();identity.put("id",request.getUserId().toString());identity.put("role","USER");redis.opsForHash().putAll("login:token:"+token,identity);redis.expire("login:token:"+token,java.time.Duration.ofMinutes(5));
            org.springframework.test.web.servlet.MockMvc http=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new com.ticketing.common.util.RefreshTokenInterceptor(redis),new com.ticketing.common.util.LoginInterceptor()).build();
            com.fasterxml.jackson.databind.ObjectMapper mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            org.springframework.test.web.servlet.MvcResult response=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/tickets/reservations").header("authorization",token).contentType("application/json").content(mapper.writeValueAsString(request))).andReturn();
            assertEquals(202,response.getResponse().getStatus());
            @SuppressWarnings("unchecked") Map<String,Object> wire=mapper.readValue(response.getResponse().getContentAsString(),Map.class);
            @SuppressWarnings("unchecked") Map<String,Object> body=(Map<String,Object>)wire.get("data");result=body;
        }catch(Exception error){throw new AssertionError(error);}
        assertEquals(true,result.get("reliableAccepted"));assertEquals("WAITING",result.get("state"));assertNotNull(result.get("attemptId"));
        assertEquals("WAITING",db.one("SELECT state FROM tb_reservation_job WHERE id=?",result.get("attemptId")).get("state"));
        return result;
    }
    @Test void realHoldCompletesAfterOldTenAndThirtySecondDeadlines(){
        isolated();
        try{
            Sale sale=sale("PASS");
            for(long age:new long[]{11000,31000}){
                ReserveRequest request=request(sale);Map<String,Object> receipt=reliable(request);Map<String,Object> claimed=attempts.claim();assertNotNull(claimed);assertEquals(receipt.get("attemptId"),claimed.get("id"));
                // Model a long-lived PROCESSING with a renewed lease using explicit timestamps,
                // not sleeps. The Armory fixture's existing sale-open wait is unchanged.
                Timestamp started=new Timestamp(System.currentTimeMillis()-age);
                db.jdbc.update("UPDATE tb_reservation_job SET created_at=?,processing_started_at=?,waiting_deadline=?,queue_expires_at=? WHERE id=?",started,started,new Timestamp(started.getTime()+5000),new Timestamp(started.getTime()+5000),claimed.get("id"));
                claimed=queue.get(claimed.get("id").toString());transactions.prepare(claimed);
                com.ticketing.common.dto.UserDTO identity=new com.ticketing.common.dto.UserDTO();identity.setId(request.getUserId());com.ticketing.common.context.UserHolder.saveUser(identity);
                try{assertEquals(409,assertThrows(com.ticketing.common.BusinessException.class,()->legacyController.reserve(request,new org.springframework.mock.web.MockHttpServletRequest())).status);}
                finally{com.ticketing.common.context.UserHolder.removeUser();}
                ReflectionTestUtils.invokeMethod(service,"process",claimed);
                Map<String,Object> done=queue.byRequest(request.getRequestId());assertEquals("RESERVED",done.get("state"));assertNull(done.get("lease_token"));
                Map<String,Object> order=db.one("SELECT status,expires_at FROM tb_ticket_order WHERE id=?",done.get("order_id"));assertEquals("WAITING_PAYMENT",order.get("status"));
                Map<Object,Object> marker=redis.opsForHash().entries(InventoryService.prefix(sale.sku)+"reservation:"+done.get("reservation_id"));
                long held=Long.parseLong(marker.get("heldAt").toString()),expiry=Long.parseLong(marker.get("expiresAt").toString());assertEquals(600000,expiry-held);assertEquals(expiry,((Timestamp)order.get("expires_at")).getTime());
                Map<String,Object> again=service.registerAttempt(request,"attempt-ip-"+request.getUserId());assertEquals(done.get("id"),again.get("attemptId"));assertEquals(false,again.get("newSubmission"));
            }
            assertEquals(98,stock(sale));assertEquals(98,redisStock(sale));
        }catch(Exception error){throw new AssertionError(error);}
    }
    @Test void timeoutIsFinalAndLateTaskCannotCreateAnIntentOrHold(){
        isolated();
        try{
            Sale sale=sale("PASS");ReserveRequest request=request(sale);Map<String,Object> receipt=reliable(request);
            Map<String,Object> old=queue.byRequest(request.getRequestId());Timestamp deadline=new Timestamp(System.currentTimeMillis()-1);
            db.jdbc.update("UPDATE tb_reservation_job SET waiting_deadline=?,queue_expires_at=? WHERE id=?",deadline,deadline,receipt.get("attemptId"));
            attempts.expireWaiting();assertNull(attempts.claim());ReflectionTestUtils.invokeMethod(service,"process",old);
            assertEquals("QUEUE_TIMEOUT",queue.byRequest(request.getRequestId()).get("state"));assertEquals(100,stock(sale));assertEquals(100,redisStock(sale));
            assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reserve_request WHERE request_id=?",Integer.class,request.getRequestId()));
            assertEquals(receipt.get("attemptId"),service.registerAttempt(request,"attempt-ip-"+request.getUserId()).get("attemptId"));
        }catch(Exception error){throw new AssertionError(error);}
    }
    @Test void mysqlFailureAfterLuaCompensatesOnceForProcessingAttempt(){
        isolated();
        try{
            Sale sale=sale("PASS");ReserveRequest request=request(sale);
            db.jdbc.update("INSERT INTO tb_user_purchase(user_id,sku_id,quantity) VALUES(?,?,2)",request.getUserId(),sale.sku);
            reliable(request);Map<String,Object> claimed=attempts.claim();ReflectionTestUtils.invokeMethod(service,"process",claimed);
            Map<String,Object> failed=queue.byRequest(request.getRequestId());assertEquals("FAILED",failed.get("state"));assertEquals("PURCHASE_LIMIT",failed.get("result_code"));
            assertEquals(100,stock(sale));assertEquals(100,redisStock(sale));assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_ticket_order WHERE id=?",Integer.class,failed.get("order_id")));
            assertEquals("FAILED",db.one("SELECT status FROM tb_reserve_request WHERE request_id=?",request.getRequestId()).get("status"));
            transactions.abort(claimed,"PURCHASE_LIMIT");assertEquals(100,redisStock(sale));assertEquals("ABORTED",redis.opsForHash().get(InventoryService.prefix(sale.sku)+"reservation:"+failed.get("reservation_id"),"state"));
        }catch(Exception error){throw new AssertionError(error);}
    }
}
