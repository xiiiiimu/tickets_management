from build_ticketing import java
java('order/OrderService','''
package com.ticketing.order;
import com.ticketing.infrastructure.Db;
import com.ticketing.outbox.OutboxService;
import com.ticketing.workflow.service.WorkflowExecutionService;
import com.ticketing.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import io.micrometer.core.instrument.MeterRegistry;
@Service
public class OrderService {
 private final Db db;private final OrderStateMachine machine;private final OutboxService outbox;private final WorkflowExecutionService workflow;private final MeterRegistry metrics;
 public OrderService(Db db,OrderStateMachine machine,OutboxService outbox,WorkflowExecutionService workflow,MeterRegistry metrics){this.db=db;this.machine=machine;this.outbox=outbox;this.workflow=workflow;this.metrics=metrics;}
 public Map<String,Object> lock(String id){Map<String,Object> order=db.one("SELECT * FROM tb_ticket_order WHERE id=? FOR UPDATE",id);if(order==null)throw new BusinessException(404,"订单不存在");return order;}
 public void owner(Map<String,Object> order,long user){if(Db.number(order,"user_id")!=user)throw new BusinessException(403,"无权访问该订单");}
 public void release(Map<String,Object> order){
  int changed=db.jdbc.update("UPDATE tb_reservation SET status='RELEASED' WHERE id=? AND status='HELD'",order.get("reservation_id"));
  if(changed==1){db.jdbc.update("UPDATE tb_ticket_sku SET remaining_stock=remaining_stock+?,version=version+1 WHERE id=?",order.get("quantity"),order.get("sku_id"));db.jdbc.update("UPDATE tb_user_purchase SET quantity=quantity-? WHERE user_id=? AND sku_id=?",order.get("quantity"),order.get("user_id"),order.get("sku_id"));outbox.append(order.get("id").toString(),"RELEASE");}
 }
 @Transactional public Map<String,Object> cancel(String id,long user){Map<String,Object> order=lock(id);owner(order,user);if("CANCELLED".equals(order.get("status")))return order;machine.transition(order,OrderStatus.CANCELLED);release(order);metrics.counter("order.cancelled").increment();workflow.record(id,Objects.toString(order.get("trace_id"),""),"CANCEL",order.get("status"));return order;}
 @Transactional public void expire(String id){Map<String,Object> order=lock(id);if(((java.sql.Timestamp)order.get("expires_at")).getTime()>System.currentTimeMillis())return;
  if(OrderStateMachine.permits(OrderStatus.valueOf(order.get("status").toString()),OrderStatus.PAY_TIMEOUT)){machine.transition(order,OrderStatus.PAY_TIMEOUT);release(order);metrics.counter("order.cancelled").increment();workflow.record(id,Objects.toString(order.get("trace_id"),""),"PAY_TIMEOUT",order.get("status"));}}
}
''')
java('review/ManualReviewService','''
package com.ticketing.review;
import com.ticketing.infrastructure.Db;
import com.ticketing.order.*;
import com.ticketing.common.BusinessException;
import com.ticketing.audit.service.AuditLogService;
import com.ticketing.workflow.service.WorkflowExecutionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service
public class ManualReviewService {
 private final Db db;private final OrderService orders;private final OrderStateMachine machine;private final AuditLogService audit;private final WorkflowExecutionService workflow;
 public ManualReviewService(Db db,OrderService orders,OrderStateMachine machine,AuditLogService audit,WorkflowExecutionService workflow){this.db=db;this.orders=orders;this.machine=machine;this.audit=audit;this.workflow=workflow;}
 public void open(Map<String,Object> order,Map<String,Object> decision,String phase){
  db.jdbc.update("INSERT INTO tb_manual_review_ticket(id,order_id,risk_decision_id,phase,status) VALUES(?,?,?,?,'PENDING')",Db.id(),order.get("id"),decision.get("id"),phase);
  machine.transition(order,OrderStatus.RISK_REVIEW);workflow.record(order.get("id").toString(),Objects.toString(order.get("trace_id"),""),"MANUAL_REVIEW",decision);
 }
 @Transactional public Map<String,Object> decide(String id,String requestId,String action,long reviewer){
  BusinessException.require(action.equals("APPROVE")||action.equals("REJECT"),"审核动作无效");
  Map<String,Object> ticket=db.one("SELECT * FROM tb_manual_review_ticket WHERE id=?",id);if(ticket==null)throw new BusinessException(404,"审核单不存在");
  Map<String,Object> order=orders.lock(ticket.get("order_id").toString());ticket=db.one("SELECT * FROM tb_manual_review_ticket WHERE id=? FOR UPDATE",id);
  if(!"PENDING".equals(ticket.get("status"))){BusinessException.require(action.equals(ticket.get("status"))&&requestId.equals(ticket.get("request_id"))&&Db.number(ticket,"reviewer_id")==reviewer,"审核单已处理");return ticket;}
  BusinessException.require(((java.sql.Timestamp)order.get("expires_at")).getTime()>System.currentTimeMillis(),"订单已过期");
  BusinessException.require(db.jdbc.update("UPDATE tb_manual_review_ticket SET status=?,request_id=?,reviewer_id=?,version=version+1 WHERE id=? AND status='PENDING' AND version=?",action,requestId,reviewer,id,ticket.get("version"))==1,"审核状态冲突");
  machine.transition(order,action.equals("APPROVE")?OrderStatus.WAITING_PAYMENT:OrderStatus.RISK_BLOCKED);if(action.equals("REJECT"))orders.release(order);
  Map<String,Object> log=new HashMap<>();log.put("workflowId",order.get("id"));log.put("traceId",order.get("trace_id"));log.put("nodeName","MANUAL_REVIEW");log.put("request",Arrays.asList(id,requestId,reviewer));log.put("response",action);audit.save(log);
  workflow.record(order.get("id").toString(),Objects.toString(order.get("trace_id"),""),"MANUAL_REVIEW",action);
  return db.one("SELECT * FROM tb_manual_review_ticket WHERE id=?",id);
 }
}
''')
java('payment/PaymentRequest','''
package com.ticketing.payment;
import lombok.Data;
import javax.validation.constraints.*;
@Data public class PaymentRequest {
 @NotBlank @Size(max=80) private String paymentRequestId;
 @NotBlank @Size(max=100) private String deviceId;
}
''')
java('payment/WalletService','''
package com.ticketing.payment;
import com.ticketing.infrastructure.Db;
import com.ticketing.order.*;
import com.ticketing.outbox.OutboxService;
import com.ticketing.risk.RiskDecisionService;
import com.ticketing.review.ManualReviewService;
import com.ticketing.workflow.service.WorkflowExecutionService;
import com.ticketing.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import io.micrometer.core.instrument.MeterRegistry;
@Service
public class WalletService {
 private final Db db;private final OrderService orders;private final OrderStateMachine machine;private final RiskDecisionService risk;private final ManualReviewService review;private final OutboxService outbox;private final WorkflowExecutionService workflow;private final MeterRegistry metrics;
 public WalletService(Db db,OrderService orders,OrderStateMachine machine,RiskDecisionService risk,ManualReviewService review,OutboxService outbox,WorkflowExecutionService workflow,MeterRegistry metrics){this.db=db;this.orders=orders;this.machine=machine;this.risk=risk;this.review=review;this.outbox=outbox;this.workflow=workflow;this.metrics=metrics;}
 private Map<String,Object> existing(String request,String order,long user,String type,String device){Map<String,Object> p=db.one("SELECT * FROM tb_payment_transaction WHERE payment_request_id=?",request);if(p!=null)BusinessException.require(order.equals(p.get("order_id"))&&Db.number(p,"user_id")==user&&type.equals(p.get("type"))&&device.equals(p.get("device_id")),"支付请求标识与原请求不一致");return p;}
 @Transactional public Map<String,Object> pay(String orderId,long user,PaymentRequest request,String ip){
  Map<String,Object> order=orders.lock(orderId);orders.owner(order,user);
  Map<String,Object> prior=existing(request.getPaymentRequestId(),orderId,user,"PAYMENT",request.getDeviceId());if(prior!=null)return prior;
  BusinessException.require("WAITING_PAYMENT".equals(order.get("status")),"订单尚不可支付或已支付");
  BusinessException.require(((java.sql.Timestamp)order.get("expires_at")).getTime()>System.currentTimeMillis(),"支付已超时");
  Map<String,Object> decision=risk.evaluate(order,"PAYMENT",request.getDeviceId(),ip);
  String status="SUCCESS";
  // An approved payment review permits one retry for the same device/IP, but a fresh BLOCK always wins.
  Map<String,Object> approval=db.one("SELECT t.id FROM tb_manual_review_ticket t JOIN tb_risk_decision d ON d.id=t.risk_decision_id WHERE t.order_id=? AND t.phase='PAYMENT' AND t.status='APPROVE' AND d.agent_analysis LIKE ? ORDER BY t.created_at DESC LIMIT 1",orderId,"%\\\"paymentContext\\\":\\\""+request.getDeviceId()+"|"+ip+"\\\"%");
  if("BLOCK".equals(decision.get("decision"))){machine.transition(order,OrderStatus.RISK_BLOCKED);orders.release(order);status="BLOCKED";}
  else if("REVIEW".equals(decision.get("decision"))&&approval==null){review.open(order,decision,"PAYMENT");status="REVIEW";}
  else {
   int paid=db.jdbc.update("UPDATE tb_wallet_account SET available_balance=available_balance-?,version=version+1 WHERE user_id=? AND available_balance>=?",order.get("amount"),user,order.get("amount"));
   if(paid==0){status="FAILED";metrics.counter("payment.failed").increment();}
   else {machine.transition(order,OrderStatus.PAYING);machine.transition(order,OrderStatus.PAID);outbox.append(orderId,"ISSUE");metrics.counter("payment.success").increment();}
  }
  String id=Db.id();db.jdbc.update("INSERT INTO tb_payment_transaction(id,payment_request_id,order_id,user_id,amount,type,status,success_key,device_id) VALUES(?,?,?,?,?,'PAYMENT',?,?,?)",id,request.getPaymentRequestId(),orderId,user,order.get("amount"),status,status.equals("SUCCESS")?"PAYMENT:"+orderId:null,request.getDeviceId());
  if(status.equals("SUCCESS"))db.jdbc.update("INSERT INTO tb_wallet_ledger(id,user_id,order_id,transaction_id,type,amount) VALUES(?,?,?,?,'PAYMENT',?)",Db.id(),user,orderId,id,order.get("amount"));
  workflow.record(orderId,Objects.toString(order.get("trace_id"),""),"PAYMENT",status);return db.one("SELECT * FROM tb_payment_transaction WHERE id=?",id);
 }
 @Transactional public Map<String,Object> refund(String orderId,long user,PaymentRequest request){
  Map<String,Object> order=orders.lock(orderId);orders.owner(order,user);Map<String,Object> prior=existing(request.getPaymentRequestId(),orderId,user,"REFUND",request.getDeviceId());if(prior!=null)return prior;
  machine.transition(order,OrderStatus.REFUNDING);
  BusinessException.require(db.jdbc.update("UPDATE tb_wallet_account SET available_balance=available_balance+?,version=version+1 WHERE user_id=?",order.get("amount"),user)==1,"钱包不存在");
  String id=Db.id();db.jdbc.update("INSERT INTO tb_payment_transaction(id,payment_request_id,order_id,user_id,amount,type,status,success_key,device_id) VALUES(?,?,?,?,?,'REFUND','SUCCESS',?,?)",id,request.getPaymentRequestId(),orderId,user,order.get("amount"),"REFUND:"+orderId,request.getDeviceId());
  db.jdbc.update("INSERT INTO tb_wallet_ledger(id,user_id,order_id,transaction_id,type,amount) VALUES(?,?,?,?,'REFUND',?)",Db.id(),user,orderId,id,order.get("amount"));
  db.jdbc.update("DELETE FROM tb_issued_ticket WHERE order_id=?",orderId);machine.transition(order,OrderStatus.REFUNDED);orders.release(order);workflow.record(orderId,Objects.toString(order.get("trace_id"),""),"REFUND",id);return db.one("SELECT * FROM tb_payment_transaction WHERE id=?",id);
 }
}
''')
java('mq/TicketEventConsumer','''
package com.ticketing.mq;
import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.InventoryService;
import com.ticketing.order.*;
import com.ticketing.risk.RiskDecisionService;
import com.ticketing.review.ManualReviewService;
import com.ticketing.workflow.service.WorkflowExecutionService;
import com.ticketing.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import io.micrometer.core.instrument.MeterRegistry;
@Service
public class TicketEventConsumer {
 private final Db db;private final OrderService orders;private final OrderStateMachine machine;private final RiskDecisionService risk;private final ManualReviewService review;private final InventoryService inventory;private final WorkflowExecutionService workflow;private final MeterRegistry metrics;
 public TicketEventConsumer(Db db,OrderService orders,OrderStateMachine machine,RiskDecisionService risk,ManualReviewService review,InventoryService inventory,WorkflowExecutionService workflow,MeterRegistry metrics){this.db=db;this.orders=orders;this.machine=machine;this.risk=risk;this.review=review;this.inventory=inventory;this.workflow=workflow;this.metrics=metrics;}
 @Transactional public void consume(String key,String orderId,String type){
  BusinessException.require(key.equals(type+":"+orderId),"消息业务键不匹配");
  BusinessException.require(Arrays.asList("RISK_CHECK","ISSUE","RELEASE").contains(type),"未知消息类型");
  Map<String,Object> order=orders.lock(orderId);
  if(db.one("SELECT * FROM tb_message_consume_record WHERE consumer_group='ticket-events' AND business_key=?",key)!=null){metrics.counter("mq.consume.duplicate").increment();return;}
  db.jdbc.update("INSERT INTO tb_message_consume_record(consumer_group,message_key,business_key,status) VALUES('ticket-events',?,?,'PROCESSING')",key,key);
  if(type.equals("RISK_CHECK")&&order.get("status").equals("CREATED")){
   machine.transition(order,OrderStatus.RISK_CHECKING);Map<String,Object> d=risk.evaluate(order,"RESERVE",order.get("device_id").toString(),order.get("ip").toString());
   if(d.get("decision").equals("PASS"))machine.transition(order,OrderStatus.WAITING_PAYMENT);
   else if(d.get("decision").equals("REVIEW"))review.open(order,d,"RESERVE");
   else {machine.transition(order,OrderStatus.RISK_BLOCKED);orders.release(order);}
   workflow.record(orderId,Objects.toString(order.get("trace_id"),""),"RISK_CHECK",d);
  } else if(type.equals("ISSUE")&&order.get("status").equals("PAID")){
   for(int i=1;i<=Db.number(order,"quantity");i++)db.jdbc.update("INSERT INTO tb_issued_ticket(id,order_id,sequence_no) VALUES(?,?,?)",Db.id(),orderId,i);
   machine.transition(order,OrderStatus.ISSUED);workflow.record(orderId,Objects.toString(order.get("trace_id"),""),"ISSUE",order.get("quantity"));
  }else if(type.equals("RELEASE"))inventory.release(order.get("sku_id").toString(),order.get("user_id"),order.get("reservation_id").toString());
  db.jdbc.update("UPDATE tb_message_consume_record SET status='DONE' WHERE consumer_group='ticket-events' AND message_key=?",key);
 }
}
''')
