package com.ticketing.outbox;

import com.ticketing.infrastructure.Db;
import com.ticketing.repository.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Service
public class OutboxService {
    private final Db db;
    private final OutboxRepository outbox;

    public OutboxService(Db db, OutboxRepository outbox) {
        this.db = db;
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String order, String type) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order);
        payload.put("type", type);
        payload.put("messageKey", type + ":" + order);
        if ("AUTO_PAYMENT".equals(type)) {
            // Deterministic — same as AutoPaymentService.paymentRequestId(orderNo); order_no == id on insert.
            payload.put("paymentRequestId", "AUTO_PAY:" + order);
        }
        outbox.appendPending(Db.id(), order, type + ":" + order, db.json(payload));
    }
}
