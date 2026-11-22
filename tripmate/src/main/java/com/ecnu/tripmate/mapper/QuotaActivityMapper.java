package com.ecnu.tripmate.mapper;

import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.quota.model.ActivityClaim;
import com.ecnu.tripmate.quota.model.ActivityConfig;
import com.ecnu.tripmate.quota.model.CreateActivityRequest;
import com.ecnu.tripmate.quota.model.QuotaClaimResult;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface QuotaActivityMapper {
    ActivityConfig select(@Param("id") long id);

    ActivityConfig lock(@Param("id") long id);

    default ActivityConfig find(long id, boolean forUpdate) {
        ActivityConfig config = forUpdate ? lock(id) : select(id);
        if (config == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        return config;
    }

    void create(CreateActivityRequest draft);

    void publish(@Param("id") long id);

    void updateDisplay(@Param("id") long id, @Param("name") String name, @Param("ruleText") String ruleText);

    List<ActivityClaim> claims(@Param("id") long id);

    Long memberExpiry(@Param("userId") long userId);

    QuotaClaimResult findClaim(@Param("id") long id, @Param("userId") long userId);

    List<Long> publishedIdsAfter(@Param("cursor") long cursor);
}
