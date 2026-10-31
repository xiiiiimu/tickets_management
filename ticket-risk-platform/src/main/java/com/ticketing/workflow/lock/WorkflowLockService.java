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
