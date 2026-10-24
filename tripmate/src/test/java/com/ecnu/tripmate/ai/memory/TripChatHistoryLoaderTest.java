package com.ecnu.tripmate.ai.memory;

import com.ecnu.tripmate.mapper.TripMessageMapper;
import com.ecnu.tripmate.model.entity.TripMessage;
import com.ecnu.tripmate.model.vo.trip.TripMemberVO;
import com.ecnu.tripmate.service.TripMemberService;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TripChatHistoryLoaderTest {
    private TripChatHistoryLoader loader;
    private TripMessageMapper messages;
    private TripMemberService members;

    @BeforeEach
    void setup() {
        loader = new TripChatHistoryLoader();
        messages = mock(TripMessageMapper.class);
        members = mock(TripMemberService.class);
        ReflectionTestUtils.setField(loader, "mapper", messages);
        ReflectionTestUtils.setField(loader, "tripMemberService", members);
        var member = new TripMemberVO();
        member.setUserId(100L);
        member.setNickname("小王");
        when(members.toMemberVOs(any())).thenReturn(List.of(member));
    }

    @Test
    void restoresOnlyTenPairsChronologicallyWithIdentityAndOriginalResponseStatus() {
        List<TripMessage> questions = new ArrayList<>();
        List<TripMessage> replies = new ArrayList<>();
        for (long i = 1; i <= 10; i++) {
            questions.add(TripMessage.builder().id(i).senderId(100L).content("问题" + i).build());
            replies.add(TripMessage.builder().id(i + 100).replyToId(i).content("回答" + i)
                    .changeStatus(i == 10 ? "applied" : "none").build());
        }
        Collections.reverse(replies);
        when(messages.selectRecentAiReplies(7L, 10)).thenReturn(replies);
        when(messages.selectListByQuery(any(QueryWrapper.class))).thenReturn(questions);
        var turns = loader.loadRecent(7L);
        assertEquals(10, turns.size());
        for (int i = 1; i <= 10; i++) {
            var turn = turns.get(i - 1);
            assertEquals("问题" + i, turn.getQuestion());
            assertEquals("回答" + i, turn.getContent());
            assertEquals(100L, turn.getUserId());
            assertEquals("小王", turn.getNickname());
            assertEquals(i == 10 ? "pending" : "none", turn.getChangeStatus());
        }
        verify(messages).selectRecentAiReplies(7L, 10);
    }

    @Test
    void emptyHistoryHasNoSyntheticBackgroundOrAcknowledgement() {
        when(messages.selectRecentAiReplies(7L, 10)).thenReturn(List.of());
        assertTrue(loader.loadRecent(7L).isEmpty());
        verifyNoInteractions(members);
    }

    @Test
    void missingQuestionDoesNotLeaveAnOrphanAnswer() {
        when(messages.selectRecentAiReplies(7L, 10)).thenReturn(List.of(
                TripMessage.builder().id(21L).replyToId(11L).content("回答").build()));
        when(messages.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of());
        assertTrue(loader.loadRecent(7L).isEmpty());
    }
}
