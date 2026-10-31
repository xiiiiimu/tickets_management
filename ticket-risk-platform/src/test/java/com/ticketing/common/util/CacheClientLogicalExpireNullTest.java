package com.ticketing.common.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheClientLogicalExpireNullTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private CacheClient cacheClient;

    @BeforeEach
    void setUp() {
        cacheClient = new CacheClient(stringRedisTemplate);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void queryWithLogicalExpire_nullProduct_writesEmptyCacheAndSkipsSecondDbHit() {
        String key = "ticket:sku:999";
        AtomicInteger dbHits = new AtomicInteger();
        when(valueOperations.get(key)).thenReturn(null, "");

        String result1 = cacheClient.queryWithLogicalExpire(
                "ticket:sku:",
                999L,
                String.class,
                id -> {
                    dbHits.incrementAndGet();
                    return null;
                },
                30L,
                TimeUnit.MINUTES);
        String result2 = cacheClient.queryWithLogicalExpire(
                "ticket:sku:",
                999L,
                String.class,
                id -> {
                    dbHits.incrementAndGet();
                    return null;
                },
                30L,
                TimeUnit.MINUTES);

        assertNull(result1);
        assertNull(result2);
        assertEquals(1, dbHits.get());
        verify(valueOperations).set(eq(key), eq(""), eq(RedisConstants.CACHE_NULL_TTL), eq(TimeUnit.MINUTES));
    }
}
