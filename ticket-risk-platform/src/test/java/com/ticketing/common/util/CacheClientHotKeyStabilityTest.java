package com.ticketing.common.util;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;

/**
 * 稳定性：热点 Key 并发查询时互斥回源，仅一次命中 DB。
 */
@ExtendWith(MockitoExtension.class)
class CacheClientHotKeyStabilityTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private final Map<String, String> store = new ConcurrentHashMap<>();
    private CacheClient cacheClient;

    @BeforeEach
    void setUp() {
        store.clear();
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        lenient().when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(0);
                    if (store.putIfAbsent(key, inv.getArgument(1)) == null) {
                        return true;
                    }
                    return false;
                });
        lenient().when(stringRedisTemplate.delete(anyString())).thenAnswer(inv -> {
            store.remove(inv.getArgument(0));
            return true;
        });
        cacheClient = new CacheClient(stringRedisTemplate);
    }

    @Test
    void stability_hotKeyConcurrentQuery_onlyOneThreadHitsDb() throws InterruptedException {
        AtomicInteger dbHits = new AtomicInteger();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    cacheClient.queryWithPassThrough(
                            "sku:",
                            1L,
                            DemoSku.class,
                            id -> {
                                dbHits.incrementAndGet();
                                DemoSku p = new DemoSku();
                                p.setId(id);
                                p.setName("演出票档");
                                return p;
                            },
                            10L,
                            TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, dbHits.get(), "并发击穿场景应仅一次回源 MySQL");
        assertEquals(store.get("sku:1"), JSONUtil.toJsonStr(
                JSONUtil.toBean(store.get("sku:1"), DemoSku.class)));
    }

    static class DemoSku {
        private Long id;
        private String name;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
