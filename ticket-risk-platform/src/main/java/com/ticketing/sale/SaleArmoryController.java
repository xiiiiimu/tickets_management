package com.ticketing.sale;

import com.ticketing.common.Result;
import com.ticketing.common.context.UserHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;
import javax.validation.constraints.*;

/** Additive admin API. Existing /api/admin authorization applies. */
@RestController
@RequestMapping("/api/admin/sessions/{sessionId}/assembly")
@ConditionalOnProperty(name="ticketing.sale.armory.enabled",havingValue="true")
public class SaleArmoryController {
    private final TicketSaleArmory armory;
    public SaleArmoryController(TicketSaleArmory armory){this.armory=armory;}
    @lombok.Data public static class Operation {
        @NotBlank @Size(max=80) private String requestId;
        @NotNull @Min(0) private Long expectedVersion;
    }
    @GetMapping public Result status(@PathVariable String sessionId){return Result.ok(armory.status(sessionId));}
    @GetMapping("/versions") public Result versions(@PathVariable String sessionId){return Result.ok(armory.history(sessionId));}
    @PostMapping public Result assemble(@PathVariable String sessionId,@Valid @RequestBody Operation operation){
        return Result.ok(armory.assemble(sessionId,operation.expectedVersion,operation.requestId,UserHolder.getUser().getId()));
    }
    @PostMapping("/recover") public Result recover(@PathVariable String sessionId,@Valid @RequestBody Operation operation){
        return Result.ok(armory.recover(sessionId,operation.expectedVersion,operation.requestId,UserHolder.getUser().getId()));
    }
}
