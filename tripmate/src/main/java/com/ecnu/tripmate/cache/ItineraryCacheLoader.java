package com.ecnu.tripmate.cache;

import cn.hutool.core.bean.BeanUtil;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.mapper.ItineraryItemMapper;
import com.ecnu.tripmate.mapper.ItineraryMapper;
import com.ecnu.tripmate.model.entity.Itinerary;
import com.ecnu.tripmate.model.entity.ItineraryItem;
import com.ecnu.tripmate.model.vo.itinerary.ItineraryItemVO;
import com.ecnu.tripmate.model.vo.itinerary.ItineraryVO;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 从数据库读取当前行程及有序明细，组装用于缓存的完整快照。 */
@Service
public class ItineraryCacheLoader {
    @Resource
    private ItineraryMapper itineraryMapper;

    @Resource
    private ItineraryItemMapper itineraryItemMapper;

    /** 缓存回填或提交后刷新：新事务读取最新已提交的完整行程。 */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public ItineraryVO loadLatest(Long tripId) {
        Itinerary itinerary = itineraryMapper.selectOneByQuery(QueryWrapper.create()
                .eq("tripId", tripId).orderBy("createTime", false).orderBy("id", false).limit(1));
        if (itinerary == null) {
            return null;
        }
        List<ItineraryItem> items = itineraryItemMapper.selectListByQuery(QueryWrapper.create()
                .eq("itineraryId", itinerary.getId())
                .orderBy("dayNumber", true).orderBy("sortOrder", true)
                .orderBy("startTime", true).orderBy("id", true));
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不存在行程明细");
        }
        ItineraryVO snapshot = BeanUtil.copyProperties(itinerary, ItineraryVO.class);
        snapshot.setItineraryItemVOList(BeanUtil.copyToList(items, ItineraryItemVO.class));
        return snapshot;
    }
}
