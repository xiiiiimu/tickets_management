package com.ticketing.ticket;

import com.ticketing.infrastructure.Db;
import com.ticketing.common.BusinessException;
import com.ticketing.repository.ReservationIntentRepository;
import com.ticketing.repository.TicketInventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.slf4j.MDC;
import org.springframework.util.DigestUtils;
import java.util.Arrays;
import com.ticketing.config.RequestTimings;

@Service
public class ReservationIntentService {
    private final Db db;
    private final ReservationIntentRepository intents;
    private final TicketInventoryRepository inventory;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.ticketing.sale.TicketSaleArmory armory;

    public ReservationIntentService(Db db, ReservationIntentRepository intents, TicketInventoryRepository inventory) {
        this.db = db;
        this.intents = intents;
        this.inventory = inventory;
    }

    public String fingerprint(ReserveRequest r) {
        return DigestUtils.md5DigestAsHex(db.json(Arrays.asList(r.getUserId(), r.getEventSessionId(), r.getTicketSkuId(), r.getQuantity(), r.getDeviceId())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Creates REQUESTED intent. Idempotency is by {@code request_id} PK
     * ({@code INSERT ... ON DUPLICATE KEY}) — not by locking the SKU row.
     * Stock correctness remains Redis Lua + MySQL CAS in {@link ReservationTransactionService}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ReservationIntent create(ReserveRequest r, String ip) {
        ReservationIntent prior = RequestTimings.measure("intents.lookup", () -> intents.findByRequestId(r.getRequestId()));
        if (prior != null) {
            BusinessException.require(prior.getFingerprint().equals(fingerprint(r)), "请求标识已用于其他参数");
            return prior;
        }
        if (armory != null) armory.assertCanCreate(r.getEventSessionId(),r.getTicketSkuId());
        // Existence / sellability: plain read. Do not FOR UPDATE the hot SKU row here.
        com.ticketing.ticket.TicketSku sku = RequestTimings.measure("intents.sku_read", () -> inventory.findById(r.getTicketSkuId()));
        RequestTimings.measure("intents.check", () -> BusinessException.require(sku != null, "票档不存在"));
        RequestTimings.measure("intents.insert", () -> intents.insertRequested(r.getRequestId(), fingerprint(r), Db.id(), Db.id(), r.getUserId(), r.getTicketSkuId(), r.getQuantity(), r.getDeviceId(), ip, MDC.get("traceId")));
        ReservationIntent row = RequestTimings.measure("intents.readback", () -> intents.findByRequestId(r.getRequestId()));
        RequestTimings.measure("intents.check", () -> {
            BusinessException.require(row != null, "预占意图创建失败");
            BusinessException.require(row.getFingerprint().equals(fingerprint(r)), "请求标识已用于其他参数");
        });
        return row;
    }
}
