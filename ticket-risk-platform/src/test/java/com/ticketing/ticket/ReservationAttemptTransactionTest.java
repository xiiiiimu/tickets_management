package com.ticketing.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.InventoryService;
import com.ticketing.outbox.OutboxService;
import com.ticketing.repository.*;
import com.ticketing.risk.*;
import com.ticketing.sale.TicketSaleArmory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.Timestamp;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real local transaction/state; inventory collaborators mocked. Real Lua is covered separately. */
class ReservationAttemptTransactionTest {
    Db db;ReservationDispatch queue;ReservationV2Transactions service;ReservationIntent intent;TicketSku sku;Map<String,Object> job;
    InventoryService inventory=mock(InventoryService.class);ReservationIntentRepository intents=mock(ReservationIntentRepository.class);
    TicketInventoryRepository skus=mock(TicketInventoryRepository.class);UserPurchaseRepository purchases=mock(UserPurchaseRepository.class);
    ReservationRepository reservations=mock(ReservationRepository.class);OrderRepository orders=mock(OrderRepository.class);
    @BeforeEach void setup(){
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:attempt-tx-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        db=new Db(new JdbcTemplate(ds),new ObjectMapper());DataSourceTransactionManager manager=new DataSourceTransactionManager(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V106__reservation_dispatch.sql"),new ClassPathResource("db/migration/V110__durable_attempt_lifecycle.sql")).execute(ds);
        RiskSubjectFence fences=mock(RiskSubjectFence.class);queue=new ReservationDispatch(db,fences,manager);
        TicketSaleArmory armory=mock(TicketSaleArmory.class);sku=new TicketSku();sku.setPrice(java.math.BigDecimal.TEN);sku.setPurchaseLimit(2);sku.setSaleEndTime(new Timestamp(System.currentTimeMillis()+3600000));
        when(armory.runtimeSku(anyString(),anyString(),anyLong())).thenReturn(sku);when(fences.lockAndCheck(anyLong(),anyString(),anyString())).thenReturn(true);
        service=new ReservationV2Transactions(db,armory,queue,intents,skus,purchases,reservations,orders,inventory,fences,mock(RiskDecisionService.class),mock(OutboxService.class),new ObjectMapper(),new SimpleMeterRegistry(),manager);
        intent=new ReservationIntent();intent.setRequestId("r");intent.setFingerprint("fp");intent.setReservationId("res");intent.setOrderId("order");intent.setUserId(1);intent.setSkuId("sku");intent.setQuantity(1);intent.setDeviceId("device");intent.setIp("ip");intent.setStatus("REQUESTED");
        when(intents.findByRequestId("r")).thenReturn(intent);when(intents.findByRequestIdForUpdate("r")).thenReturn(intent);
        when(skus.tryDecreaseStockForReservation(intent)).thenReturn(true);when(purchases.tryIncreaseWithinLimit(1L,"sku",1,2)).thenReturn(true);
        when(inventory.reserveRuntime(eq(intent),eq(1L),anyLong())).thenAnswer(a->{assertEquals(sku.getSaleEndTime().getTime(),(long)a.getArgument(2));intent.setExpiresAt(new Timestamp(System.currentTimeMillis()+600000));return 100L;});
    }
    void seed(long age,int protocol){
        Timestamp created=new Timestamp(System.currentTimeMillis()-age),oldDeadline=new Timestamp(created.getTime()+5000);
        db.jdbc.update("INSERT INTO tb_reservation_job(id,request_id,fingerprint,user_id,session_id,sku_id,quantity,device_id,ip,sale_version,state,decision,admission,lease_token,lease_until,queue_expires_at,reservation_id,order_id,attempt_protocol,waiting_deadline,processing_started_at) VALUES('job','r','fp',1,'session','sku',1,'device','ip',1,?,'PASS','{}','token',?,?,'res','order',?,?,?)",protocol==3?"PROCESSING":"CLAIMED",new Timestamp(System.currentTimeMillis()+15000),oldDeadline,protocol,oldDeadline,created);
        job=queue.get("job");
    }
    @Test void processingOverTenSecondsCanComplete(){seed(11000,3);service.prepare(job);service.reserve(job);assertEquals("RESERVED",queue.get("job").get("state"));}
    @Test void processingOverThirtySecondsCanCompleteAndLuaGetsSaleEndNotQueueDeadline(){seed(31000,3);service.prepare(job);service.reserve(job);assertEquals("RESERVED",queue.get("job").get("state"));verify(inventory).reserveRuntime(intent,1,sku.getSaleEndTime().getTime());}
    @Test void queueTimeoutCannotExecuteInventoryEvenWithLateTask(){seed(31000,3);db.jdbc.update("UPDATE tb_reservation_job SET state='QUEUE_TIMEOUT',lease_token=NULL WHERE id='job'");service.prepare(job);service.reserve(job);verifyNoInteractions(inventory,reservations,orders);assertEquals("QUEUE_TIMEOUT",queue.get("job").get("state"));}
    @Test void staleProcessingTokenCannotReserveOrAbortCurrentOwner(){seed(1000,3);db.jdbc.update("UPDATE tb_reservation_job SET lease_token='new-owner' WHERE id='job'");service.prepare(job);service.reserve(job);service.abort(job,"EXECUTION_UNAVAILABLE");verifyNoInteractions(inventory,reservations,orders);assertEquals("PROCESSING",queue.get("job").get("state"));}
    @Test void oldProtocolStillRejectsExpiredQueueDeadline(){seed(31000,2);service.prepare(job);assertEquals("QUEUE_EXPIRED",assertThrows(ReservationV2Transactions.Rejected.class,()->service.reserve(job)).code);verify(inventory,never()).reserveRuntime(any(),anyLong(),anyLong());}
    @Test void processingSaleWindowEndIsNotMisreportedAsQueueTimeout(){seed(31000,3);when(inventory.reserveRuntime(eq(intent),eq(1L),anyLong())).thenReturn(-10L);service.prepare(job);assertEquals("SALE_WINDOW_CLOSED",assertThrows(ReservationV2Transactions.Rejected.class,()->service.reserve(job)).code);}
    @Test void mysqlFailureAfterRedisUsesExistingAbortAndDoesNotMarkReserved(){seed(31000,3);when(skus.tryDecreaseStockForReservation(intent)).thenReturn(false);service.prepare(job);assertEquals("MYSQL_STOCK_REJECTED",assertThrows(ReservationV2Transactions.Rejected.class,()->service.reserve(job)).code);assertEquals("PROCESSING",queue.get("job").get("state"));service.abort(job,"MYSQL_STOCK_REJECTED");verify(inventory).abortRuntime(intent,1);assertEquals("FAILED",queue.get("job").get("state"));verifyNoInteractions(reservations,orders);}
}
