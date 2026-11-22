-- KEYS: stock, reservations, pending, rules, outbox（同一活动同槽）
-- ARGV: userId, claimNo, activityId, memberExpireAt（Unix 秒）
redis.replicate_commands()
local old = redis.call('HGET', KEYS[2], ARGV[1])
if old then return old end
local now = tonumber(redis.call('TIME')[1])
if tonumber(ARGV[4]) <= now then return 'MEMBER_EXPIRED' end
if redis.call('TYPE', KEYS[5]).ok ~= 'stream' then return 'UNAVAILABLE' end
local rule = redis.call('HMGET', KEYS[4], 'start', 'end', 'grantAmount')
if not tonumber(rule[1]) or not tonumber(rule[2]) or not tonumber(rule[3]) then return 'UNAVAILABLE' end
if now < tonumber(rule[1]) then return 'NOT_STARTED' end
if now >= tonumber(rule[2]) then return 'ENDED' end
local total = redis.call('HGET', KEYS[2], '_total')
local stock = redis.call('GET', KEYS[1])
if not total or redis.call('HGET', KEYS[3], '_total') ~= total
    or not stock or not string.match(stock, '^%d+$') or tostring(tonumber(stock)) ~= stock
    or tonumber(stock) > tonumber(total) then return 'UNAVAILABLE' end
if stock == '0' then return 'SOLD_OUT' end
-- 先 XADD；Stream 类型或 ID 异常时库存和领取标记均未改变。
redis.call('XADD', KEYS[5], '*', 'claimNo', ARGV[2], 'activityId', ARGV[3], 'userId', ARGV[1], 'grantAmount', rule[3])
redis.call('DECR', KEYS[1])
redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
redis.call('HSET', KEYS[3], ARGV[1], ARGV[2])
return ARGV[2]
