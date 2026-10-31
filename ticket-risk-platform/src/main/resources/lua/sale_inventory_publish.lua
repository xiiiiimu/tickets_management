-- Off-path assembly/recovery only. Existing reserve/release scripts are unchanged.
-- KEYS: SKU fence, config, pending, then every stock/user/reservation/cleanup key.
-- ARGV: generation, JSON snapshot. All keys use the existing SKU hash tag.
if redis.call('GET',KEYS[1])~=ARGV[1] then return -1 end
if tonumber(redis.call('HGET',KEYS[2],'assemblyVersion') or '-1')>tonumber(ARGV[1]) then return -1 end
local data=cjson.decode(ARGV[2])
local declared={};for _,key in ipairs(KEYS) do declared[key]=true end
for _,group in ipairs({'stocks','users','markers'}) do
 for key,_ in pairs(data[group]) do if not declared[key] then return redis.error_reply('Undeclared assembly key') end end
end
if data.replace then for i=2,#KEYS do redis.call('DEL',KEYS[i]) end end
for key,value in pairs(data.stocks) do
 if data.replace then redis.call('SET',key,value) else redis.call('SETNX',key,value) end
end
for field,value in pairs(data.config) do redis.call('HSET',KEYS[2],field,value) end
redis.call('HSET',KEYS[2],'assemblyVersion',ARGV[1])
if data.replace then
 for key,value in pairs(data.users) do redis.call('SET',key,value) end
 for key,marker in pairs(data.markers) do for field,value in pairs(marker) do redis.call('HSET',key,field,value) end end
 for _,entry in ipairs(data.pending) do redis.call('ZADD',KEYS[3],entry.score,entry.member) end
end
return 1
