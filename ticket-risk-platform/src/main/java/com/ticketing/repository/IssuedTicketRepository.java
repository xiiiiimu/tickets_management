package com.ticketing.repository;

/**
 * Issued tickets on {@code tb_issued_ticket}.
 */
public interface IssuedTicketRepository {

    void insert(String id, String orderId, int sequenceNo);

    void deleteByOrderId(String orderId);
}
