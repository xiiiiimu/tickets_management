-- Per-attempt zero-stock linearization; same slot. Never marks a held reservation.
if redis.call('GET',KEYS[21])~=ARGV[3] or redis.call('HGET',KEYS[1],'assemblyVersion')~=ARGV[3] then return -7 end
if ARGV[4] and ARGV[4]~='' then local t=redis.call('TIME');if tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)>=tonumber(ARGV[4]) then return 3 end end
local state=redis.call('HGET',KEYS[19],'state');if state=='HELD' then return 2 end
local total=0
for i=2,17 do local n=tonumber(redis.call('GET',KEYS[i]));if not n or n<0 then return -8 end;total=total+n end
if total>0 then return 0 end
local old=redis.call('HGET',KEYS[19],'fingerprint');if old and old~=ARGV[1] then return -4 end
redis.call('HSET',KEYS[19],'fingerprint',ARGV[1],'protocolVersion','3','assemblyVersion',ARGV[3],'state','ABORTED','abortReason','SOLD_OUT')
return 1
