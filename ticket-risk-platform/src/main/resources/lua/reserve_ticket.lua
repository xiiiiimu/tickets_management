-- All keys share the SKU hash tag. KEYS: config, stock, user quantity, reservation, pending.
-- ARGV: fingerprint, quantity, reservationId, expiryMillis, userId.
local prior = redis.call('HGET', KEYS[4], 'fingerprint')
if prior then
 if prior ~= ARGV[1] then return -4 end
 if redis.call('HGET', KEYS[4], 'state') == 'RELEASED' then return -5 end
 return 2
end
local q=tonumber(ARGV[2])
if not q or q < 1 or q ~= math.floor(q) then return -6 end
local now=redis.call('TIME'); local ms=tonumber(now[1])*1000+math.floor(tonumber(now[2])/1000)
local start=tonumber(redis.call('HGET',KEYS[1],'start') or '0')
local finish=tonumber(redis.call('HGET',KEYS[1],'end') or '0')
if redis.call('HGET',KEYS[1],'status') ~= 'ON_SALE' or ms < start or ms >= finish then return -1 end
local limit=tonumber(redis.call('HGET',KEYS[1],'limit') or '0')
local purchased=tonumber(redis.call('GET',KEYS[3]) or '0')
if purchased+q > limit then return -2 end
local stock=tonumber(redis.call('GET',KEYS[2]) or '-1')
if stock < q then return -3 end
redis.call('DECRBY',KEYS[2],q)
redis.call('INCRBY',KEYS[3],q)
redis.call('HSET',KEYS[4],'fingerprint',ARGV[1],'quantity',q,'userId',ARGV[5],'state','HELD')
redis.call('ZADD',KEYS[5],ARGV[4],ARGV[3])
return 1
