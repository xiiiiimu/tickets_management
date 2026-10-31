-- KEYS: config, 16 stock buckets, SKU-wide user quantity, reservation, pending.
-- ARGV: fingerprint, quantity, reservationId, expiryMillis, userId, preferred bucket (0..15).
local v2=ARGV[7]~=nil and ARGV[7]~=''
local old=redis.call('HGET',KEYS[19],'fingerprint')
if old then
 if old~=ARGV[1] then return -4 end
 local state=redis.call('HGET',KEYS[19],'state')
 if state=='RELEASED' or state=='ABORTED' then return -5 end
 if v2 and (redis.call('HGET',KEYS[19],'protocolVersion')~='2' or redis.call('HGET',KEYS[19],'assemblyVersion')~=ARGV[7]) then return -7 end
 return 100+tonumber(redis.call('HGET',KEYS[19],'bucketId'))
end
local q=tonumber(ARGV[2])
if not q or q<1 or q~=math.floor(q) then return -6 end
local t=redis.call('TIME');local ms=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
local expiry=ARGV[4]
if v2 then
 if redis.call('GET',KEYS[21])~=ARGV[7] or redis.call('HGET',KEYS[1],'assemblyVersion')~=ARGV[7] then return -7 end
 for i=2,17 do if redis.call('EXISTS',KEYS[i])==0 then return -8 end end
 if ms>=tonumber(ARGV[8]) then return -10 end
 expiry=ms+600000
end
if redis.call('HGET',KEYS[1],'status')~='ON_SALE' or ms<tonumber(redis.call('HGET',KEYS[1],'start') or '0')
 or ms>=tonumber(redis.call('HGET',KEYS[1],'end') or '0') then return -1 end
if tonumber(redis.call('GET',KEYS[18]) or '0')+q>tonumber(redis.call('HGET',KEYS[1],'limit') or '0') then return -2 end
for probe=0,15 do
 local bucket=(tonumber(ARGV[6])+probe)%16
 local stockKey=KEYS[2+bucket]
 if tonumber(redis.call('GET',stockKey) or '-1')>=q then
  redis.call('DECRBY',stockKey,q);redis.call('INCRBY',KEYS[18],q)
  redis.call('HSET',KEYS[19],'fingerprint',ARGV[1],'quantity',q,'userId',ARGV[5],'state','HELD','bucketId',bucket,'stockKey',stockKey)
  if v2 then redis.call('HSET',KEYS[19],'protocolVersion','2','assemblyVersion',ARGV[7],'heldAt',ms,'expiresAt',expiry) end
  redis.call('ZADD',KEYS[20],expiry,ARGV[3])
  return 100+bucket
 end
end
return -3
