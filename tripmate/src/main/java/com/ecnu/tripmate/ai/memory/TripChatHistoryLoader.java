package com.ecnu.tripmate.ai.memory;

import cn.hutool.core.util.StrUtil;
import com.ecnu.tripmate.mapper.TripMessageMapper;
import com.ecnu.tripmate.model.entity.TripMessage;
import com.ecnu.tripmate.model.vo.trip.TripMemberVO;
import com.ecnu.tripmate.service.TripMemberService;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TripChatHistoryLoader {
    public static final int HISTORY_PAIR_LIMIT = 10;

    @Resource
    private TripMessageMapper mapper;

    @Resource
    private TripMemberService tripMemberService;

    /**
     * 从数据库加载历史记忆，独立事务读取已提交消息，避免外层长事务的旧快照覆盖最新缓存。
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<TripChatTurn> loadRecent(Long tripId) {
        List<TripMessage> replies = mapper.selectRecentAiReplies(tripId, HISTORY_PAIR_LIMIT);
        if (replies.isEmpty()) {
            return List.of();
        }
        var questionIds = replies.stream().map(TripMessage::getReplyToId).distinct().toList();
        var questions = mapper.selectListByQuery(new QueryWrapper()
                        .in("id", questionIds).eq("tripId", tripId)
                        .eq("role", "user").eq("recallStatus", "normal"))
                .stream().collect(Collectors.toMap(TripMessage::getId, Function.identity()));
        // 包含已退出成员，历史问答仍保留提问者身份。
        var nicknames = tripMemberService.toMemberVOs(
                        tripMemberService.list(new QueryWrapper().eq("tripId", tripId)))
                .stream().collect(Collectors.toMap(TripMemberVO::getUserId,
                        member -> StrUtil.blankToDefault(member.getNickname(), "未知成员"),
                        (first, second) -> first));
        List<TripChatTurn> turns = new ArrayList<>();
        for (int i = replies.size() - 1; i >= 0; i--) {
            TripMessage reply = replies.get(i);
            TripMessage question = questions.get(reply.getReplyToId());
            if (question == null || StrUtil.isBlank(question.getContent())) {
                continue;
            }
            turns.add(new TripChatTurn(reply.getId(), question.getSenderId(),
                    nicknames.getOrDefault(question.getSenderId(), "未知成员"),
                    question.getContent(), reply.getContent(),
                    "none".equals(reply.getChangeStatus()) ? "none" : "pending"));
        }
        return turns;
    }
}
