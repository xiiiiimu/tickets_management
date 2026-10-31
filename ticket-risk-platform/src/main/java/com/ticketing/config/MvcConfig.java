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
