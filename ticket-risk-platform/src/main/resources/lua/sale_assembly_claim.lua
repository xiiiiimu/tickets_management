-- Claim a monotonic assembly generation. KEYS share a session or SKU hash tag.
-- KEYS: fence, active pointer (STRING) or SKU config (HASH). ARGV: generation, kind.
local incoming=tonumber(ARGV[1])
local current=tonumber(redis.call('GET',KEYS[1]) or '-1')
local published
if ARGV[2]=='HASH' then published=tonumber(redis.call('HGET',KEYS[2],'assemblyVersion') or '-1')
else published=tonumber(redis.call('GET',KEYS[2]) or '-1') end
if incoming<current or incoming<published then return 0 end
redis.call('SET',KEYS[1],ARGV[1]);return 1
