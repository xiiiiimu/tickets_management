package com.ticketing.outbox;

import com.ticketing.infrastructure.Db;
import com.ticketing.repository.OutboxRepository;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.sql.Timestamp;

@Component
public class OutboxPublisher {
    private final OutboxRepository outbox;
    private final ObjectProvider<RocketMQTemplate> mq;
    private final int maxRetries;
    private final MeterRegistry metrics;

    public OutboxPublisher(OutboxRepository outbox, ObjectProvider<RocketMQTemplate> mq,
                           @Value("${ticketing.outbox.max-retries:12}") int maxRetries, MeterRegistry metrics) {
        this.outbox = outbox;
        this.mq = mq;
        this.maxRetries = maxRetries;
        this.metrics = metrics;
    }

    public void publish() {
        List<Map<String, Object>> rows = outbox.findPublishable(200);
        for (Map<String, Object> row : rows) {
            String token = Db.id();
            if (!outbox.tryClaim(row.get("id").toString(), token, new Timestamp(System.currentTimeMillis() + 60000)))
                continue;
            try {
                RocketMQTemplate transport = mq.getIfAvailable();
                if (transport == null) throw new IllegalStateException("MQ unavailable");
                org.apache.rocketmq.client.producer.SendResult sent = transport.syncSend(
                        row.get("topic").toString(),
                        MessageBuilder.withPayload(row.get("payload").toString()).setHeader("KEYS", row.get("message_key")).build(),
                        3000);
                if (sent.getSendStatus() != org.apache.rocketmq.client.producer.SendStatus.SEND_OK)
                    throw new IllegalStateException("Broker did not durably acknowledge");
                outbox.markSent(row.get("id").toString(), token);
                metrics.counter("mq.outbox.publish.success").increment();
            } catch (Exception e) {
                int attempts = ((Number) row.get("retry_count")).intValue() + 1;
                long delay = Math.min(300000, 1000L * (1L << Math.min(attempts, 8)));
                String next = attempts >= maxRetries ? "FAILED" : "RETRY";
                outbox.markRetryOrFailed(row.get("id").toString(), token,
                        next, attempts, new Timestamp(System.currentTimeMillis() + delay));
                metrics.counter(next.equals("FAILED") ? "mq.outbox.publish.failed" : "mq.outbox.publish.retry").increment();
            }
        }
    }

    public void compensate() {
        outbox.requeueUnconsumedSent(new Timestamp(System.currentTimeMillis() - 300000));
        outbox.requeueOldFailed(new Timestamp(System.currentTimeMillis() - 3600000));
    }
}
