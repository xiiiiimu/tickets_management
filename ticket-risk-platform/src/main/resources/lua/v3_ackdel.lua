-- Acknowledge and delete a message whose request is already settled. KEYS: 1 stream. ARGV: 1 group, 2 stream id
redis.call('XACK',KEYS[1],ARGV[1],ARGV[2]) redis.call('XDEL',KEYS[1],ARGV[2]) return 1
