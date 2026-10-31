from build_ticketing import java
java('event/EventCatalogProvider','''
package com.ticketing.event;
import java.util.*;
public interface EventCatalogProvider { List<Map<String,Object>> events(); }
''')
java('event/LocalEventCatalogProvider','''
package com.ticketing.event;
import com.ticketing.infrastructure.Db;
import org.springframework.stereotype.Service;
import java.util.*;
@Service public class LocalEventCatalogProvider implements EventCatalogProvider {
 private final Db db;public LocalEventCatalogProvider(Db db){this.db=db;}
 public List<Map<String,Object>> events(){return db.jdbc.queryForList("SELECT * FROM tb_event WHERE status='ON_SALE' ORDER BY name");}
}
''')
java('event/EventController','''
package com.ticketing.event;
import com.ticketing.infrastructure.Db;
import com.ticketing.common.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/events")
public class EventController {
 private final EventCatalogProvider catalog;private final Db db;
 public EventController(EventCatalogProvider catalog,Db db){this.catalog=catalog;this.db=db;}
 @GetMapping public Result list(){return Result.ok(catalog.events());}
 @GetMapping("/{id}") public Result detail(@PathVariable String id){Map<String,Object> event=db.one("SELECT * FROM tb_event WHERE id=? AND status='ON_SALE'",id);if(event==null)throw new BusinessException(404,"演出不存在");event.put("sessions",db.jdbc.queryForList("SELECT * FROM tb_event_session WHERE event_id=? ORDER BY starts_at",id));event.put("skus",db.jdbc.queryForList("SELECT s.* FROM tb_ticket_sku s JOIN tb_event_session e ON e.id=s.event_session_id WHERE e.event_id=?",id));return Result.ok(event);}
}
''')
java('ticket/TicketController','''
package com.ticketing.ticket;
import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.InventoryService;
import com.ticketing.risk.RiskDecisionService;
import com.ticketing.common.*;
import com.ticketing.common.context.UserHolder;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import io.micrometer.core.instrument.MeterRegistry;
@RestController @RequestMapping("/api/tickets")
public class TicketController {
 private final RiskDecisionService risk;private final ReservationIntentService intents;private final ReservationTransactionService transactions;private final Db db;private final InventoryService inventory;private final MeterRegistry metrics;
 public TicketController(RiskDecisionService risk,ReservationIntentService intents,ReservationTransactionService transactions,Db db,InventoryService inventory,MeterRegistry metrics){this.risk=risk;this.intents=intents;this.transactions=transactions;this.db=db;this.inventory=inventory;this.metrics=metrics;}
 @PostMapping("/reserve") public Result reserve(@Valid @RequestBody ReserveRequest r,HttpServletRequest http){
  if(!r.getUserId().equals(UserHolder.getUser().getId()))throw new BusinessException(403,"请求用户与登录身份不一致");
  try{
   Map<String,Object> previous=db.one("SELECT * FROM tb_reserve_request WHERE request_id=?",r.getRequestId());
   if(previous!=null&&"COMPLETED".equals(previous.get("status"))){BusinessException.require(previous.get("fingerprint").equals(intents.fingerprint(r)),"请求标识已用于其他参数");return Result.ok(transactions.reserve(r.getRequestId(),r.getEventSessionId()));}
   // getRemoteAddr is authoritative; untrusted forwarded headers are never accepted.
   risk.fast(r.getUserId(),r.getDeviceId(),http.getRemoteAddr());
   Map<String,Object> sku=db.one("SELECT * FROM tb_ticket_sku WHERE id=?",r.getTicketSkuId());BusinessException.require(sku!=null,"票档不存在");inventory.configure(sku);
   intents.create(r,http.getRemoteAddr());Map<String,Object> result=transactions.reserve(r.getRequestId(),r.getEventSessionId());metrics.counter("ticket.reserve.success").increment();return Result.ok(result);
  }catch(RuntimeException e){metrics.counter("ticket.reserve.failed").increment();throw e;}
 }
}
''')
java('order/OrderController','''
package com.ticketing.order;
import com.ticketing.infrastructure.Db;
import com.ticketing.common.*;
import com.ticketing.common.context.UserHolder;
import com.ticketing.payment.*;
import com.ticketing.workflow.service.WorkflowPersistenceService;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
@RestController @RequestMapping("/api")
public class OrderController {
 private final Db db;private final OrderService orders;private final WalletService wallet;private final WorkflowPersistenceService workflow;
 public OrderController(Db db,OrderService orders,WalletService wallet,WorkflowPersistenceService workflow){this.db=db;this.orders=orders;this.wallet=wallet;this.workflow=workflow;}
 private long user(){return UserHolder.getUser().getId();}
 private Map<String,Object> own(String id){Map<String,Object> order=db.one("SELECT * FROM tb_ticket_order WHERE id=?",id);if(order==null)throw new BusinessException(404,"订单不存在");orders.owner(order,user());return order;}
 @GetMapping("/orders") public Result list(){return Result.ok(db.jdbc.queryForList("SELECT * FROM tb_ticket_order WHERE user_id=? ORDER BY created_at DESC LIMIT 100",user()));}
 @GetMapping("/orders/{id}") public Result detail(@PathVariable String id){Map<String,Object> order=own(id);order.put("tickets",db.jdbc.queryForList("SELECT * FROM tb_issued_ticket WHERE order_id=?",id));return Result.ok(order);}
 @GetMapping("/orders/{id}/trace") public Result trace(@PathVariable String id){own(id);return Result.ok(workflow.getExecutionChain(id));}
 @PostMapping("/orders/{id}/cancel") public Result cancel(@PathVariable String id){return Result.ok(orders.cancel(id,user()));}
 @PostMapping("/orders/{id}/pay") public Result pay(@PathVariable String id,@Valid @RequestBody PaymentRequest r,HttpServletRequest http){return Result.ok(wallet.pay(id,user(),r,http.getRemoteAddr()));}
 @PostMapping("/orders/{id}/refund") public Result refund(@PathVariable String id,@Valid @RequestBody PaymentRequest r){return Result.ok(wallet.refund(id,user(),r));}
 @GetMapping("/wallet") public Result wallet(){return Result.ok(db.one("SELECT * FROM tb_wallet_account WHERE user_id=?",user()));}
}
''')
java('event/CatalogAdminService','''
package com.ticketing.event;
import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.InventoryService;
import com.ticketing.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service
public class CatalogAdminService {
 private final Db db;private final InventoryService inventory;
 public CatalogAdminService(Db db,InventoryService inventory){this.db=db;this.inventory=inventory;}
 @Transactional public void update(String id,String status,Integer stock){
  BusinessException.require(Arrays.asList("ON_SALE","OFF_SALE").contains(status),"票档状态无效");
  Map<String,Object> sku=db.one("SELECT * FROM tb_ticket_sku WHERE id=? FOR UPDATE",id);BusinessException.require(sku!=null,"票档不存在");
  if(stock!=null){BusinessException.require(stock>=0&&db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reserve_request WHERE sku_id=?",Long.class,id)==0,"开始交易后不可重设库存");db.jdbc.update("UPDATE tb_ticket_sku SET total_stock=?,remaining_stock=? WHERE id=?",stock,stock,id);}
  db.jdbc.update("UPDATE tb_ticket_sku SET status=?,version=version+1 WHERE id=?",status,id);
  // The cache refresh runs after commit; DB sale checks remain authoritative during the brief lag.
 }
}
''')
java('infrastructure/AdminController','''
package com.ticketing.infrastructure;
import com.ticketing.common.*;
import com.ticketing.common.context.UserHolder;
import com.ticketing.event.CatalogAdminService;
import com.ticketing.review.ManualReviewService;
import com.ticketing.audit.service.AuditLogService;
import com.ticketing.workflow.service.WorkflowPersistenceService;
import com.ticketing.risk.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.micrometer.core.instrument.*;
import javax.validation.Valid;
import javax.validation.constraints.*;
import java.util.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@RestController @RequestMapping("/api/admin")
public class AdminController {
 private final Db db;private final ManualReviewService review;private final AuditLogService audit;private final WorkflowPersistenceService workflow;private final MeterRegistry metrics;private final TaskPools pools;private final CatalogAdminService catalog;private final StringRedisTemplate redis;
 public AdminController(Db db,ManualReviewService review,AuditLogService audit,WorkflowPersistenceService workflow,MeterRegistry metrics,TaskPools pools,CatalogAdminService catalog,StringRedisTemplate redis){this.db=db;this.review=review;this.audit=audit;this.workflow=workflow;this.metrics=metrics;this.pools=pools;this.catalog=catalog;this.redis=redis;}
 @lombok.Data public static class EventInput{@NotBlank @Size(max=150) private String name;@NotBlank @Size(max=200) private String venue;@Size(max=4000) private String description;}
 @lombok.Data public static class SessionInput{@NotBlank private String eventId;@NotNull private LocalDateTime startsAt;}
 @lombok.Data public static class SkuInput{@NotBlank private String eventSessionId;@NotBlank @Size(max=80) private String name;@NotNull @DecimalMin("0.01") private BigDecimal price;@Min(0) private int totalStock;@Min(1) @Max(100) private int purchaseLimit;@NotNull private LocalDateTime saleStartTime;@NotNull private LocalDateTime saleEndTime;}
 @lombok.Data public static class ReviewInput{@NotBlank @Size(max=80) private String requestId;@Pattern(regexp="APPROVE|REJECT") @NotNull private String action;}
 @PostMapping("/events") public Result event(@Valid @RequestBody EventInput r){String id=Db.id();db.jdbc.update("INSERT INTO tb_event(id,name,description,venue,status) VALUES(?,?,?,?,'ON_SALE')",id,r.name,r.description,r.venue);return Result.ok(id);}
 @PostMapping("/sessions") public Result session(@Valid @RequestBody SessionInput r){String id=Db.id();db.jdbc.update("INSERT INTO tb_event_session(id,event_id,starts_at) VALUES(?,?,?)",id,r.eventId,java.sql.Timestamp.valueOf(r.startsAt));return Result.ok(id);}
 @PostMapping("/skus") public Result sku(@Valid @RequestBody SkuInput r){BusinessException.require(r.saleEndTime.isAfter(r.saleStartTime),"销售结束时间必须晚于开始时间");String id=Db.id();db.jdbc.update("INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,purchase_limit,sale_start_time,sale_end_time,status) VALUES(?,?,?,?,?,?,?,?,?,'OFF_SALE')",id,r.eventSessionId,r.name,r.price,r.totalStock,r.totalStock,r.purchaseLimit,java.sql.Timestamp.valueOf(r.saleStartTime),java.sql.Timestamp.valueOf(r.saleEndTime));return Result.ok(id);}
 @PatchMapping("/skus/{id}") public Result updateSku(@PathVariable String id,@RequestParam String status,@RequestParam(required=false)Integer stock){catalog.update(id,status,stock);return Result.ok();}
 @GetMapping("/reviews") public Result reviews(){return Result.ok(db.jdbc.queryForList("SELECT * FROM tb_manual_review_ticket ORDER BY created_at DESC LIMIT 100"));}
 @PostMapping("/reviews/{id}/decision") public Result decide(@PathVariable String id,@Valid @RequestBody ReviewInput r){return Result.ok(review.decide(id,r.requestId,r.action,UserHolder.getUser().getId()));}
 @GetMapping("/orders/{id}/risk") public Result risk(@PathVariable String id){return Result.ok(db.jdbc.queryForList("SELECT * FROM tb_risk_decision WHERE order_id=? ORDER BY created_at",id));}
 @GetMapping("/orders/{id}/trace") public Result trace(@PathVariable String id){return Result.ok(workflow.getExecutionChain(id));}
 @GetMapping("/orders/{id}/audit") public Result audit(@PathVariable String id){return Result.ok(audit.listByWorkflowId(id));}
 @GetMapping("/rules") public Result rules(){return Result.ok(db.jdbc.queryForList("SELECT * FROM tb_risk_rule ORDER BY rule_code"));}
 @PutMapping("/rules/{code}") public Result rule(@PathVariable String code,@RequestBody RiskRule r){
  BusinessException.require(r.getConfig()!=null&&r.getWeight()>=0&&r.getWeight()<=1000,"规则参数无效");
  if(code.equals("DECISION_POLICY")){for(String key:Arrays.asList("review","block","agentMaxAdjustment","rateLimit"))BusinessException.require(r.getConfig().get(key) instanceof Number,"决策策略参数缺失");BusinessException.require(((Number)r.getConfig().get("review")).intValue()>0&&((Number)r.getConfig().get("block")).intValue()>((Number)r.getConfig().get("review")).intValue()&&r.isEnabled(),"决策阈值无效");}
  else BusinessException.require(r.getConfig().get("feature") instanceof String&&r.getConfig().get("threshold") instanceof Number,"规则特征/阈值无效");
  BusinessException.require(db.jdbc.update("UPDATE tb_risk_rule SET enabled=?,weight=?,config=?,version=version+1 WHERE rule_code=? AND version=?",r.isEnabled(),r.getWeight(),db.json(r.getConfig()),code,r.getVersion())==1,"规则版本冲突");return Result.ok();}
 @PostMapping("/blacklist") public Result blacklist(@RequestParam String kind,@RequestParam String value,@RequestParam(defaultValue="3600")long seconds){BusinessException.require(Arrays.asList("user","device","ip").contains(kind)&&seconds>0&&seconds<=2592000,"黑名单参数无效");redis.opsForValue().set("risk:blacklist:"+kind+":"+value,"1",java.time.Duration.ofSeconds(seconds));return Result.ok();}
 @PostMapping("/pools/{name}") public Result pool(@PathVariable String name,@RequestParam int core,@RequestParam int max){pools.resize(name,core,max);return Result.ok();}
 @GetMapping("/metrics") public Result metrics(){Map<String,Object> result=new LinkedHashMap<>();result.put("llm",audit.summarizeLlmUsage());result.put("llmByNode",audit.summarizeLlmUsageByNode());List<Map<String,Object>> meters=new ArrayList<>();for(Meter meter:metrics.getMeters()){Map<String,Object> item=new LinkedHashMap<>();item.put("name",meter.getId().getName());item.put("tags",meter.getId().getTags());List<Double> values=new ArrayList<>();meter.measure().forEach(m->values.add(m.getValue()));item.put("values",values);meters.add(item);}result.put("meters",meters);return Result.ok(result);}
}
''')
