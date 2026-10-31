-- Same 21 keys/slot and first 8 args as bucket protocol 2. Only protocol 3 writes vectors.
local old=redis.call('HGET',KEYS[19],'fingerprint')
if old then
 if old~=ARGV[1] then return -4 end
 local state=redis.call('HGET',KEYS[19],'state')
 if state=='ABORTED' or state=='RELEASED' then
  if redis.call('HGET',KEYS[19],'abortReason')=='SOLD_OUT' then return -11 end
  return -5
 end
 if state~='HELD' or redis.call('HGET',KEYS[19],'protocolVersion')~='3' or redis.call('HGET',KEYS[19],'assemblyVersion')~=ARGV[7] then return -7 end
 return 1
end
local q=tonumber(ARGV[2]);if not q or q<1 or q~=math.floor(q) then return -6 end
if redis.call('GET',KEYS[21])~=ARGV[7] or redis.call('HGET',KEYS[1],'assemblyVersion')~=ARGV[7] or redis.call('HGET',KEYS[1],'reservationProtocol')~='3' then return -7 end
local total=0;local stocks={}
for i=1,16 do local n=tonumber(redis.call('GET',KEYS[1+i]));if not n or n<0 or n~=math.floor(n) then return -8 end;stocks[i]=n;total=total+n end
local t=redis.call('TIME');local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
if now>=tonumber(ARGV[8]) then return -10 end
if redis.call('HGET',KEYS[1],'status')~='ON_SALE' or now<tonumber(redis.call('HGET',KEYS[1],'start') or '0') or now>=tonumber(redis.call('HGET',KEYS[1],'end') or '0') then return -1 end
if q>tonumber(redis.call('HGET',KEYS[1],'singleLimit') or '0') then return -13 end
local used=tonumber(redis.call('GET',KEYS[18]) or '0');if not used or used<0 or used~=math.floor(used) then return -8 end
local pending=redis.call('TYPE',KEYS[20]).ok;if pending~='none' and pending~='zset' then return -8 end
if used+q>tonumber(redis.call('HGET',KEYS[1],'limit') or '0') then return -2 end
if total==0 then return -3 end
if total<q then return -12 end
-- Plan all components before changing any stock. Execution is atomic in this SKU hash slot.
local v={};for i=1,16 do v[i]=0 end
local remaining=q
for probe=0,15 do local b=(tonumber(ARGV[6])+probe)%16;local n=math.min(stocks[b+1],remaining);v[b+1]=n;remaining=remaining-n;if remaining==0 then break end end
for i=1,16 do if v[i]>0 then redis.call('DECRBY',KEYS[1+i],v[i]) end end
redis.call('INCRBY',KEYS[18],q)
redis.call('HSET',KEYS[19],'fingerprint',ARGV[1],'quantity',q,'userId',ARGV[5],'state','HELD','protocolVersion','3','assemblyVersion',ARGV[7],'heldAt',now,'expiresAt',now+600000,'bucketId','-1','stockKey','VECTOR','allocations',cjson.encode(v))
redis.call('ZADD',KEYS[20],now+600000,ARGV[3]);return 1
