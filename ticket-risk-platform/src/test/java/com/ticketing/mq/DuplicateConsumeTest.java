package com.ticketing.mq;

import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.InventoryService;
import com.ticketing.order.*;
import com.ticketing.outbox.OutboxService;
import com.ticketing.repository.jdbc.*;
import com.ticketing.risk.RiskDecisionService;
import com.ticketing.workflow.service.WorkflowExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DuplicateConsumeTest {
    Db db;
    TransactionTemplate tx;
    TicketEventConsumer consumer;
    WorkflowExecutionService workflow;

    @BeforeEach
    void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        db = new Db(new JdbcTemplate(ds), new ObjectMapper());
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.jdbc.execute("CREATE TABLE tb_ticket_order(id VARCHAR PRIMARY KEY,status VARCHAR,version INT,quantity INT,trace_id VARCHAR,updated_at TIMESTAMP)");
        db.jdbc.execute("CREATE TABLE tb_message_consume_record(consumer_group VARCHAR,message_key VARCHAR,business_key VARCHAR,status VARCHAR,PRIMARY KEY(consumer_group,message_key),UNIQUE(consumer_group,business_key))");
        db.jdbc.execute("CREATE TABLE tb_issued_ticket(id VARCHAR PRIMARY KEY,order_id VARCHAR,sequence_no INT,UNIQUE(order_id,sequence_no))");
        db.jdbc.update("INSERT INTO tb_ticket_order VALUES('o','PAID',0,2,'trace',CURRENT_TIMESTAMP)");
        JdbcOrderRepository orderRepo = new JdbcOrderRepository(db);
        OrderStateMachine machine = new OrderStateMachine(orderRepo);
        workflow = mock(WorkflowExecutionService.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        OrderService orders = new OrderService(orderRepo, new JdbcReservationRepository(db), new JdbcTicketInventoryRepository(db),
                new JdbcUserPurchaseRepository(db), machine, mock(OutboxService.class), workflow, metrics);
        consumer = new TicketEventConsumer(new JdbcMessageConsumeRepository(db), new JdbcIssuedTicketRepository(db),
                orders, machine, mock(RiskDecisionService.class),
                mock(InventoryService.class), mock(com.ticketing.payment.AutoPaymentService.class), workflow, metrics,
                new DataSourceTransactionManager(ds), mock(OutboxService.class));
    }

    private void consume() {
        consumer.consume("ISSUE:o", "o", "ISSUE");
    }

    @Test
    void duplicateDeliveryIssuesOnlyOnce() {
        consume();
        consume();
        assertEquals(2, db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_issued_ticket", Integer.class));
        assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_message_consume_record", Integer.class));
        assertEquals(1, db.jdbc.queryForObject("SELECT version FROM tb_ticket_order", Integer.class));
    }

    @Test
    void failedConsumerRollsBackRecordAndState() {
        doThrow(new IllegalStateException("checkpoint unavailable")).when(workflow).record(anyString(), anyString(), anyString(), any());
        assertThrows(IllegalStateException.class, this::consume);
        assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_message_consume_record", Integer.class));
        assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_issued_ticket", Integer.class));
        assertEquals("PAID", db.one("SELECT status FROM tb_ticket_order").get("status"));
        reset(workflow);
        consume();
        assertEquals("ISSUED", db.one("SELECT status FROM tb_ticket_order").get("status"));
    }
}
