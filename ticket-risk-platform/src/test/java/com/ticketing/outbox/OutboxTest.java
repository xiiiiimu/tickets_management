package com.ticketing.outbox;

import com.ticketing.infrastructure.Db;
import com.ticketing.repository.jdbc.JdbcOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.client.producer.*;
import org.springframework.messaging.Message;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxTest {
    Db db;
    TransactionTemplate tx;
    OutboxService service;
    RocketMQTemplate mq;
    OutboxPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        db = new Db(new JdbcTemplate(ds), new ObjectMapper());
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.jdbc.execute("CREATE TABLE tb_outbox_message(id VARCHAR PRIMARY KEY,aggregate_type VARCHAR,aggregate_id VARCHAR,topic VARCHAR,message_key VARCHAR UNIQUE,payload VARCHAR,status VARCHAR,retry_count INT DEFAULT 0,next_retry_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,lease_token VARCHAR,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        JdbcOutboxRepository outboxRepo = new JdbcOutboxRepository(db);
        service = new OutboxService(db, outboxRepo);
        mq = mock(RocketMQTemplate.class);
        ObjectProvider<RocketMQTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mq);
        publisher = new OutboxPublisher(outboxRepo, provider, 2, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test
    void businessRollbackRemovesOutbox() {
        assertThrows(IllegalStateException.class, () -> tx.execute(s -> {
            service.append("o1", "RISK_CHECK");
            throw new IllegalStateException("business write failed");
        }));
        assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_outbox_message", Integer.class));
    }

    @Test
    void retriesBecomeFailedAtThreshold() {
        tx.execute(s -> {
            service.append("o1", "RISK_CHECK");
            return null;
        });
        when(mq.syncSend(anyString(), any(Message.class), eq(3000L))).thenThrow(new IllegalStateException("offline"));
        publisher.publish();
        assertEquals("RETRY", db.one("SELECT status FROM tb_outbox_message").get("status"));
        db.jdbc.update("UPDATE tb_outbox_message SET next_retry_time=CURRENT_TIMESTAMP");
        publisher.publish();
        assertEquals("FAILED", db.one("SELECT status FROM tb_outbox_message").get("status"));
    }

    @Test
    void sentRowsAreNotPublishedAgain() {
        tx.execute(s -> {
            service.append("o1", "RISK_CHECK");
            return null;
        });
        SendResult result = new SendResult();
        result.setSendStatus(SendStatus.SEND_OK);
        when(mq.syncSend(anyString(), any(Message.class), eq(3000L))).thenReturn(result);
        publisher.publish();
        publisher.publish();
        verify(mq, times(1)).syncSend(anyString(), any(Message.class), eq(3000L));
        assertEquals("SENT", db.one("SELECT status FROM tb_outbox_message").get("status"));
    }
}
