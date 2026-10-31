-- SALE_MANIFEST_PUBLISH: stale workers cannot downgrade the active session pointer.
-- KEYS: fence, immutable manifest, active pointer. ARGV: generation, payload.
if redis.call('GET',KEYS[1])~=ARGV[1] then return -1 end
if tonumber(redis.call('GET',KEYS[3]) or '-1')>tonumber(ARGV[1]) then return -1 end
local old=redis.call('GET',KEYS[2])
if old and old~=ARGV[2] then return -2 end
if not old then redis.call('SET',KEYS[2],ARGV[2]) end
redis.call('SET',KEYS[3],ARGV[1]);return 1
