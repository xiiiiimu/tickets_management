package com.ticketing.payment;

import com.ticketing.infrastructure.Db;
import com.ticketing.order.*;
import com.ticketing.outbox.OutboxService;
import com.ticketing.repository.IssuedTicketRepository;
import com.ticketing.repository.PaymentRepository;
import com.ticketing.repository.WalletLedgerRepository;
import com.ticketing.repository.WalletRepository;
import com.ticketing.risk.RiskDecisionService;
import com.ticketing.workflow.service.WorkflowExecutionService;
import com.ticketing.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Compatibility wallet pay/refund path. Admission uses fast RiskProfile checks only —
 * deep Agent analysis is never used to gate or rewrite the current order here.
 */
@Service
public class WalletService {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ReservationPaymentPolicy reservationPolicy;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private GatewayPaymentService gateway;
    private final WalletRepository wallets;
    private final PaymentRepository payments;
    private final WalletLedgerRepository ledger;
    private final IssuedTicketRepository tickets;
    private final OrderService orders;
    private final OrderStateMachine machine;
    private final RiskDecisionService risk;
    private final OutboxService outbox;
    private final WorkflowExecutionService workflow;
    private final MeterRegistry metrics;

    public WalletService(WalletRepository wallets, PaymentRepository payments, WalletLedgerRepository ledger,
                         IssuedTicketRepository tickets, OrderService orders,
                         OrderStateMachine machine, RiskDecisionService risk,
                         OutboxService outbox, WorkflowExecutionService workflow, MeterRegistry metrics) {
        this.wallets = wallets;
        this.payments = payments;
        this.ledger = ledger;
        this.tickets = tickets;
        this.orders = orders;
        this.machine = machine;
        this.risk = risk;
        this.outbox = outbox;
        this.workflow = workflow;
        this.metrics = metrics;
    }

    private Map<String, Object> existing(String request, String order, long user, String type, String device) {
        Map<String, Object> p = payments.findByPaymentRequestId(request);
        if (p != null)
            BusinessException.require(order.equals(p.get("order_id")) && Db.number(p, "user_id") == user && type.equals(p.get("type")) && device.equals(p.get("device_id")), "支付请求标识与原请求不一致");
        return p;
    }

    @Transactional
    public Map<String, Object> pay(String orderId, long user, PaymentRequest request, String ip) {
        TicketOrder order = orders.lock(orderId);
        orders.owner(order, user);
        if(reservationPolicy!=null)reservationPolicy.claim(order,request,"WALLET_PAY");
        Map<String, Object> prior = existing(request.getPaymentRequestId(), orderId, user, "PAYMENT", request.getDeviceId());
        if (prior != null) return prior;
        BusinessException.require("WAITING_PAYMENT".equals(order.getStatus()), "订单尚不可支付或已支付");
        BusinessException.require(order.getExpiresAt().getTime() > System.currentTimeMillis(), "支付已超时");
        // Profile / blacklist admission only — no deep evaluate, no order RISK_* transitions.
        boolean v2=reservationPolicy!=null&&reservationPolicy.isV2(orderId);
        if(v2)reservationPolicy.assertQualified(order);
        else risk.fast(user, request.getDeviceId(), ip);
        String status = "SUCCESS";
        if (!wallets.tryDebit(user, order.getAmount())) {
            status = "FAILED";
            metrics.counter("payment.failed").increment();
        } else {
            if(v2)reservationPolicy.startPaying(order);
            else machine.transition(order, OrderStatus.PAYING);
            machine.transition(order, OrderStatus.PAID);
            outbox.append(orderId, "ISSUE");
            metrics.counter("payment.success").increment();
        }
        String id = Db.id();
        payments.insert(id, request.getPaymentRequestId(), orderId, user, order.getAmount(), "PAYMENT", status,
                status.equals("SUCCESS") ? "PAYMENT:" + orderId : null, request.getDeviceId());
        if (status.equals("SUCCESS"))
            ledger.insert(Db.id(), user, orderId, id, "PAYMENT", order.getAmount());
        workflow.record(orderId, Objects.toString(order.getTraceId(), ""), "PAYMENT", status);
        return payments.findById(id);
    }

    @Transactional
    public Map<String, Object> refund(String orderId, long user, PaymentRequest request) {
        TicketOrder order = orders.lock(orderId);
        orders.owner(order, user);
        BusinessException.require(gateway==null||!gateway.hasCaptured(orderId),"网关支付必须走原通道退款");
        if(reservationPolicy!=null)reservationPolicy.claim(order,request,"WALLET_REFUND");
        Map<String, Object> prior = existing(request.getPaymentRequestId(), orderId, user, "REFUND", request.getDeviceId());
        if (prior != null) return prior;
        machine.transition(order, OrderStatus.REFUNDING);
        BusinessException.require(wallets.tryCredit(user, order.getAmount()), "钱包不存在");
        String id = Db.id();
        payments.insert(id, request.getPaymentRequestId(), orderId, user, order.getAmount(), "REFUND", "SUCCESS",
                "REFUND:" + orderId, request.getDeviceId());
        ledger.insert(Db.id(), user, orderId, id, "REFUND", order.getAmount());
        tickets.deleteByOrderId(orderId);
        machine.transition(order, OrderStatus.REFUNDED);
        orders.release(order);
        workflow.record(orderId, Objects.toString(order.getTraceId(), ""), "REFUND", id);
        return payments.findById(id);
    }
}
