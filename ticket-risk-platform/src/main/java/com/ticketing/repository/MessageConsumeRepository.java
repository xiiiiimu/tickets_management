package com.ticketing.repository;

import java.util.Map;

/**
 * Consume idempotency on {@code tb_message_consume_record}.
 * Unique constraints remain the final concurrency protection.
 */
public interface MessageConsumeRepository {

    Map<String, Object> findByBusinessKey(String businessKey);

    void insertProcessing(String messageKey, String businessKey);

    void markDone(String messageKey);
}
