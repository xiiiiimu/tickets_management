package com.ticketing.ticket;
import com.ticketing.common.Result;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
public class ReservationModeController {
    private final ObjectProvider<ReservationV2Service> service;
    public ReservationModeController(ObjectProvider<ReservationV2Service> service){this.service=service;}
    @GetMapping("/api/tickets/reservation-mode") public Result mode(){
        ReservationV2Service v2=service.getIfAvailable();Map<String,Object> value=new LinkedHashMap<>();value.put("mode",v2==null?"LEGACY":"PRIORITY_V2");value.put("accepting",v2==null||v2.isAccepting());value.put("receiptSemantics",v2==null?"RESERVATION_RESULT":v2.receiptSemantics());if(v2!=null&&v2.usesDurableAttempts())value.put("waitingMillis",ReservationAttemptStore.WAIT_MILLIS);return Result.ok(value);
    }
}
