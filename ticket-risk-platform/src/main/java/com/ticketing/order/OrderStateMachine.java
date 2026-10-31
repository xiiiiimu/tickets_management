package com.ticketing.order;

import java.util.*;
import com.ticketing.common.BusinessException;
import com.ticketing.repository.OrderRepository;
import org.springframework.stereotype.Service;

@Service
public class OrderStateMachine {
    private static final Map<OrderStatus, Set<OrderStatus>> EDGES = new EnumMap<>(OrderStatus.class);

    static {
        // Main path: orders are inserted as WAITING_PAYMENT after fast admission.
        // Historical RISK_* edges retained for legacy rows / manual-review compatibility; new main chain does not enter them.
        edge("CREATED", "WAITING_PAYMENT", "RISK_CHECKING", "CANCELLED", "PAY_TIMEOUT");
        edge("RISK_CHECKING", "RISK_REVIEW", "RISK_BLOCKED", "WAITING_PAYMENT", "PAY_TIMEOUT");
        edge("RISK_REVIEW", "WAITING_PAYMENT", "RISK_BLOCKED", "PAY_TIMEOUT");
        edge("WAITING_PAYMENT", "PAYING", "PAY_TIMEOUT", "CANCELLED");
        // Only verified gateway FAILED/CLOSED may use the recovery edges; expire() explicitly excludes PAYING.
        edge("PAYING", "PAID", "WAITING_PAYMENT", "PAY_TIMEOUT");
        edge("PAID", "ISSUED", "REFUNDING");
        edge("ISSUED", "REFUNDING");
        edge("REFUNDING", "REFUNDED");
    }

    private static void edge(String from, String... to) {
        Set<OrderStatus> s = new HashSet<>();
        for (String t : to) s.add(OrderStatus.valueOf(t));
        EDGES.put(OrderStatus.valueOf(from), s);
    }

    public static boolean permits(OrderStatus from, OrderStatus to) {
        return EDGES.getOrDefault(from, Collections.emptySet()).contains(to);
    }

    private final OrderRepository orders;

    public OrderStateMachine(OrderRepository orders) {
        this.orders = orders;
    }

    public void transition(TicketOrder order, OrderStatus next) {
        OrderStatus previous = OrderStatus.valueOf(order.getStatus());
        BusinessException.require(permits(previous, next), "订单状态不允许此操作");
        BusinessException.require(
                orders.tryTransition(order.getId(), previous.name(), order.getVersion(), next.name()),
                "订单已变更，请重试");
        order.setStatus(next.name());
        order.setVersion(order.getVersion() + 1);
    }
}
