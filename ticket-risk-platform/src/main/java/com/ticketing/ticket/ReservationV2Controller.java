package com.ticketing.ticket;
import com.ticketing.common.*;
import com.ticketing.common.context.UserHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.Map;
import java.util.LinkedHashMap;

@RestController
@RequestMapping("/api/tickets/reservations")
@ConditionalOnProperty(name="ticketing.reservations-v2.enabled",havingValue="true")
public class ReservationV2Controller {
    private final ReservationV2Service service;
    private final ReservationIngress ingress;
    public ReservationV2Controller(ReservationV2Service service,ReservationIngress ingress){this.service=service;this.ingress=ingress;}
    @PostMapping public ResponseEntity<Result> create(@Valid @RequestBody ReserveRequest request,HttpServletRequest http){
        if(!request.getUserId().equals(UserHolder.getUser().getId()))throw new BusinessException(403,"请求用户与登录身份不一致");
        return ResponseEntity.accepted().body(Result.ok(ingress.receive(request,http.getRemoteAddr())));
    }
    @GetMapping("/by-request") public Result byRequest(@RequestParam String requestId,@RequestParam(required=false) Long receivedAt){return Result.ok(ingress.status(requestId,UserHolder.getUser().getId(),receivedAt));}
    @GetMapping("/{reservationId}") public Result status(@PathVariable String reservationId){return Result.ok(service.status(reservationId,UserHolder.getUser().getId()));}
    @GetMapping("/attempts/{attemptId}") public Result attempt(@PathVariable String attemptId){return Result.ok(service.attemptStatus(attemptId,UserHolder.getUser().getId()));}
    @ExceptionHandler(AttemptAdmissionException.class) public ResponseEntity<Result> attemptError(AttemptAdmissionException error){
        Map<String,Object> detail=new LinkedHashMap<>();detail.put("code",error.code);detail.put("reliableAccepted",false);detail.put("retryWithSameRequestId",true);
        return ResponseEntity.status(error.status).body(new Result(false,error.getMessage(),detail,null));
    }
}
