package com.ticketing.review;

import com.ticketing.infrastructure.Db;
import com.ticketing.order.*;
import com.ticketing.common.BusinessException;
import com.ticketing.audit.service.AuditLogService;
import com.ticketing.repository.ManualReviewRepository;
import com.ticketing.workflow.service.WorkflowExecutionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ManualReviewService {
    private final ManualReviewRepository reviews;
    private final OrderService orders;
    private final OrderStateMachine machine;
    private final AuditLogService audit;
    private final WorkflowExecutionService workflow;

    public ManualReviewService(ManualReviewRepository reviews, OrderService orders, OrderStateMachine machine,
                               AuditLogService audit, WorkflowExecutionService workflow) {
        this.reviews = reviews;
        this.orders = orders;
        this.machine = machine;
        this.audit = audit;
        this.workflow = workflow;
    }

    public void open(TicketOrder order, Map<String, Object> decision, String phase) {
        reviews.insertPending(Db.id(), order.getId(), decision.get("id"), phase);
        machine.transition(order, OrderStatus.RISK_REVIEW);
        workflow.record(order.getId(), Objects.toString(order.getTraceId(), ""), "MANUAL_REVIEW", decision);
    }

    @Transactional
    public Map<String, Object> decide(String id, String requestId, String action, long reviewer) {
        BusinessException.require(action.equals("APPROVE") || action.equals("REJECT"), "审核动作无效");
        Map<String, Object> ticket = reviews.findById(id);
        if (ticket == null) throw new BusinessException(404, "审核单不存在");
        TicketOrder order = orders.lock(ticket.get("order_id").toString());
        ticket = reviews.findByIdForUpdate(id);
        if (!"PENDING".equals(ticket.get("status"))) {
            BusinessException.require(action.equals(ticket.get("status")) && requestId.equals(ticket.get("request_id")) && Db.number(ticket, "reviewer_id") == reviewer, "审核单已处理");
            return ticket;
        }
        BusinessException.require(order.getExpiresAt().getTime() > System.currentTimeMillis(), "订单已过期");
        BusinessException.require(reviews.tryDecidePending(id, action, requestId, reviewer, ticket.get("version")), "审核状态冲突");
        machine.transition(order, action.equals("APPROVE") ? OrderStatus.WAITING_PAYMENT : OrderStatus.RISK_BLOCKED);
        if (action.equals("REJECT")) orders.release(order);
        Map<String, Object> log = new HashMap<>();
        log.put("workflowId", order.getId());
        log.put("traceId", order.getTraceId());
        log.put("nodeName", "MANUAL_REVIEW");
        log.put("request", Arrays.asList(id, requestId, reviewer));
        log.put("response", action);
        audit.save(log);
        workflow.record(order.getId(), Objects.toString(order.getTraceId(), ""), "MANUAL_REVIEW", action);
        return reviews.findById(id);
    }
}
