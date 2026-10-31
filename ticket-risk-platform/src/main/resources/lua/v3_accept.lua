-- V3 acceptance. Single hash slot {tkv3}. Never touches inventory keys.
-- KEYS: 1 cfg, 2 req, 3 owner(user:session), 4 waiting zset, 5 stream, 6 user in-flight zset
-- ARGV: 1 requestId, 2 fingerprint, 3 attemptId, 4 userId, 5 sessionId, 6 waitMs, 7 retentionMs, 8 userLimit,
--       9 reservationId, 10 orderId, 11 skuId, 12 quantity, 13 deviceId, 14 ip, 15 saleVersion, 16 traceId,
--       17 fallback cap, 18 cap freshness ms, 19 req key prefix
-- BUSY / USER_LIMIT / CLOSED / MISMATCH write nothing.
if redis.call('HGET',KEYS[1],'mode')~='OPEN' then return {'CLOSED'} end
local t=redis.call('TIME') local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
local st=redis.call('HGET',KEYS[2],'state')
if st then
  if redis.call('HGET',KEYS[2],'fp')~=ARGV[2] then return {'MISMATCH'} end
  return {'KNOWN',redis.call('HGET',KEYS[2],'attempt') or '',st,redis.call('HGET',KEYS[2],'aliasOf') or '',redis.call('HGET',KEYS[2],'deadline') or '0',redis.call('HGET',KEYS[2],'at') or '0'}
end
local owner=redis.call('GET',KEYS[3])
if owner then
  -- Same user+session already has an attempt in flight: alias it (protocol-3 active_owner semantics).
  local ownerState=redis.call('HGET',ARGV[19]..owner,'state')
  if ownerState=='WAITING' or ownerState=='PROCESSING' then
    redis.call('HSET',KEYS[2],'fp',ARGV[2],'user',ARGV[4],'state','ALIAS','aliasOf',owner,'at',now)
    redis.call('PEXPIRE',KEYS[2],ARGV[7])
    return {'ALIAS',owner}
  end
  redis.call('DEL',KEYS[3]) -- stale owner of a settled/expired attempt
end
redis.call('ZREMRANGEBYSCORE',KEYS[4],'-inf',now)
local cap=tonumber(ARGV[17])
local capAt=tonumber(redis.call('HGET',KEYS[1],'capAt') or '0')
if now-capAt<=tonumber(ARGV[18]) then cap=tonumber(redis.call('HGET',KEYS[1],'cap') or ARGV[17]) end
if redis.call('ZCARD',KEYS[4])>=cap then return {'BUSY'} end
redis.call('ZREMRANGEBYSCORE',KEYS[6],'-inf',now)
if redis.call('ZCARD',KEYS[6])>=tonumber(ARGV[8]) then return {'USER_LIMIT'} end
local deadline=now+tonumber(ARGV[6])
local id=redis.call('XADD',KEYS[5],'*','v','1','requestId',ARGV[1],'attemptId',ARGV[3],'reservationId',ARGV[9],'orderId',ARGV[10],
  'userId',ARGV[4],'sessionId',ARGV[5],'skuId',ARGV[11],'quantity',ARGV[12],'deviceId',ARGV[13],'ip',ARGV[14],
  'fingerprint',ARGV[2],'saleVersion',ARGV[15],'deadline',deadline,'acceptedAt',now,'traceId',ARGV[16])
redis.call('HSET',KEYS[2],'fp',ARGV[2],'user',ARGV[4],'attempt',ARGV[3],'state','WAITING','deadline',deadline,'sid',id,'at',now)
redis.call('PEXPIRE',KEYS[2],ARGV[7])
redis.call('SET',KEYS[3],ARGV[1],'PX',ARGV[7])
redis.call('ZADD',KEYS[4],deadline,ARGV[1])
redis.call('ZADD',KEYS[6],deadline,ARGV[1]) redis.call('PEXPIRE',KEYS[6],ARGV[7])
return {'ACCEPTED',ARGV[3],id,tostring(deadline),tostring(now)}
