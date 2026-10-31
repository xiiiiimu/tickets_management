package com.ticketing.workflow.lock;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class WorkflowLockServiceTest {
 @Test @SuppressWarnings("unchecked") void tokenIsRequiredForUnlock(){StringRedisTemplate redis=mock(StringRedisTemplate.class);ValueOperations<String,String> values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);when(values.setIfAbsent(anyString(),eq("owner"),any(Duration.class))).thenReturn(true);WorkflowLockService lock=new WorkflowLockService(redis);assertTrue(lock.tryLock("id","owner"));lock.unlock("id","owner");verify(redis).execute(any(DefaultRedisScript.class),eq(Collections.singletonList(WorkflowLockConstants.LOCK_PREFIX+"id")),eq("owner"));verify(redis,never()).delete(anyString());}
}
