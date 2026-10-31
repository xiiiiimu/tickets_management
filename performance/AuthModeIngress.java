package benchmark;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.ticketing.common.context.UserHolder;
import com.ticketing.common.dto.UserDTO;
import com.ticketing.common.util.RefreshTokenInterceptor;
import com.ticketing.common.util.RedisConstants;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.*;
import javax.servlet.http.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Performance-tool classpath ONLY. Not part of Maven sources or application JAR. */
public class AuthModeIngress {
 public static void main(String[] args){SpringApplication.run(new Class<?>[]{FastIngressApplication.class,Fixture.class},args);}
 @Configuration @Import(Control.class) public static class Fixture {
  @Bean static Modes modes(Environment env){return new Modes(env);}
  @Bean static BeanPostProcessor testAuthWrappers(Modes modes,org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redis){return new BeanPostProcessor(){
   @SuppressWarnings("unchecked") public Object postProcessAfterInitialization(Object bean,String name){
    if(bean instanceof AbstractHandlerMapping){List<HandlerInterceptor> list=(List<HandlerInterceptor>)ReflectionTestUtils.getField(bean,"adaptedInterceptors");for(int i=0;i<list.size();i++)list.set(i,wrap(list.get(i),modes,redis.getObject()));}return bean;
   }
  };}
 }
 static HandlerInterceptor wrap(HandlerInterceptor original,Modes modes,StringRedisTemplate redis){
  if(original instanceof SwitchRefresh)return original;
  if(original instanceof RefreshTokenInterceptor)return new SwitchRefresh(original,modes,redis);
  if(original instanceof MappedInterceptor){MappedInterceptor m=(MappedInterceptor)original;ReflectionTestUtils.setField(m,"interceptor",wrap(m.getInterceptor(),modes,redis));}
  if(original.getClass().getName().equals("benchmark.FastIngressDiagnostics$TimedInterceptor")){HandlerInterceptor delegate=(HandlerInterceptor)ReflectionTestUtils.getField(original,"delegate");ReflectionTestUtils.setField(original,"delegate",wrap(delegate,modes,redis));}
  return original;
 }
 public static class Modes {
  final AtomicReference<String> mode=new AtomicReference<>("A");final String prefix;final Map<String,LongAdder> calls=new LinkedHashMap<>();
  Modes(Environment env){
   if(!env.getProperty("fast.benchmark.isolated",Boolean.class,false)||!"127.0.0.1".equals(env.getProperty("server.address"))||!"26379".equals(env.getProperty("spring.redis.port")))throw new IllegalStateException("Auth bypass restricted to explicit local isolated fixture");
   prefix="fast-user-"+env.getRequiredProperty("fast.benchmark.owner")+"-";for(String name:Arrays.asList("A","B","C"))calls.put(name,new LongAdder());
  }
  Map<String,Object> snapshot(){Map<String,Object> m=new LinkedHashMap<>();m.put("authMode",mode.get());Map<String,Long> counts=new LinkedHashMap<>();calls.forEach((k,v)->counts.put(k,v.sum()));m.put("calls",counts);m.put("scope","TEST_CLASSPATH_ONLY_POST_RESERVATIONS");return m;}
 }
 public static class SwitchRefresh extends RefreshTokenInterceptor {
  final HandlerInterceptor original;final Modes modes;final StringRedisTemplate redis;
  SwitchRefresh(HandlerInterceptor original,Modes m,StringRedisTemplate r){super(r);this.original=original;modes=m;redis=r;}
  public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler)throws Exception{
   if(!"POST".equals(request.getMethod())||!"/api/tickets/reservations".equals(request.getRequestURI()))return original.preHandle(request,response,handler);
   String mode=modes.mode.get();modes.calls.get(mode).increment();if(mode.equals("A"))return original.preHandle(request,response,handler);
   UserHolder.removeUser();String token=request.getHeader("authorization");if(StrUtil.isBlank(token))return true;
   if(mode.equals("B")){
    Map<Object,Object> fields=redis.opsForHash().entries(RedisConstants.LOGIN_USER_KEY+token);if(!fields.isEmpty())UserHolder.saveUser(BeanUtil.fillBeanWithMap(fields,new UserDTO(),false));return true;
   }
   if(token.startsWith(modes.prefix))try{
    String suffix=token.substring(modes.prefix.length());int i=Integer.parseInt(suffix);if(i>=0&&i<128&&suffix.equals(Integer.toString(i))){UserDTO user=new UserDTO();user.setId(100000L+i);user.setRole("USER");user.setNickName("fast-test");UserHolder.saveUser(user);}
   }catch(NumberFormatException ignored){}return true;
  }
  public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception e)throws Exception{original.afterCompletion(request,response,handler,e);}
 }
 @RestController public static class Control {
  final Modes modes;Control(Modes m){modes=m;}
  @PostMapping("/api/admin/fast-auth/mode") public Map<String,Object> change(@RequestParam("mode") String mode){if(!Arrays.asList("A","B","C").contains(mode))throw new IllegalArgumentException("A/B/C only");modes.mode.set(mode);return modes.snapshot();}
  @GetMapping("/api/admin/fast-auth/mode") public Map<String,Object> current(){return modes.snapshot();}
 }
}
