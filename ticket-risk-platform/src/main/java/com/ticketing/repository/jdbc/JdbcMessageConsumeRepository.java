package com.ticketing.repository.jdbc;

import com.ticketing.infrastructure.Db;
import com.ticketing.repository.MessageConsumeRepository;
import org.springframework.stereotype.Repository;

import java.util.Map;

@Repository
public class JdbcMessageConsumeRepository implements MessageConsumeRepository {

    private final Db db;

    public JdbcMessageConsumeRepository(Db db) {
        this.db = db;
    }

    @Override
    public Map<String, Object> findByBusinessKey(String businessKey) {
        return db.one(
                "SELECT * FROM tb_message_consume_record WHERE consumer_group='ticket-events' AND business_key=?",
                businessKey);
    }

    @Override
    public void insertProcessing(String messageKey, String businessKey) {
        db.jdbc.update(
                "INSERT INTO tb_message_consume_record(consumer_group,message_key,business_key,status) VALUES('ticket-events',?,?,'PROCESSING')",
                messageKey, businessKey);
    }

    @Override
    public void markDone(String messageKey) {
        db.jdbc.update(
                "UPDATE tb_message_consume_record SET status='DONE' WHERE consumer_group='ticket-events' AND message_key=?",
                messageKey);
    }
}
