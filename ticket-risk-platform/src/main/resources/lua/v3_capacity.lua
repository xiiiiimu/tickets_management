-- Publishes the acceptance capacity and trims stale active entries (lease expired long ago, no live owner).
-- KEYS: 1 cfg, 2 active, 3 waiting. ARGV: 1 cap, 2 activeLimit, 3 stale ms
-- Returns {redis now, active, waiting, done}
local t=redis.call('TIME') local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
redis.call('ZREMRANGEBYSCORE',KEYS[2],'-inf',now-tonumber(ARGV[3]))
redis.call('ZREMRANGEBYSCORE',KEYS[3],'-inf',now)
redis.call('HSET',KEYS[1],'cap',ARGV[1],'activeLimit',ARGV[2],'capAt',now)
return {tostring(now),tostring(redis.call('ZCARD',KEYS[2])),tostring(redis.call('ZCARD',KEYS[3])),redis.call('HGET',KEYS[1],'done') or '0'}
