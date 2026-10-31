package com.ticketing.notification;
import com.ticketing.common.Result;
import com.ticketing.common.context.UserHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/notifications")
@ConditionalOnProperty(name="ticketing.reservations-v2.enabled",havingValue="true")
public class OrderNotificationController {
    private final OrderNotificationService notices;
    public OrderNotificationController(OrderNotificationService notices){this.notices=notices;}
    @GetMapping public Result list(@RequestParam(defaultValue="false")boolean unread){return Result.ok(notices.list(UserHolder.getUser().getId(),unread));}
    @PostMapping("/{id}/read") public Result read(@PathVariable String id){return Result.ok(notices.read(id,UserHolder.getUser().getId()));}
}
