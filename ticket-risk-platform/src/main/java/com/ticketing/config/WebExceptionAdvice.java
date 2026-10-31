package com.ticketing.config;
import com.ticketing.common.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import lombok.extern.slf4j.Slf4j;
@Slf4j @RestControllerAdvice
public class WebExceptionAdvice {
 @ExceptionHandler(BusinessException.class) public ResponseEntity<Result> business(BusinessException e){return ResponseEntity.status(e.status).body(Result.fail(e.getMessage()));}
 @ExceptionHandler({MethodArgumentNotValidException.class,IllegalArgumentException.class}) public ResponseEntity<Result> invalid(Exception e){return ResponseEntity.badRequest().body(Result.fail("参数格式错误"));}
 @ExceptionHandler(Exception.class) public ResponseEntity<Result> unknown(Exception e){log.error("Request failed",e);return ResponseEntity.status(503).body(Result.fail("服务暂不可用，请使用相同请求标识重试"));}
}
