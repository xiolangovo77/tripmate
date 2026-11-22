-- KEYS: stock, reservations, pending, rules, outbox（同一活动同槽）
-- ARGV: totalStock（份）, start（Unix 秒）, end（Unix 秒）, grantAmount（AI 使用次数）
local existing = redis.call('EXISTS', unpack(KEYS))
if existing == 0 then
    redis.call('XGROUP', 'CREATE', KEYS[5], 'quota-dispatch', '0', 'MKSTREAM')
    redis.call('SET', KEYS[1], ARGV[1])
    redis.call('HSET', KEYS[2], '_total', ARGV[1])
    redis.call('HSET', KEYS[3], '_total', ARGV[1])
    redis.call('HSET', KEYS[4], 'start', ARGV[2], 'end', ARGV[3], 'grantAmount', ARGV[4])
    return 1
end
-- 发布事务回滚后可复用完整的初始化结果，部分缺失或库存已变动时拒绝覆盖。
if existing ~= #KEYS or redis.call('TYPE', KEYS[5]).ok ~= 'stream' then return 0 end
local rules = redis.call('HMGET', KEYS[4], 'start', 'end', 'grantAmount')
if rules[1] == ARGV[2] and rules[2] == ARGV[3] and rules[3] == ARGV[4]
    and redis.call('GET', KEYS[1]) == ARGV[1]
    and redis.call('HGET', KEYS[2], '_total') == ARGV[1] and redis.call('HLEN', KEYS[2]) == 1
    and redis.call('HGET', KEYS[3], '_total') == ARGV[1] and redis.call('HLEN', KEYS[3]) == 1
    and redis.call('XLEN', KEYS[5]) == 0 then return 1 end
return 0
