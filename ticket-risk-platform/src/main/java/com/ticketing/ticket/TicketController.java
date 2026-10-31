package com.ticketing.ticket;
import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.InventoryService;
import com.ticketing.risk.RiskDecisionService;
import com.ticketing.common.*;
import com.ticketing.common.context.UserHolder;
import com.ticketing.config.RequestTimings;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import io.micrometer.core.instrument.MeterRegistry;
@RestController @RequestMapping("/api/tickets")
public class TicketController {
 @org.springframework.beans.factory.annotation.Value("${ticketing.reservations-v2.legacy-reserve-accepting:true}") private boolean legacyAccepting=true;
 @org.springframework.beans.factory.annotation.Value("${ticketing.reservations-v2.enabled:false}") private boolean priorityReservations;
 @org.springframework.beans.factory.annotation.Value("${ticketing.reservations-v2.attempts.enabled:false}") private boolean durableAttempts;
 private final RiskDecisionService risk;private final ReservationIntentService intents;private final ReservationTransactionService transactions;private final Db db;private final InventoryService inventory;private final MeterRegistry metrics;
 public TicketController(RiskDecisionService risk,ReservationIntentService intents,ReservationTransactionService transactions,Db db,InventoryService inventory,MeterRegistry metrics){this.risk=risk;this.intents=intents;this.transactions=transactions;this.db=db;this.inventory=inventory;this.metrics=metrics;}
 @org.springframework.beans.factory.annotation.Autowired(required=false) private com.ticketing.sale.TicketSaleArmory armory;
 @PostMapping("/reserve") public Result reserve(@Valid @RequestBody ReserveRequest r,HttpServletRequest http){
  RequestTimings.identify(r.getRequestId(),r.getTicketSkuId());
  return RequestTimings.measure("total",()->reserveMeasured(r,http));
 }
 private Result reserveMeasured(ReserveRequest r,HttpServletRequest http){
  if(!r.getUserId().equals(UserHolder.getUser().getId()))throw new BusinessException(403,"请求用户与登录身份不一致");
  try{
   if(durableAttempts&&db.one("SELECT j.id FROM tb_reservation_job j WHERE j.attempt_protocol IN (3,4) AND j.request_id=? UNION ALL SELECT j.id FROM tb_reservation_request_alias a JOIN tb_reservation_job j ON j.id=a.job_id WHERE j.attempt_protocol IN (3,4) AND a.request_id=? LIMIT 1",r.getRequestId(),r.getRequestId())!=null)
    throw new BusinessException(409,"本请求属于可靠attempt，请使用新版抢票入口查询或重试");
   Map<String,Object> previous=RequestTimings.measure("request.lookup",()->db.one("SELECT * FROM tb_reserve_request WHERE request_id=?",r.getRequestId()));
   if(previous!=null&&"COMPLETED".equals(previous.get("status"))){BusinessException.require(previous.get("fingerprint").equals(intents.fingerprint(r)),"请求标识已用于其他参数");return Result.ok(transactions.reserve(r.getRequestId(),r.getEventSessionId()));}
   if(previous==null&&(!legacyAccepting||priorityReservations))throw new BusinessException(503,"请使用新版抢票入口，原请求仍可查询或重试");
   // getRemoteAddr is authoritative; untrusted forwarded headers are never accepted.
   com.ticketing.sale.SaleManifest plan=RequestTimings.measure("sale.admission",()->armory==null?null:armory.admissionPlan(r.getEventSessionId(),r.getTicketSkuId()));
   Map<String,Object> admission=RequestTimings.measure("risk.admit",()->plan==null
       ?risk.admit(r.getUserId(),r.getDeviceId(),http.getRemoteAddr())
       :risk.admit(r.getUserId(),r.getDeviceId(),http.getRemoteAddr(),plan.rules()));
   if(plan!=null){
       admission.put("saleVersion",plan.version());
       Map<String,Object> policy=new LinkedHashMap<>((Map<String,Object>)admission.get("policy"));
       policy.put("saleAssemblyVersion",plan.version());policy.put("saleSessionId",plan.sessionId());admission.put("policy",policy);
   }
   BusinessException.require(RequestTimings.measure("sku.exists",()->db.one("SELECT id FROM tb_ticket_sku WHERE id=?",r.getTicketSkuId()))!=null,"票档不存在");
   // Hot path: skip SKU FOR UPDATE when Redis inventory/config already initialized (BackgroundJobs/configure).
   RequestTimings.measure("inventory.ensureReady",()->inventory.ensureReady(r.getTicketSkuId()));
   RequestTimings.measure("intents.create",()->intents.create(r,http.getRemoteAddr()));
   Map<String,Object> result=RequestTimings.measure("transactions.reserve",()->transactions.reserve(r.getRequestId(),r.getEventSessionId(),admission));metrics.counter("ticket.reserve.success").increment();return Result.ok(result);
  }catch(RuntimeException e){metrics.counter("ticket.reserve.failed").increment();throw e;}
 }
}
