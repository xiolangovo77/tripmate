package com.ecnu.tripmate.service;

import cn.hutool.json.JSONUtil;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.mapper.TripMessageMapper;
import com.ecnu.tripmate.model.entity.TripMessage;
import com.ecnu.tripmate.model.entity.TripMember;
import com.ecnu.tripmate.model.vo.itinerary.ItineraryVO;
import com.ecnu.tripmate.service.impl.TripMessageServiceImpl;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 离线验证真实窗口记忆的初始化、持久化和重复加载行为。 */
class TripMessageMemoryTest {
    private TripMessageServiceImpl service;
    private TripService trips;
    private ItineraryService itineraries;
    private TripMemberService members;
    private TripMessageMapper messages;
    private InMemoryChatMemoryStore store;
    private MessageWindowChatMemory memory;

    @BeforeEach
    void setup() {
        service = new TripMessageServiceImpl();
        trips = mock(TripService.class);
        itineraries = mock(ItineraryService.class);
        members = mock(TripMemberService.class);
        messages = mock(TripMessageMapper.class);
        ReflectionTestUtils.setField(service, "tripService", trips);
        ReflectionTestUtils.setField(service, "itineraryService", itineraries);
        ReflectionTestUtils.setField(service, "tripMemberService", members);
        ReflectionTestUtils.setField(service, "mapper", messages);
        store = new InMemoryChatMemoryStore();
        memory = MessageWindowChatMemory.builder().id(7L).maxMessages(50).chatMemoryStore(store).build();
        when(trips.getInitItineraryPrompt(7L)).thenReturn("旅行：杭州；成员：小王，预算2000元");
        when(itineraries.getIdByTripId(7L)).thenReturn(null);
        when(messages.selectRecentAiReplies(7L, 20)).thenReturn(List.of());
        when(members.list(any(QueryWrapper.class)))
                .thenReturn(List.of(TripMember.builder().userId(100L).nickname("小王").build()));
    }

    @Test
    void loadsContextAndTwentyPairsInChronologicalOrderWithinWindow() {
        ItineraryVO itinerary = new ItineraryVO();
        when(itineraries.getIdByTripId(7L)).thenReturn(8L);
        when(itineraries.get(8L)).thenReturn(itinerary);
        when(itineraries.getItineraryInfosPrompt(itinerary)).thenReturn("行程：西湖；明细：第一天09:00散步");
        List<TripMessage> questions = new ArrayList<>();
        List<TripMessage> replies = new ArrayList<>();
        for (long i = 1; i <= 20; i++) {
            questions.add(TripMessage.builder().id(i).senderId(100L).content("问题" + i).build());
            replies.add(TripMessage.builder().id(i + 100).replyToId(i).content("回答" + i)
                    .changeStatus(i == 20 ? "applied" : "none").build());
        }
        Collections.reverse(replies);
        when(messages.selectRecentAiReplies(7L, 20)).thenReturn(replies);
        when(messages.selectListByQuery(any(QueryWrapper.class))).thenReturn(questions);

        service.loadChatMemory(7L, memory);

        List<ChatMessage> history = store.getMessages(7L);
        assertEquals(42, history.size());
        String context = ((UserMessage) history.getFirst()).singleText();
        assertTrue(context.contains("成员：小王"));
        assertTrue(context.contains("第一天09:00散步"));
        for (int i = 1; i <= 20; i++) {
            assertEquals("用户昵称：小王\n消息内容：问题" + i, ((UserMessage) history.get(i * 2)).singleText());
            var response = JSONUtil.parseObj(((AiMessage) history.get(i * 2 + 1)).text());
            assertEquals("回答" + i, response.getStr("content"));
            assertEquals(i == 20 ? "pending" : "none", response.getStr("changeStatus"));
        }
        // AI Service设置系统提示词后，42条初始化消息仍完整保留。
        memory.add(SystemMessage.from("群聊系统提示词"));
        assertEquals(43, memory.messages().size());
        assertTrue(memory.messages().contains(history.getFirst()));
    }

    @Test
    void supportsFirstItineraryGenerationAndDoesNotReloadExistingStore() {
        service.loadChatMemory(7L, memory);
        assertEquals(2, memory.messages().size());
        assertTrue(((UserMessage) memory.messages().getFirst()).singleText().contains("尚未生成行程"));
        var recreated = MessageWindowChatMemory.builder().id(7L).maxMessages(50).chatMemoryStore(store).build();
        service.loadChatMemory(7L, recreated);
        assertEquals(memory.messages(), recreated.messages());
        verify(trips, times(1)).getInitItineraryPrompt(7L);
        verify(messages, times(1)).selectRecentAiReplies(7L, 20);
        verify(itineraries, never()).get(any());
    }

    @Test
    void rejectsInvalidOrMismatchedMemoryIdsBeforeReadingData() {
        assertThrows(BusinessException.class, () -> service.loadChatMemory(null, memory));
        assertThrows(BusinessException.class, () -> service.loadChatMemory(0L, memory));
        assertThrows(BusinessException.class, () -> service.loadChatMemory(7L, null));
        assertThrows(BusinessException.class, () -> service.loadChatMemory(8L, memory));
        verifyNoInteractions(trips, itineraries, messages);
    }

    @Test
    void missingQuestionDoesNotLeaveAnOrphanAnswer() {
        when(messages.selectRecentAiReplies(7L, 20)).thenReturn(List.of(
                TripMessage.builder().id(21L).replyToId(11L).content("回答").changeStatus("none").build()));
        // 关联查询后问题被撤回或删除，第二次查询不再返回。
        when(messages.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of());
        service.loadChatMemory(7L, memory);
        assertEquals(2, memory.messages().size());
    }

    @Test
    void databaseFailureDoesNotPersistPartialInitialization() {
        when(messages.selectRecentAiReplies(7L, 20)).thenThrow(new IllegalStateException("数据库不可用"));
        assertThrows(IllegalStateException.class, () -> service.loadChatMemory(7L, memory));
        assertTrue(memory.messages().isEmpty());
    }
}
