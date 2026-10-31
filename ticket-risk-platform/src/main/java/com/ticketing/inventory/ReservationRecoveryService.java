package com.ticketing.inventory;

import com.ticketing.repository.ReservationIntentRepository;
import com.ticketing.ticket.ReservationIntent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationRecoveryService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.ticketing.ticket.ReservationRuntimeRecovery runtime;
    private final ReservationIntentRepository intents;
    private final InventoryService inventory;

    public ReservationRecoveryService(ReservationIntentRepository intents, InventoryService inventory) {
        this.intents = intents;
        this.inventory = inventory;
    }

    @Transactional
    public void recover(String request) {
        java.util.Map<String,Object> job=runtime==null?null:runtime.lock(request);
        if(job!=null&&runtime.active(job))return;
        ReservationIntent r = intents.findByRequestIdForUpdate(request);
        if(job!=null&&r!=null)com.ticketing.ticket.ReservationRuntimeRecovery.assertOwned(job,r);
        if (r != null && "REQUESTED".equals(r.getStatus()) && r.getCreatedAt().getTime() < System.currentTimeMillis() - 120000) {
            if(job!=null)inventory.abortRuntime(r,com.ticketing.infrastructure.Db.number(job,"sale_version"));
            else inventory.release(r.getSkuId(), r.getUserId(), r.getReservationId());
            intents.tryMarkFailedIfRequested(request);
            if(job!=null)runtime.failed(job);
        }
    }
}
