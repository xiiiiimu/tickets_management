package com.ticketing.repository.jdbc;

import com.ticketing.infrastructure.Db;
import com.ticketing.repository.IssuedTicketRepository;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIssuedTicketRepository implements IssuedTicketRepository {

    private final Db db;

    public JdbcIssuedTicketRepository(Db db) {
        this.db = db;
    }

    @Override
    public void insert(String id, String orderId, int sequenceNo) {
        db.jdbc.update(
                "INSERT INTO tb_issued_ticket(id,order_id,sequence_no) VALUES(?,?,?)",
                id, orderId, sequenceNo);
    }

    @Override
    public void deleteByOrderId(String orderId) {
        db.jdbc.update("DELETE FROM tb_issued_ticket WHERE order_id=?", orderId);
    }
}
