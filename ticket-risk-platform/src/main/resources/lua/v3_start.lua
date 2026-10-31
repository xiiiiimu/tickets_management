-- V3 start decision: the ONLY place where "start" and "queue timeout" are decided for a request, atomically.
-- KEYS: 1 req, 2 waiting, 3 active, 4 user in-flight, 5 cfg
-- ARGV: 1 requestId, 2 token, 3 leaseMs, 4 fallback activeLimit, 5 message deadline, 6 fingerprint, 7 userId, 8 attemptId,
--       9 stream id, 10 retentionMs
-- Returns START | TAKEOVER | TIMEOUT | FULL | HELD_BY_OTHER | SETTLED,<state>
local t=redis.call('TIME') local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
local st=redis.call('HGET',KEYS[1],'state')
if not st then
  -- req lost (retention expiry / partial data loss) but the message survived: rebuild from the message itself.
  redis.call('HSET',KEYS[1],'fp',ARGV[6],'user',ARGV[7],'attempt',ARGV[8],'deadline',ARGV[5],'sid',ARGV[9],'at',now,'rebuilt','1')
  redis.call('PEXPIRE',KEYS[1],ARGV[10])
  if now>=tonumber(ARGV[5]) then redis.call('HSET',KEYS[1],'state','QUEUE_TIMEOUT') return {'TIMEOUT'} end
  redis.call('HSET',KEYS[1],'state','WAITING') st='WAITING'
end
if st=='WAITING' then
  if now>=tonumber(redis.call('HGET',KEYS[1],'deadline')) then
    redis.call('HSET',KEYS[1],'state','QUEUE_TIMEOUT')
    redis.call('ZREM',KEYS[2],ARGV[1]) redis.call('ZREM',KEYS[4],ARGV[1])
    return {'TIMEOUT'}
  end
  local limit=tonumber(redis.call('HGET',KEYS[5],'activeLimit') or ARGV[4])
  if redis.call('ZCARD',KEYS[3])>=limit then return {'FULL'} end
  local lease=now+tonumber(ARGV[3])
  redis.call('HSET',KEYS[1],'state','PROCESSING','token',ARGV[2],'lease',lease)
  redis.call('ZREM',KEYS[2],ARGV[1]) redis.call('ZADD',KEYS[3],lease,ARGV[1]) redis.call('ZADD',KEYS[4],lease,ARGV[1])
  return {'START'}
end
if st=='PROCESSING' then
  if tonumber(redis.call('HGET',KEYS[1],'lease') or '0')>now then return {'HELD_BY_OTHER'} end
  local lease=now+tonumber(ARGV[3])
  redis.call('HSET',KEYS[1],'token',ARGV[2],'lease',lease)
  redis.call('ZADD',KEYS[3],lease,ARGV[1]) redis.call('ZADD',KEYS[4],lease,ARGV[1])
  return {'TAKEOVER'}
end
if st=='QUEUE_TIMEOUT' then return {'TIMEOUT'} end -- not yet fenced in MySQL: the consumer must fence before ACK
return {'SETTLED',st}
