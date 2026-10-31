-- Persistent marker is retained so duplicate compensation cannot add stock twice.
-- KEYS: stock, user quantity, reservation, pending. ARGV: reservationId.
if redis.call('HGET',KEYS[3],'state') ~= 'HELD' then return 0 end
if redis.call('EXISTS',KEYS[1]) == 0 or redis.call('EXISTS',KEYS[2]) == 0 then return -1 end
local q=tonumber(redis.call('HGET',KEYS[3],'quantity'))
redis.call('INCRBY',KEYS[1],q)
redis.call('DECRBY',KEYS[2],q)
redis.call('HSET',KEYS[3],'state','RELEASED')
redis.call('ZREM',KEYS[4],ARGV[1])
return 1
