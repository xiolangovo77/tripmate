-- KEYS: config, config-version（同一活动同槽）
-- ARGV: configVersion（19 位等宽字符串）
local current = redis.call('GET', KEYS[2])
if current and current > ARGV[1] then return 0 end
redis.call('SET', KEYS[2], ARGV[1])
redis.call('DEL', KEYS[1])
return 1
