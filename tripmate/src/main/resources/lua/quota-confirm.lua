-- KEYS: outbox, pending（同一活动同槽）
-- ARGV: 按条重复传入 streamEntryId, userId, claimNo（每条 3 个参数）
local cleared = 0
for i = 1, #ARGV, 3 do
    if redis.call('HGET', KEYS[2], ARGV[i+1]) == ARGV[i+2]
        and redis.call('XACK', KEYS[1], 'quota-dispatch', ARGV[i]) == 1 then
        redis.call('XDEL', KEYS[1], ARGV[i])
        redis.call('HDEL', KEYS[2], ARGV[i+1])
        cleared = cleared + 1
    end
end
return cleared
