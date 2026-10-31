package com.ticketing.config;

import io.micrometer.core.instrument.*;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Fixed labels only. Runs outside transaction advice to include connection acquisition and commit. */
@Aspect
@Component
@Order(100)
@ConditionalOnProperty(name="ticketing.metrics.business.enabled",havingValue="true",matchIfMissing=true)
public class BusinessMetricsAspect {
    private final Map<String,io.micrometer.core.instrument.Timer> timers=new HashMap<>();
    public BusinessMetricsAspect(MeterRegistry registry){
        for(String operation:Arrays.asList("admission","features","risk_investigation","redis_reserve","mysql_stock",
                "order_transaction","auto_payment","mq_consume","inventory_release",
                "sale_admission","sale_intent_gate","sale_reserve_gate","sale_runtime_plan","sale_runtime_fence",
                "reservation_classify","reservation_prepare","reservation_commit","wallet_payment"))
            for(String outcome:Arrays.asList("success","failure"))
                timers.put(operation+outcome,io.micrometer.core.instrument.Timer.builder("ticketing.operation.duration")
                    .tags("operation",operation,"outcome",outcome)
                    .serviceLevelObjectives(Duration.ofMillis(1),Duration.ofMillis(10),Duration.ofMillis(100),
                            Duration.ofMillis(500),Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(5),Duration.ofSeconds(10))
                    .register(registry));
    }
    @Around("execution(* com.ticketing.sale.TicketSaleArmory.admissionPlan(..)) || "
          + "execution(* com.ticketing.sale.TicketSaleArmory.runtimePlan(..)) || "
          + "execution(* com.ticketing.sale.TicketSaleArmory.runtimeSku(..)) || "
          + "execution(* com.ticketing.risk.RiskDecisionService.classifyReservation(..)) || "
          + "execution(* com.ticketing.ticket.ReservationV2Transactions.prepare(..)) || "
          + "execution(* com.ticketing.ticket.ReservationV2Transactions.reserve(..)) || "
          + "execution(* com.ticketing.payment.WalletService.pay(..)) || "
          + "execution(* com.ticketing.inventory.InventoryService.reserveRuntime(..)) || "
          + "execution(* com.ticketing.sale.TicketSaleArmory.assertCanCreate(..)) || "
          + "execution(* com.ticketing.sale.TicketSaleArmory.assertCanReserve(..)) || "
          + "execution(* com.ticketing.risk.RiskDecisionService.fast(..)) || "
          + "execution(* com.ticketing.risk.RiskDecisionService.admit(..)) || "
          + "execution(* com.ticketing.risk.RiskFeatureService.collect(..)) || "
          + "execution(* com.ticketing.risk.RiskDecisionService.investigate(..)) || "
          + "execution(* com.ticketing.inventory.InventoryService.reserve(..)) || "
          + "execution(* com.ticketing.repository.jdbc.JdbcTicketInventoryRepository.tryDecreaseStockForReservation(..)) || "
          + "execution(* com.ticketing.ticket.ReservationTransactionService.reserve(..)) || "
          + "execution(* com.ticketing.payment.AutoPaymentService.attempt(..)) || "
          + "execution(* com.ticketing.mq.TicketEventConsumer.consume(..)) || "
          + "execution(* com.ticketing.inventory.InventoryService.release(..))")
    public Object measure(ProceedingJoinPoint call) throws Throwable {
        String method=call.getSignature().getName(),operation;
        switch(method){
            case "runtimePlan":operation="sale_admission";break;
            case "runtimeSku":operation="sale_runtime_fence";break;
            case "classifyReservation":operation="reservation_classify";break;
            case "prepare":operation="reservation_prepare";break;
            case "pay":operation="wallet_payment";break;
            case "reserveRuntime":operation="redis_reserve";break;
            case "reserve":operation=call.getTarget() instanceof com.ticketing.ticket.ReservationV2Transactions?"reservation_commit":
                call.getTarget() instanceof com.ticketing.inventory.InventoryService?"redis_reserve":"order_transaction";break;
            case "admissionPlan":operation="sale_admission";break;
            case "assertCanCreate":operation="sale_intent_gate";break;
            case "assertCanReserve":operation="sale_reserve_gate";break;
            case "fast":case "admit":operation="admission";break;case "collect":operation="features";break;
            case "investigate":operation="risk_investigation";break;case "attempt":operation="auto_payment";break;
            case "consume":operation="mq_consume";break;case "release":operation="inventory_release";break;
            case "tryDecreaseStockForReservation":operation="mysql_stock";break;
            default:operation=call.getTarget() instanceof com.ticketing.inventory.InventoryService?"redis_reserve":"order_transaction";
        }
        long begin=System.nanoTime();String outcome="success";
        try{return call.proceed();}catch(Throwable error){outcome="failure";throw error;}
        finally{timers.get(operation+outcome).record(System.nanoTime()-begin,TimeUnit.NANOSECONDS);}
    }
}
