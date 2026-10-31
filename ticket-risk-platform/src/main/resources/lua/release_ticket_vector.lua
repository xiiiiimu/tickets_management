-- KEYS same 21 keys. ARGV fingerprint (empty for release), reservationId, version (empty for release), mode.
local old=redis.call('HGET',KEYS[19],'fingerprint')
if ARGV[1]~='' and old and old~=ARGV[1] then return -4 end
local state=redis.call('HGET',KEYS[19],'state')
if state=='RELEASED' or state=='ABORTED' then return 0 end
if state=='HELD' then
 local version=redis.call('HGET',KEYS[19],'assemblyVersion')
 if redis.call('HGET',KEYS[19],'protocolVersion')~='3' or (ARGV[3]~='' and version~=ARGV[3]) or redis.call('GET',KEYS[21])~=version or redis.call('HGET',KEYS[1],'assemblyVersion')~=version then return -7 end
 local raw=redis.call('HGET',KEYS[19],'allocations');if not raw then return -8 end
 local ok,v=pcall(cjson.decode,raw);if not ok or #v~=16 then return -8 end
 local total=0
 for i=1,16 do local stock=tonumber(redis.call('GET',KEYS[1+i]));if type(v[i])~='number' or v[i]<0 or v[i]~=math.floor(v[i]) or not stock or stock<0 or stock~=math.floor(stock) then return -8 end;total=total+v[i] end
 local used=tonumber(redis.call('GET',KEYS[18]));if not used or used~=math.floor(used) or used<total or total~=tonumber(redis.call('HGET',KEYS[19],'quantity')) then return -8 end
 local pending=redis.call('TYPE',KEYS[20]).ok;if pending~='none' and pending~='zset' then return -8 end
 for i=1,16 do if v[i]>0 then redis.call('INCRBY',KEYS[1+i],v[i]) end end
 redis.call('DECRBY',KEYS[18],total)
elseif ARGV[4]~='ABORT' then return 0 end
if ARGV[4]=='ABORT' then redis.call('HSET',KEYS[19],'fingerprint',ARGV[1],'protocolVersion','3','assemblyVersion',ARGV[3],'state','ABORTED')
else redis.call('HSET',KEYS[19],'state','RELEASED') end
redis.call('ZREM',KEYS[20],ARGV[2]);return 1
