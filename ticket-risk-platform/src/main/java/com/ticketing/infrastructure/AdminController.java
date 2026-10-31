package com.ticketing.infrastructure;
import com.ticketing.common.*;
import com.ticketing.common.context.UserHolder;
import com.ticketing.event.CatalogAdminService;
import com.ticketing.review.ManualReviewService;
import com.ticketing.payment.AutoPaymentService;
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
 @org.springframework.beans.factory.annotation.Autowired(required=false) private RiskSubjectFence riskFences;
 private final Db db;private final ManualReviewService review;private final AutoPaymentService autoPayment;private final AuditLogService audit;private final WorkflowPersistenceService workflow;private final MeterRegistry metrics;private final TaskPools pools;private final CatalogAdminService catalog;private final StringRedisTemplate redis;
 public AdminController(Db db,ManualReviewService review,AutoPaymentService autoPayment,AuditLogService audit,WorkflowPersistenceService workflow,MeterRegistry metrics,TaskPools pools,CatalogAdminService catalog,StringRedisTemplate redis){this.db=db;this.review=review;this.autoPayment=autoPayment;this.audit=audit;this.workflow=workflow;this.metrics=metrics;this.pools=pools;this.catalog=catalog;this.redis=redis;}
 @lombok.Data public static class EventInput{@NotBlank @Size(max=150) private String name;@NotBlank @Size(max=200) private String venue;@Size(max=4000) private String description;}
 @lombok.Data public static class SessionInput{@NotBlank private String eventId;@NotNull private LocalDateTime startsAt;}
 @lombok.Data public static class SkuInput{@NotBlank private String eventSessionId;@NotBlank @Size(max=80) private String name;@NotNull @DecimalMin("0.01") private BigDecimal price;@Min(0) private int totalStock;@Min(1) @Max(100) private int purchaseLimit;@NotNull private LocalDateTime saleStartTime;@NotNull private LocalDateTime saleEndTime;}
 @lombok.Data public static class ReviewInput{@NotBlank @Size(max=80) private String requestId;@Pattern(regexp="APPROVE|REJECT") @NotNull private String action;}
 @PostMapping("/events") public Result event(@Valid @RequestBody EventInput r){String id=Db.id();db.jdbc.update("INSERT INTO tb_event(id,name,description,venue,status) VALUES(?,?,?,?,'ON_SALE')",id,r.name,r.description,r.venue);return Result.ok(id);}
 @PostMapping("/sessions") public Result session(@Valid @RequestBody SessionInput r){String id=Db.id();db.jdbc.update("INSERT INTO tb_event_session(id,event_id,starts_at) VALUES(?,?,?)",id,r.eventId,java.sql.Timestamp.valueOf(r.startsAt));return Result.ok(id);}
 @PostMapping("/skus") public Result sku(@Valid @RequestBody SkuInput r){BusinessException.require(r.saleEndTime.isAfter(r.saleStartTime),"销售结束时间必须晚于开始时间");String id=Db.id();db.jdbc.update("INSERT INTO tb_ticket_sku(id,event_session_id,name,price,total_stock,remaining_stock,purchase_limit,sale_start_time,sale_end_time,status) VALUES(?,?,?,?,?,?,?,?,?,'OFF_SALE')",id,r.eventSessionId,r.name,r.price,r.totalStock,r.totalStock,r.purchaseLimit,java.sql.Timestamp.valueOf(r.saleStartTime),java.sql.Timestamp.valueOf(r.saleEndTime));return Result.ok(id);}
 @PatchMapping("/skus/{id}") public Result updateSku(@PathVariable String id,@RequestParam String status,@RequestParam(required=false)Integer stock){catalog.update(id,status,stock);return Result.ok();}
 @PatchMapping("/skus/{id}/single-purchase-limit") public Result singlePurchaseLimit(@PathVariable String id,@RequestParam int quantity){catalog.singlePurchaseLimit(id,quantity);return Result.ok();}
 @GetMapping("/reviews") public Result reviews(){return Result.ok(db.jdbc.queryForList("SELECT * FROM tb_manual_review_ticket ORDER BY created_at DESC LIMIT 100"));}
 @PostMapping("/reviews/{id}/decision") public Result decide(@PathVariable String id,@Valid @RequestBody ReviewInput r){
  Map<String,Object> ticket=review.decide(id,r.requestId,r.action,UserHolder.getUser().getId());
  // decide() TX already committed; auto-confirm payment outside review module (no ManualReview↔Wallet cycle).
  if("APPROVE".equals(r.action)&&ticket.get("order_id")!=null)autoPayment.attempt(ticket.get("order_id").toString());
  return Result.ok(ticket);
 }
 @GetMapping("/orders/{id}/risk") public Result risk(@PathVariable String id){return Result.ok(db.jdbc.queryForList("SELECT * FROM tb_risk_decision WHERE order_id=? ORDER BY created_at",id));}
 @GetMapping("/risk-profiles") public Result riskProfiles(@RequestParam(required=false) String subjectType,@RequestParam(required=false) String subjectValue){
  if(subjectType!=null&&subjectValue!=null)return Result.ok(db.jdbc.queryForList("SELECT * FROM tb_risk_profile WHERE subject_type=? AND subject_value=?",subjectType,subjectValue));
  return Result.ok(db.jdbc.queryForList("SELECT * FROM tb_risk_profile ORDER BY updated_at DESC LIMIT 100"));
 }
 @GetMapping("/orders/{id}/trace") public Result trace(@PathVariable String id){return Result.ok(workflow.getExecutionChain(id));}
 @GetMapping("/orders/{id}/audit") public Result audit(@PathVariable String id){return Result.ok(audit.listByWorkflowId(id));}
 @GetMapping("/rules") public Result rules(){return Result.ok(db.jdbc.queryForList("SELECT * FROM tb_risk_rule ORDER BY rule_code"));}
 @PutMapping("/rules/{code}") public Result rule(@PathVariable String code,@RequestBody RiskRule r){
  BusinessException.require(r.getConfig()!=null&&r.getWeight()>=0&&r.getWeight()<=1000,"规则参数无效");
  if(code.equals("DECISION_POLICY")){for(String key:Arrays.asList("review","block","agentMaxAdjustment","rateLimit"))BusinessException.require(r.getConfig().get(key) instanceof Number,"决策策略参数缺失");BusinessException.require(((Number)r.getConfig().get("review")).intValue()>0&&((Number)r.getConfig().get("block")).intValue()>((Number)r.getConfig().get("review")).intValue()&&r.isEnabled(),"决策阈值无效");}
  else BusinessException.require(r.getConfig().get("feature") instanceof String&&r.getConfig().get("threshold") instanceof Number,"规则特征/阈值无效");
  BusinessException.require(db.jdbc.update("UPDATE tb_risk_rule SET enabled=?,weight=?,config=?,version=version+1 WHERE rule_code=? AND version=?",r.isEnabled(),r.getWeight(),db.json(r.getConfig()),code,r.getVersion())==1,"规则版本冲突");return Result.ok();}
 @PostMapping("/blacklist") public Result blacklist(@RequestParam String kind,@RequestParam String value,@RequestParam(defaultValue="3600")long seconds){BusinessException.require(Arrays.asList("user","device","ip").contains(kind)&&seconds>0&&seconds<=2592000,"黑名单参数无效");if(riskFences!=null&&riskFences.isEnabled())riskFences.blacklist(kind,value,seconds);else redis.opsForValue().set("risk:blacklist:"+kind+":"+value,"1",java.time.Duration.ofSeconds(seconds));return Result.ok();}
 @PostMapping("/pools/{name}") public Result pool(@PathVariable String name,@RequestParam int core,@RequestParam int max){pools.resize(name,core,max);return Result.ok();}
 @GetMapping("/metrics") public Result metrics(){Map<String,Object> result=new LinkedHashMap<>();result.put("llm",audit.summarizeLlmUsage());result.put("llmByNode",audit.summarizeLlmUsageByNode());List<Map<String,Object>> meters=new ArrayList<>();for(Meter meter:metrics.getMeters()){Map<String,Object> item=new LinkedHashMap<>();item.put("name",meter.getId().getName());item.put("tags",meter.getId().getTags());List<Double> values=new ArrayList<>();meter.measure().forEach(m->values.add(m.getValue()));item.put("values",values);meters.add(item);}result.put("meters",meters);return Result.ok(result);}
}
