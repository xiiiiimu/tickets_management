package com.ticketing.common.util;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.TimeoutUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.ticketing.common.util.RedisConstants.*;


@Slf4j
@Component
public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;
    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }



    public void set(String key, Object value, Long time, TimeUnit unit){
        long ttlSeconds = applyJitter(time, unit);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), ttlSeconds, TimeUnit.SECONDS);
    }
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));

    }
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        //1. 从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在
        if(StrUtil.isNotBlank(json)) {
            //3.存在，直接返回
            return JSONUtil.toBean(json, type);

        }
        //判断命中是否是空值
        if (json != null){
            //返回一个错误信息
            return null;
        }
        //4.不存在，根据id查询数据库 — 热点 Key 互斥回源
        String lockKey = LOCK_CACHE_REBUILD_KEY + key;
        boolean locked = tryLock(lockKey);
        try {
            if (locked) {
                String retryJson = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(retryJson)) {
                    return JSONUtil.toBean(retryJson, type);
                }
                if (retryJson != null) {
                    return null;
                }
                R loaded = dbFallback.apply(id);
                if (loaded == null) {
                    stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                this.set(key, loaded, time, unit);
                return loaded;
            }
            for (int attempt=0; attempt<200; attempt++) {
                try { Thread.sleep(10L); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new com.ticketing.common.BusinessException(503,"缓存等待被中断");
                }
                String waited=stringRedisTemplate.opsForValue().get(key);
                if(waited!=null) return waited.isEmpty()?null:JSONUtil.toBean(waited,type);
            }
            throw new com.ticketing.common.BusinessException(503,"缓存正在重建，请稍后重试");
        } finally {
            if (locked) {
                unlock(lockKey);
            }
        }
    }

    /** TTL ±10% 抖动，缓解缓存雪崩 */
    static long applyJitter(long time, TimeUnit unit) {
        if (time <= 0) {
            return time;
        }
        long baseSeconds = unit.toSeconds(time);
        double factor = 0.9 + ThreadLocalRandom.current().nextDouble(0.2);
        return Math.max(1L, Math.round(baseSeconds * factor));
    }

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.ticketing.infrastructure.TaskPools taskPools;

    public <R, ID> R queryWithLogicalExpire(
            String keyprefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit){
        String key = keyprefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 空值缓存：写入 ""，避免不存在的数据反复穿透 DB
        if (json != null && json.isEmpty()) {
            return null;
        }
        if (StrUtil.isBlank(json)) {
            R loaded = dbFallback.apply(id);
            if (loaded == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            this.setWithLogicalExpire(key, loaded, time, unit);
            return loaded;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }

        String lockKey = LOCK_CACHE_REBUILD_KEY + key;
        boolean isLock = tryLock(lockKey);
        if(isLock){
            final String rebuildToken=lockTokens.get().remove(lockKey);
            Runnable rebuild=()->{
                try {
                    R r1 = dbFallback.apply(id);
                    if (r1 == null) {
                        stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                        return;
                    }
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally{
                    unlock(lockKey,rebuildToken);
                }

            };
            try { if(taskPools!=null)taskPools.execute("cache",rebuild);else rebuild.run(); } catch(RuntimeException e){unlock(lockKey,rebuildToken);throw e;}

        }
        return r;

    }

    private final ThreadLocal<java.util.Map<String,String>> lockTokens=ThreadLocal.withInitial(java.util.HashMap::new);
    private boolean tryLock(String key){
        String token=java.util.UUID.randomUUID().toString();
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, token, 10,TimeUnit.SECONDS);
        if(Boolean.TRUE.equals(flag))lockTokens.get().put(key,token);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        unlock(key,lockTokens.get().remove(key));
        if(lockTokens.get().isEmpty())lockTokens.remove();
    }
    private void unlock(String key,String token){
        if(token==null)return;
        org.springframework.data.redis.core.script.DefaultRedisScript<Long> script=new org.springframework.data.redis.core.script.DefaultRedisScript<>();
        script.setScriptText("if redis.call('GET',KEYS[1]) == ARGV[1] then return redis.call('DEL',KEYS[1]) end return 0");
        script.setResultType(Long.class);
        stringRedisTemplate.execute(script,java.util.Collections.singletonList(key),token);
    }
}

