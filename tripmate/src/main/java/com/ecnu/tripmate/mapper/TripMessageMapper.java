package com.ecnu.tripmate.mapper;

import com.mybatisflex.core.BaseMapper;
import com.ecnu.tripmate.model.entity.TripMessage;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 旅行消息表 映射层。
 *
 * @author Wolfcub
 */
public interface TripMessageMapper extends BaseMapper<TripMessage> {

    /** 最近的完整AI问答中的回答，按回答创建时间、id倒序。 */
    List<TripMessage> selectRecentAiReplies(@Param("tripId") Long tripId, @Param("limit") int limit);

}
