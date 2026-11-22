-- KEYS: outbox, pending（同一活动同槽）
-- ARGV: streamEntryId, userId, claimNo
-- 专属消费组收到 RabbitMQ 确认后，原子清理 PEL、消息和待投递索引。
if redis.call('HGET', KEYS[2], ARGV[2]) ~= ARGV[3] then return 0 end
if redis.call('XACK', KEYS[1], 'quota-dispatch', ARGV[1]) == 0 then return 0 end
redis.call('XDEL', KEYS[1], ARGV[1])
redis.call('HDEL', KEYS[2], ARGV[2])
return 1
