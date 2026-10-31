-- Same SKU hash slot as the existing 16-bucket reserve protocol.
-- KEYS: config,16 stock buckets,user,reservation,pending,assembly-fence.
-- ARGV: fingerprint,reservationId,expected assembly version.
local old=redis.call('HGET',KEYS[19],'fingerprint')
if old and old~=ARGV[1] then return -4 end
local state=redis.call('HGET',KEYS[19],'state')
if state=='RELEASED' or state=='ABORTED' then return 0 end
if state=='HELD' then
 local version=redis.call('HGET',KEYS[19],'assemblyVersion')
 if redis.call('HGET',KEYS[19],'protocolVersion')~='2' or version~=ARGV[3]
  or redis.call('GET',KEYS[21])~=version or redis.call('HGET',KEYS[1],'assemblyVersion')~=version then return -7 end
 local bucket=tonumber(redis.call('HGET',KEYS[19],'bucketId'))
 if not bucket or bucket<0 or bucket>15 then return -8 end
 local stock=KEYS[2+bucket]
 local q=tonumber(redis.call('HGET',KEYS[19],'quantity'))
 if redis.call('HGET',KEYS[19],'stockKey')~=stock or redis.call('EXISTS',stock)==0
  or redis.call('EXISTS',KEYS[18])==0 or not q or tonumber(redis.call('GET',KEYS[18]))<q then return -8 end
 redis.call('INCRBY',stock,q);redis.call('DECRBY',KEYS[18],q)
end
-- Also create a tombstone when no hold exists: delayed reserve commands must not resurrect an aborted intent.
redis.call('HSET',KEYS[19],'fingerprint',ARGV[1],'state','ABORTED','protocolVersion','2','assemblyVersion',ARGV[3])
redis.call('ZREM',KEYS[20],ARGV[2])
return 1
