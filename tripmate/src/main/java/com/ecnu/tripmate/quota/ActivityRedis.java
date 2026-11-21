package com.ecnu.tripmate.quota;

import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.quota.model.ActivityClaim;
import com.ecnu.tripmate.quota.model.ActivityConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Component
@RequiredArgsConstructor
public class ActivityRedis {
    static final String CHANNEL = "tripmate:quota:activity:invalidate:v1";
    // 同一活动的预扣脚本须原子更新 stock、reservations 和 pending。
    // 两个 hash 都按 userId -> claimNo 保存。投递确认后只删除 pending 中的记录。
    private static final String TOTAL_FIELD = "_total";
    private static final DefaultRedisScript<Long> PREPARE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1], KEYS[2], KEYS[3]) == 0 then
                redis.call('SET', KEYS[1], ARGV[1])
                redis.call('HSET', KEYS[2], '_total', ARGV[1])
                redis.call('HSET', KEYS[3], '_total', ARGV[1])
                return 1
            end
            if redis.call('GET', KEYS[1]) == ARGV[1]
                and redis.call('HGET', KEYS[2], '_total') == ARGV[1]
                and redis.call('HGET', KEYS[3], '_total') == ARGV[1]
                and redis.call('HLEN', KEYS[2]) == 1 and redis.call('HLEN', KEYS[3]) == 1 then
                return 1
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> RESTORE = new DefaultRedisScript<>("""
            local stock = redis.call('GET', KEYS[1])
            if stock then return tonumber(stock) end
            if redis.call('HGET', KEYS[2], '_total') ~= ARGV[1]
                or redis.call('HGET', KEYS[3], '_total') ~= ARGV[1]
                or redis.call('HLEN', KEYS[2]) ~= tonumber(ARGV[2]) + 1 then return -1 end
            local remaining = tonumber(ARGV[1]) - tonumber(ARGV[2])
            redis.call('SET', KEYS[1], remaining)
            return remaining
            """, Long.class);
    // 版本字符串等宽，Lua 可直接比较，避免大整数经 double 转换丢失精度。
    private static final DefaultRedisScript<Long> WRITE = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[2])
            if current and current > ARGV[1] then return 0 end
            redis.call('SET', KEYS[2], ARGV[1])
            redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> INVALIDATE = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[2])
            if current and current > ARGV[1] then return 0 end
            redis.call('SET', KEYS[2], ARGV[1])
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);

    /**
     * 读写活动配置与库存，执行 Lua 脚本并发布缓存失效通知。
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 在活动配置对象与 Redis 缓存的 JSON 快照之间转换。
     */
    private final ObjectMapper objectMapper;

    public ActivityConfig read(long id) {
        String snapshot = stringRedisTemplate.opsForValue().get(prefix(id) + "config");
        if (snapshot == null) {
            return null;
        }
        try {
            return objectMapper.readValue(snapshot, ActivityConfig.class);
        } catch (JsonProcessingException malformed) {
            // 后续从数据库加载并按版本覆盖该快照。
            return null;
        }
    }

    public boolean store(ActivityConfig config) {
        try {
            long ttl = 300 + ThreadLocalRandom.current().nextInt(61);
            Long stored = stringRedisTemplate.execute(WRITE, keys(config.getId()), version(config.getConfigVersion()),
                    objectMapper.writeValueAsString(config), Long.toString(ttl));
            return Long.valueOf(1).equals(stored);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("活动配置序列化失败", e);
        }
    }

    public void invalidate(long id, long configVersion) {
        stringRedisTemplate.execute(INVALIDATE, keys(id), version(configVersion));
    }

    public void broadcast(long id, long configVersion) {
        stringRedisTemplate.convertAndSend(CHANNEL, id + ":" + configVersion);
    }

    public void prepareStock(ActivityConfig config) {
        Long result = stringRedisTemplate.execute(PREPARE, stockKeys(config.getId()), Long.toString(config.getTotalStock()));
        if (!Long.valueOf(1).equals(result)) {
            throw inconsistentStock();
        }
    }

    public long recover(ActivityConfig config, List<ActivityClaim> claims) {
        //1、读取预扣和待投递记录，检查总份数标记及领取单号的唯一性。
        List<String> keys = stockKeys(config.getId());
        String total = Long.toString(config.getTotalStock());
        Map<String, String> reserved = stringRedisTemplate.<String, String>opsForHash().entries(keys.get(1));
        Map<String, String> pending = stringRedisTemplate.<String, String>opsForHash().entries(keys.get(2));
        if (!total.equals(reserved.remove(TOTAL_FIELD)) || !total.equals(pending.remove(TOTAL_FIELD))
                || reserved.size() > config.getTotalStock()
                || new HashSet<>(reserved.values()).size() != reserved.size()) {
            throw inconsistentStock();
        }
        //2、按用户和领取单号逐笔核对数据库领取单及待投递记录。
        for (var claim : claims) {
            if (!claim.getClaimNo().equals(reserved.get(Long.toString(claim.getUserId())))) {
                throw inconsistentStock();
            }
        }
        if (!reserved.entrySet().containsAll(pending.entrySet())) {
            throw inconsistentStock();
        }
        //3、原子恢复总份数减去预扣份数的余额，已投递但尚未入库的单也计入预扣。
        Long remaining = stringRedisTemplate.execute(RESTORE, keys, total, Integer.toString(reserved.size()));
        if (remaining == null || remaining < 0) {
            throw inconsistentStock();
        }
        return remaining;
    }

    private static BusinessException inconsistentStock() {
        return new BusinessException(ErrorCode.OPERATION_ERROR, "预扣记录不完整或与领取单不符，活动库存需人工核对");
    }

    private static List<String> stockKeys(long id) {
        String prefix = prefix(id);
        return List.of(prefix + "stock", prefix + "reservations", prefix + "pending");
    }

    public Long remaining(long id) {
        String value = stringRedisTemplate.opsForValue().get(prefix(id) + "stock");
        return value == null ? null : Long.valueOf(value);
    }

    private static String prefix(long id) {
        return "tripmate:quota:activity:{" + id + "}:";
    }

    private static List<String> keys(long id) {
        String prefix = prefix(id);
        return List.of(prefix + "config", prefix + "config-version");
    }

    private static String version(long version) {
        return String.format(Locale.ROOT, "%019d", version);
    }
}
