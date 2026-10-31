package com.ticketing.order;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ticketing.infrastructure.Db;
import com.ticketing.common.BusinessException;
import com.ticketing.repository.jdbc.JdbcOrderRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

class OrderStateMachineTest {
    @Test
    void terminalStatesRejectReplay() {
        for (OrderStatus from : Arrays.asList(OrderStatus.CANCELLED, OrderStatus.REFUNDED, OrderStatus.RISK_BLOCKED, OrderStatus.PAY_TIMEOUT))
            for (OrderStatus to : OrderStatus.values())
                assertFalse(OrderStateMachine.permits(from, to));
    }

    @Test
    void paymentCannotSkipRisk() {
        assertFalse(OrderStateMachine.permits(OrderStatus.CREATED, OrderStatus.PAID));
        assertFalse(OrderStateMachine.permits(OrderStatus.RISK_REVIEW, OrderStatus.PAYING));
        assertTrue(OrderStateMachine.permits(OrderStatus.CREATED, OrderStatus.WAITING_PAYMENT));
        assertTrue(OrderStateMachine.permits(OrderStatus.WAITING_PAYMENT, OrderStatus.PAYING));
    }

    @Test
    void compareAndSwapRejectsStaleVersion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Db db = new Db(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        TicketOrder order = new TicketOrder();
        order.setId("o1");
        order.setStatus("CREATED");
        order.setVersion(0);
        assertThrows(BusinessException.class,
                () -> new OrderStateMachine(new JdbcOrderRepository(db)).transition(order, OrderStatus.RISK_CHECKING));
        assertEquals("CREATED", order.getStatus());
    }
}
