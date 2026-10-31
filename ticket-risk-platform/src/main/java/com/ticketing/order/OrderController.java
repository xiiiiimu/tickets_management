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

@RestController
@RequestMapping("/api")
public class OrderController {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ReservationPaymentPolicy reservationPolicy;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private GatewayPaymentService gateway;
    private final Db db;
    private final OrderService orders;
    private final WalletService wallet;
    private final WorkflowPersistenceService workflow;

    public OrderController(Db db, OrderService orders, WalletService wallet, WorkflowPersistenceService workflow) {
        this.db = db;
        this.orders = orders;
        this.wallet = wallet;
        this.workflow = workflow;
    }

    private long user() {
        return UserHolder.getUser().getId();
    }

    /** HTTP read path still uses Db Map so list/detail JSON keys stay snake_case column names. */
    private Map<String, Object> own(String id) {
        Map<String, Object> order = db.one("SELECT * FROM tb_ticket_order WHERE id=?", id);
        if (order == null) throw new BusinessException(404, "订单不存在");
        if (Db.number(order, "user_id") != user()) throw new BusinessException(403, "无权访问该订单");
        return order;
    }

    @GetMapping("/orders")
    public Result list() {
        return Result.ok(db.jdbc.queryForList("SELECT * FROM tb_ticket_order WHERE user_id=? ORDER BY created_at DESC LIMIT 100", user()));
    }

    @GetMapping("/orders/{id}")
    public Result detail(@PathVariable String id) {
        Map<String, Object> order = own(id);
        order.put("manual_payment_required",reservationPolicy!=null&&reservationPolicy.isV2(id));
        order.put("tickets", db.jdbc.queryForList("SELECT * FROM tb_issued_ticket WHERE order_id=?", id));
        return Result.ok(order);
    }

    @GetMapping("/orders/{id}/trace")
    public Result trace(@PathVariable String id) {
        own(id);
        return Result.ok(workflow.getExecutionChain(id));
    }

    @PostMapping("/orders/{id}/cancel")
    public Result cancel(@PathVariable String id) {
        return Result.ok(orders.cancel(id, user()));
    }

    @PostMapping({"/orders/{id}/pay","/tickets/orders/{id}/pay"})
    public Result pay(@PathVariable String id, @Valid @RequestBody PaymentRequest r, HttpServletRequest http) {
        return Result.ok(wallet.pay(id, user(), r, http.getRemoteAddr()));
    }

    @PostMapping("/orders/{id}/refund")
    public Result refund(@PathVariable String id, @Valid @RequestBody PaymentRequest r) {
        if(gateway!=null&&gateway.hasCaptured(id))return Result.ok(gateway.refund(id,user(),r));
        return Result.ok(wallet.refund(id, user(), r));
    }

    @GetMapping("/wallet")
    public Result wallet() {
        return Result.ok(db.one("SELECT * FROM tb_wallet_account WHERE user_id=?", user()));
    }
}
