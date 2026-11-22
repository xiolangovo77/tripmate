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
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.time.ZoneId;

@Component
@RequiredArgsConstructor
public class ActivityRedis {
    /**
     * 广播活动配置失效通知的 Redis 频道，各实例订阅后清理本地缓存。
     */
    static final String CHANNEL = "tripmate:quota:activity:invalidate:v1";
    /**
     * 预扣标记与待投递索引中保存活动总份数的保留字段，用于库存核对。
     */
    private static final String TOTAL_FIELD = "_total";
    /**
     * 重复领取返回原单号，首次领取通过校验后原子扣减一份库存、登记单号并写入投递待办。
     */
    private static final DefaultRedisScript<String> CLAIM = script("claim", String.class);
    /**
     * RabbitMQ 确认投递后，确认并删除 Stream 消息，清除对应待投递索引，保留用户领取标记。
     */
    static final DefaultRedisScript<Long> CONFIRM = script("confirm", Long.class);

    private static <T> DefaultRedisScript<T> script(String name, Class<T> type) {
        var script = new DefaultRedisScript<T>();
        script.setLocation(new ClassPathResource("lua/quota-" + name + ".lua"));
        script.setResultType(type);
        return script;
    }
    /**
     * 发布时初始化库存、领取索引、规则和投递消费组，重试时核对已有数据。
     */
    private static final DefaultRedisScript<Long> PREPARE = script("prepare", Long.class);

    /**
     * 库存键缺失时核对预扣记录，按总份数减去已预扣份数恢复库存。
     */
    private static final DefaultRedisScript<Long> RESTORE = script("restore", Long.class);
    /**
     * 按等宽版本字符串比较新旧，仅回填相同或更高版本的配置，避免大整数转换丢失精度。
     */
    private static final DefaultRedisScript<Long> WRITE = script("write-config", Long.class);

    /**
     * 按通知版本删除共享配置缓存并记录失效版本，防止旧查询回填。
     */
    private static final DefaultRedisScript<Long> INVALIDATE = script("invalidate-config", Long.class);

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
        //1、按活动时区转换领取窗口，一次初始化本期全部 Redis 数据。
        var zone = ZoneId.of("Asia/Shanghai");
        Long result = stringRedisTemplate.execute(PREPARE, claimKeys(config.getId()),
                Long.toString(config.getTotalStock()), Long.toString(config.getStartTime().atZone(zone).toEpochSecond()),
                Long.toString(config.getEndTime().atZone(zone).toEpochSecond()), Long.toString(config.getGrantAmount()));
        if (!Long.valueOf(1).equals(result)) throw inconsistentStock();
    }

    public String reserve(ActivityConfig config, long userId, String candidate, long expiry) {
        //1、在同一 Redis 脚本中完成去重、资格和库存校验、预扣及写入 Stream。
        var keys = claimKeys(config.getId());
        String[] args = {Long.toString(userId), candidate, Long.toString(config.getId()), Long.toString(expiry)};
        String result = stringRedisTemplate.execute(CLAIM, keys, (Object[]) args);
        //2、将预扣结果转换为领取单号或业务错误。
        if (result == null) throw inconsistentStock();
        return switch (result) {
            case "MEMBER_EXPIRED" -> throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "会员资格无效或已到期");
            case "NOT_STARTED" -> throw new BusinessException(ErrorCode.OPERATION_ERROR, "活动尚未开始");
            case "ENDED" -> throw new BusinessException(ErrorCode.OPERATION_ERROR, "活动已结束");
            case "SOLD_OUT" -> throw new BusinessException(ErrorCode.OPERATION_ERROR, "活动已售罄");
            case "UNAVAILABLE" -> throw inconsistentStock();
            default -> result;
        };
    }

    public String reservation(long activityId, long userId) {
        return stringRedisTemplate.<String, String>opsForHash().get(prefix(activityId) + "reservations", Long.toString(userId));
    }

    private static List<String> claimKeys(long id) {
        String prefix = prefix(id);
        return List.of(prefix + "stock", prefix + "reservations", prefix + "pending", prefix + "rules", prefix + "outbox");
    }

    public long recover(ActivityConfig config, List<ActivityClaim> claims) {
        //1、读取预扣和待投递记录，检查总份数标记及领取单号的唯一性。
        List<String> keys = claimKeys(config.getId());
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

    public Long remaining(long id) {
        String value = stringRedisTemplate.opsForValue().get(prefix(id) + "stock");
        return value == null ? null : Long.valueOf(value);
    }

    static String prefix(long id) {
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
