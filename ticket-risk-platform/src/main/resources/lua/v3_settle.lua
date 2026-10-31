-- Records an outcome ALREADY decided by MySQL (terminal job row, timeout fence, alias, delegation) and acknowledges.
-- KEYS: 1 req, 2 waiting, 3 active, 4 user in-flight, 5 owner, 6 stream, 7 cfg
-- ARGV: 1 requestId, 2 state, 3 code, 4 group, 5 stream id ('' = no message), 6 token ('' = no token check), 7 aliasOf
-- With a token, a stale owner (taken over) cannot settle: returns 0 and acknowledges nothing.
if ARGV[6]~='' and redis.call('HGET',KEYS[1],'token')~=ARGV[6] then return 0 end
if redis.call('EXISTS',KEYS[1])==1 then
  redis.call('HSET',KEYS[1],'state',ARGV[2],'code',ARGV[3])
  if ARGV[7]~='' then redis.call('HSET',KEYS[1],'aliasOf',ARGV[7]) end
  redis.call('HDEL',KEYS[1],'token','lease')
end
redis.call('ZREM',KEYS[2],ARGV[1]) redis.call('ZREM',KEYS[3],ARGV[1]) redis.call('ZREM',KEYS[4],ARGV[1])
if redis.call('GET',KEYS[5])==ARGV[1] then redis.call('DEL',KEYS[5]) end
if ARGV[5]~='' then
  redis.call('XACK',KEYS[6],ARGV[4],ARGV[5]) redis.call('XDEL',KEYS[6],ARGV[5])
end
if ARGV[2]=='DONE' then redis.call('HINCRBY',KEYS[7],'done',1) end
return 1
