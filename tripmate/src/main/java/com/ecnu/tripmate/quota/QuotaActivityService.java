package com.ecnu.tripmate.quota;

import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.mapper.QuotaActivityMapper;
import com.ecnu.tripmate.quota.model.ActivityConfig;
import com.ecnu.tripmate.quota.model.ActivityConfigChangedEvent;
import com.ecnu.tripmate.quota.model.ActivityDetail;
import com.ecnu.tripmate.quota.model.CreateActivityRequest;
import com.ecnu.tripmate.quota.model.UpdateActivityDisplayRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class QuotaActivityService {
    /**
     * 读写 MySQL 活动配置，查询库存核对所需的领取单。
     */
    private final QuotaActivityMapper quotaActivityMapper;

    /**
     * 通过 Caffeine 和 Redis 两级缓存读取活动配置。
     */
    private final ActivityCache activityCache;

    /**
     * 初始化和读取 Redis 库存，并在对账后恢复缺失的库存。
     */
    private final ActivityRedis activityRedis;

    /**
     * 发布活动配置变更事件，供监听器在事务提交后同步缓存。
     */
    private final ApplicationEventPublisher applicationEventPublisher;

    public ActivityDetail detail(long id) {
        //1、读取活动配置，仅向用户展示已发布活动。
        ActivityConfig config = activityCache.get(id);
        if (!config.published()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        //2、读取实时库存，将库存状态和配置快照组装成详情。
        Long stock = activityRedis.remaining(id);
        return new ActivityDetail(config, stock, stock != null);
    }

    @Transactional
    public long create(CreateActivityRequest draft) {
        //1、校验展示文案、领取时间窗口和发放数量。
        validateDisplay(draft.getName(), draft.getRuleText());
        if (draft.getStartTime() == null || draft.getEndTime() == null
                || !draft.getStartTime().withNano(0).isBefore(draft.getEndTime().withNano(0))
                || !validQuantity(draft.getTotalStock()) || !validQuantity(draft.getGrantAmount())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请检查活动时间、总份数和每份额度");
        }
        //2、保存草稿，返回 MyBatis 回填的活动ID。
        quotaActivityMapper.create(draft);
        return draft.getId();
    }

    @Transactional
    public void publish(long id) {
        //1、锁定活动行，已发布的活动直接返回。
        ActivityConfig config = quotaActivityMapper.find(id, true);
        if (config.published()) {
            return;
        }
        //2、在草稿行锁内准备库存，事务回滚后重试可沿用首次初始化的值。
        activityRedis.prepareStock(config);
        //3、更新发布状态和版本，提交成功后由事件监听器预热配置。
        quotaActivityMapper.publish(id);
        applicationEventPublisher.publishEvent(new ActivityConfigChangedEvent(quotaActivityMapper.find(id, false), true));
    }

    @Transactional
    public long recoverStock(long id) {
        //1、锁定活动行并确认活动已发布。
        ActivityConfig config = quotaActivityMapper.find(id, true);
        if (!config.published()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "仅可恢复已发布活动的库存");
        }
        //2、库存键仍存在时返回当前值，保留已扣减的份数。
        Long existing = activityRedis.remaining(id);
        if (existing != null) {
            return existing;
        }
        //3、将数据库领取单与 Redis 预扣记录核对后恢复库存。
        return activityRedis.recover(config, quotaActivityMapper.claims(id));
    }

    @Transactional
    public void updateDisplay(long id, UpdateActivityDisplayRequest display) {
        //1、校验展示信息并锁定活动行。
        validateDisplay(display.getName(), display.getRuleText());
        quotaActivityMapper.find(id, true);
        //2、更新展示文案并递增版本，提交后通知各实例清理缓存。
        quotaActivityMapper.updateDisplay(id, display.getName(), display.getRuleText());
        applicationEventPublisher.publishEvent(new ActivityConfigChangedEvent(quotaActivityMapper.find(id, false), false));
    }

    private static boolean validQuantity(Long value) {
        return value != null && value > 0 && value <= 4294967295L;
    }

    private static void validateDisplay(String name, String ruleText) {
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 120
                || ruleText == null || ruleText.isBlank()
                || ruleText.getBytes(StandardCharsets.UTF_8).length > 65535) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "活动名称或规则无效");
        }
    }
}
