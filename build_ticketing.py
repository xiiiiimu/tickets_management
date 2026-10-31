from pathlib import Path
root=Path(__file__).parent
def put(path,s):
 p=root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s.strip()+'\n',encoding='utf-8')
def java(path,s): put('ticket-risk-platform/src/main/java/com/ticketing/'+path+'.java',s)

java('TicketPlatformApplication','''
package com.ticketing;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
@EnableScheduling
@SpringBootApplication
public class TicketPlatformApplication {
 public static void main(String[] args) { SpringApplication.run(TicketPlatformApplication.class,args); }
}
''')
java('config/AuthInterceptorConstants','''
package com.ticketing.config;
public final class AuthInterceptorConstants {
 private AuthInterceptorConstants() {}
 public static final String[] LOGIN_EXCLUDE_PATTERNS={"/api/user/login","/api/user/code","/error","/actuator/health","/actuator/prometheus"};
 public static boolean isPublicPath(String method,String uri) {
  return ("POST".equals(method) && ("/api/user/login".equals(uri)||"/api/user/code".equals(uri)))
    || ("GET".equals(method) && (uri.startsWith("/api/events") || uri.equals("/actuator/health") || uri.equals("/actuator/prometheus")));
 }
}
''')
java('config/MvcConfig','''
package com.ticketing.config;
import com.ticketing.common.util.*;
import com.ticketing.common.context.UserHolder;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.*;
@Configuration
public class MvcConfig implements WebMvcConfigurer {
 private final StringRedisTemplate redis;
 public MvcConfig(StringRedisTemplate redis){this.redis=redis;}
 public void addInterceptors(InterceptorRegistry r){
  r.addInterceptor(new RefreshTokenInterceptor(redis)).order(0);
  r.addInterceptor(new LoginInterceptor()).excludePathPatterns(AuthInterceptorConstants.LOGIN_EXCLUDE_PATTERNS).order(1);
  r.addInterceptor(new HandlerInterceptor(){public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object h){
   if(UserHolder.getUser()==null || !"ADMIN".equals(UserHolder.getUser().getRole())){res.setStatus(403);return false;}return true;
  }}).addPathPatterns("/api/admin/**","/actuator/metrics/**").order(2);
 }
}
''')
java('config/TicketDevProperties','''
package com.ticketing.config;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
@Data @Component @ConfigurationProperties(prefix="ticketing.dev")
public class TicketDevProperties { private boolean exposeLoginCode=false; }
''')
java('common/BusinessException','''
package com.ticketing.common;
public class BusinessException extends RuntimeException {
 public final int status;
 public BusinessException(int status,String message){super(message);this.status=status;}
 public static void require(boolean test,String message){if(!test)throw new BusinessException(409,message);}
}
''')
java('config/WebExceptionAdvice','''
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
''')
java('infrastructure/TraceFilter','''
package com.ticketing.infrastructure;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
@Component
public class TraceFilter extends OncePerRequestFilter {
 protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
  String trace=UUID.randomUUID().toString();MDC.put("traceId",trace);res.setHeader("X-Trace-Id",trace);
  try{chain.doFilter(req,res);}finally{MDC.clear();}
 }
}
''')
java('infrastructure/Db','''
package com.ticketing.infrastructure;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
@Component
public class Db {
 public final JdbcTemplate jdbc; private final ObjectMapper mapper;
 public Db(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
 public Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.get(0);}
 public String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException(e);}}
 public static String id(){return UUID.randomUUID().toString();}
 public static long number(Map<String,Object> row,String key){Object v=row.get(key);return v==null?0:((Number)v).longValue();}
}
''')

# Preserve the original login/token and audit implementations with corrected configuration.
p=root/'ticket-risk-platform/src/main/java/com/ticketing/user/service/impl/UserServiceImpl.java'
s=p.read_text(encoding='utf-8').replace('CreditDevProperties','TicketDevProperties').replace('creditDevProperties','ticketDevProperties')
s=s.replace('log.info("[LOGIN-CODE] phone={} code={}", phone, code);','')
s=s.replace('//3.符合，生成验证码','if (!ticketDevProperties.isExposeLoginCode()) { return Result.fail("短信通道未配置；本地演示请启用 ticketing.dev.expose-login-code"); }\n        //3.符合，生成验证码')
p.write_text(s,encoding='utf-8')
p=root/'ticket-risk-platform/src/main/java/com/ticketing/user/controller/UserController.java';s=p.read_text(encoding='utf-8').replace('@RequestMapping("/user")','@RequestMapping("/api/user")');s='\n'.join(l for l in s.splitlines() if '// TODO' not in l);p.write_text(s,encoding='utf-8')

java('workflow/lock/WorkflowLockService','''
package com.ticketing.workflow.lock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.Collections;
@Service
public class WorkflowLockService {
 private final StringRedisTemplate redis;
 private final DefaultRedisScript<Long> unlock=new DefaultRedisScript<>();
 public WorkflowLockService(StringRedisTemplate redis){this.redis=redis;unlock.setLocation(new ClassPathResource("lua/unlock_with_token.lua"));unlock.setResultType(Long.class);}
 public boolean tryLock(String id,String token){return token!=null && Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(WorkflowLockConstants.LOCK_PREFIX+id,token,Duration.ofSeconds(60)));}
 public void unlock(String id,String token){redis.execute(unlock,Collections.singletonList(WorkflowLockConstants.LOCK_PREFIX+id),token);}
 public boolean isHeldBy(String id,String token){return token!=null && token.equals(redis.opsForValue().get(WorkflowLockConstants.LOCK_PREFIX+id));}
}
''')
java('workflow/service/WorkflowExecutionService','''
package com.ticketing.workflow.service;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service
public class WorkflowExecutionService {
 private final WorkflowPersistenceService persistence;
 public WorkflowExecutionService(WorkflowPersistenceService persistence){this.persistence=persistence;}
 @Transactional public void record(String orderId,String traceId,String node,Object state){
  persistence.initWorkflowIfAbsent(orderId,traceId,null,null);
  persistence.beginNode(orderId,node,"java",traceId,state);
  persistence.completeNode(orderId,node,traceId,"java",true,state,null,null,0,0);
  persistence.saveCheckpoint(orderId,node,state,Collections.singletonList(node),0);
  persistence.finishWorkflow(orderId,"ISSUE".equals(node)?"SUCCESS":"RUNNING",traceId,state,0);
 }
}
''')
put('ticket-risk-platform/src/main/resources/lua/unlock_with_token.lua',"if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end\nreturn 0")
put('ticket-risk-platform/src/test/resources/application.yml','''
ticketing:
  mq:
    enabled: false
''')
