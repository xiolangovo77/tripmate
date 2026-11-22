-- KEYS: stock, reservations, pending, rules, outbox（同一活动同槽）
-- ARGV: totalStock（份）, reservedCount（已预扣份数）
local stock = redis.call('GET', KEYS[1])
if stock then return tonumber(stock) end
if redis.call('HGET', KEYS[2], '_total') ~= ARGV[1]
    or redis.call('HGET', KEYS[3], '_total') ~= ARGV[1]
    or redis.call('HLEN', KEYS[2]) ~= tonumber(ARGV[2]) + 1 then return -1 end
if redis.call('TYPE', KEYS[4]).ok ~= 'hash' or redis.call('TYPE', KEYS[5]).ok ~= 'stream'
    or redis.call('XLEN', KEYS[5]) ~= redis.call('HLEN', KEYS[3]) - 1 then return -1 end
local remaining = tonumber(ARGV[1]) - tonumber(ARGV[2])
redis.call('SET', KEYS[1], remaining)
return remaining
