package com.ticketing.ticket;

import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import com.ticketing.sale.SaleManifest;
import com.ticketing.sale.TicketSaleArmory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * V3 HTTP acceptance: one Redis Lua call, no MySQL write. A 202 from here means "accepted for processing, NOT yet a
 * confirmed reservation" ({@code durable=false}); success/failure are only ever read from MySQL afterwards.
 */
@Component
@ConditionalOnProperty(name="ticketing.reservations-v3.enabled",havingValue="true")
public class V3Entry {
    static final String RECEIPT_SEMANTICS="V3_PROCESSING";
    static final String PROCESSING_MESSAGE="已受理，处理中；尚未确认抢票成功，请以订单或查询结果为准。";
    private final V3Redis redis;private final ReservationDispatch queue;private final TicketSaleArmory armory;private final ReservationIntentService intents;private final MeterRegistry metrics;
    private final int userLimit;private final long capFreshMillis;
    public V3Entry(V3Redis redis,ReservationDispatch queue,TicketSaleArmory armory,ReservationIntentService intents,MeterRegistry metrics,Environment env){
        this.redis=redis;this.queue=queue;this.armory=armory;this.intents=intents;this.metrics=metrics;
        userLimit=env.getProperty("ticketing.ingress.per-user-inflight",Integer.class,4);
        capFreshMillis=env.getProperty("ticketing.reservations-v3.capacity-fresh-ms",Long.class,3000L);
    }
    /** Conservative capacity used while no fresh published value exists (reconciler down): one round of active slots. */
    volatile int fallbackCap=4;

    public Map<String,Object> accept(ReserveRequest r,String ip){
        String fingerprint=intents.fingerprint(r);
        SaleManifest plan=armory.cachedRuntimePlan(r.getEventSessionId(),r.getTicketSkuId()); // S1: no MySQL on a cache hit
        String attemptId=Db.id();
        List<String> out;
        try{out=AttemptPath.time(metrics,"v3_accept_lua",()->redis.accept(r,fingerprint,attemptId,Db.id(),Db.id(),ip,plan.version(),ReservationAttemptStore.WAIT_MILLIS,userLimit,fallbackCap,capFreshMillis,org.slf4j.MDC.get("traceId")));}
        catch(RuntimeException unavailable){
            metrics.counter("attempt.v3.accept","outcome","redis_unavailable").increment();
            // The script may or may not have run: unknown, never a refusal claim.
            throw new AttemptAdmissionException(503,"VALIDATION_UNAVAILABLE","暂时无法确认受理结果，请使用原请求编号查询或重试");
        }
        String outcome=out.isEmpty()?"EMPTY":out.get(0);
        metrics.counter("attempt.v3.accept","outcome",outcome.toLowerCase(Locale.ROOT)).increment();
        switch(outcome){
            case "ACCEPTED":return receipt(r.getRequestId(),out.get(1),true,Long.parseLong(out.get(3)),Long.parseLong(out.get(4)),"WAITING");
            case "KNOWN":{
                Map<String,Object> durable=durableView(r.getRequestId(),r.getUserId());if(durable!=null)return durable;
                String aliasOf=out.get(3);
                if(!aliasOf.isEmpty()){Map<String,Object> target=durableView(aliasOf,r.getUserId());if(target!=null)return target;}
                return receipt(r.getRequestId(),out.get(1),false,Long.parseLong(out.get(4)),Long.parseLong(out.get(5)),out.get(2));
            }
            case "ALIAS":{
                Map<String,Object> target=durableView(out.get(1),r.getUserId());if(target!=null)return target;
                Map<String,Object> value=receipt(r.getRequestId(),"",false,0,System.currentTimeMillis(),"ALIAS");value.put("aliasOf",out.get(1));return value;
            }
            case "MISMATCH":throw new BusinessException(409,"请求编号已用于其他购票参数");
            case "BUSY":throw new AttemptAdmissionException(429,"SYSTEM_BUSY","系统繁忙，本次请求未受理；可使用原请求编号稍后重试");
            case "USER_LIMIT":throw new AttemptAdmissionException(429,"SYSTEM_BUSY","尚有请求处理中，本次请求未受理；请使用原请求编号查询");
            case "CLOSED":throw new AttemptAdmissionException(503,"SYSTEM_BUSY","抢票受理已暂停，本次请求未受理");
            default:throw new AttemptAdmissionException(503,"VALIDATION_UNAVAILABLE","暂时无法确认受理结果，请使用原请求编号查询或重试");
        }
    }
    private Map<String,Object> durableView(String requestId,long user){
        Map<String,Object> job=queue.byRequest(requestId);if(job==null)return null;
        Map<String,Object> value=queue.view(job,user);value.put("received",true);value.put("newSubmission",false);value.put("receiptSemantics",RECEIPT_SEMANTICS);return value;
    }
    private Map<String,Object> receipt(String requestId,String attemptId,boolean created,long deadline,long acceptedAt,String stage){
        Map<String,Object> v=new LinkedHashMap<>();
        v.put("requestId",requestId);if(attemptId!=null&&!attemptId.isEmpty())v.put("attemptId",attemptId);
        v.put("state","PROCESSING");v.put("stage",stage);v.put("received",true);v.put("newSubmission",created);
        v.put("reliableAccepted",false);v.put("durable",false);v.put("authoritative",false);v.put("terminal",false);v.put("reservationSuccess",false);
        v.put("pollingSuggested",true);v.put("retryWithSameRequestId",true);v.put("receiptSemantics",RECEIPT_SEMANTICS);
        v.put("receivedAt",acceptedAt);v.put("waitUntil",System.currentTimeMillis()+120000);if(deadline>0)v.put("waitingDeadline",deadline);
        v.put("message",PROCESSING_MESSAGE);return v;
    }
    /**
     * Status when MySQL has no row for the request. Never a failure: either PROCESSING (Redis knows it) or null (unknown).
     * A Redis alias whose target already has a MySQL row returns that row's authoritative view.
     */
    public Map<String,Object> pendingStatus(String requestId,long user){
        Map<Object,Object> req;try{req=redis.request(requestId);}catch(RuntimeException unavailable){return null;}
        if(req==null||req.isEmpty()||!String.valueOf(user).equals(String.valueOf(req.get("user"))))return null;
        String stage=String.valueOf(req.get("state"));
        if("ALIAS".equals(stage)&&req.get("aliasOf")!=null){Map<String,Object> target=durableView(req.get("aliasOf").toString(),user);if(target!=null)return target;}
        Map<String,Object> v=new LinkedHashMap<>();v.put("stage",stage);
        if(req.get("attempt")!=null)v.put("attemptId",req.get("attempt"));
        if(req.get("deadline")!=null)v.put("waitingDeadline",Long.parseLong(req.get("deadline").toString()));
        v.put("receiptSemantics",RECEIPT_SEMANTICS);
        if("CONFLICT".equals(stage)){v.put("pollingSuggested",false);v.put("message","请求编号已被其他交易使用，请查看订单或使用新的请求编号。");}
        return v;
    }
}
