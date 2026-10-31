package com.ticketing.notification;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import com.ticketing.order.TicketOrder;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class OrderNotificationServiceTest {
    Db db;TransactionTemplate tx;OrderNotificationService notices;TicketOrder order;
    @BeforeEach void setup(){
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:notice-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V109__order_notification.sql")).execute(ds);
        db=new Db(new JdbcTemplate(ds),new ObjectMapper());tx=new TransactionTemplate(new DataSourceTransactionManager(ds));notices=new OrderNotificationService(db);
        order=new TicketOrder();order.setId("order");order.setUserId(2);order.setQuantity(1);
    }
    @Test void issuanceRollbackCannotCreateAnOrphanNotice(){
        tx.executeWithoutResult(s->{notices.enqueue(order);s.setRollbackOnly();});assertTrue(notices.list(2,false).isEmpty());
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_order_notification",Integer.class));
    }
    @Test void deliveryAndRecipientAcknowledgementAreIndependentIdempotentStates(){
        tx.executeWithoutResult(s->notices.enqueue(order));assertTrue(notices.list(2,true).isEmpty());
        tx.executeWithoutResult(s->notices.deliver("order"));tx.executeWithoutResult(s->notices.deliver("order"));
        Map<String,Object> notice=notices.list(2,true).get(0);String id=notice.get("id").toString();
        assertThrows(BusinessException.class,()->tx.execute(s->notices.read(id,3)));
        Map<String,Object> one=tx.execute(s->notices.read(id,2)),two=tx.execute(s->notices.read(id,2));
        assertEquals("READ",one.get("status"));assertEquals(one.get("read_at"),two.get("read_at"));assertTrue(notices.list(2,true).isEmpty());
        assertEquals(1,db.jdbc.queryForObject("SELECT delivery_attempts FROM tb_order_notification",Integer.class));
    }
    @Test void failedConsumeRollsBackDeliveryAndCanRetry(){
        tx.executeWithoutResult(s->notices.enqueue(order));
        assertThrows(IllegalStateException.class,()->tx.execute(s->{notices.deliver("order");throw new IllegalStateException("consume failed");}));
        assertEquals("PENDING",db.one("SELECT status FROM tb_order_notification").get("status"));
        tx.executeWithoutResult(s->notices.deliver("order"));assertEquals(1,notices.list(2,true).size());
    }
}
