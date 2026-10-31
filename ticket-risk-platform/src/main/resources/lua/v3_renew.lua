-- KEYS: 1 req, 2 active, 3 user in-flight. ARGV: 1 requestId, 2 token, 3 leaseMs
if redis.call('HGET',KEYS[1],'token')~=ARGV[2] or redis.call('HGET',KEYS[1],'state')~='PROCESSING' then return 0 end
local t=redis.call('TIME') local lease=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)+tonumber(ARGV[3])
redis.call('HSET',KEYS[1],'lease',lease) redis.call('ZADD',KEYS[2],lease,ARGV[1]) redis.call('ZADD',KEYS[3],lease,ARGV[1])
return 1
