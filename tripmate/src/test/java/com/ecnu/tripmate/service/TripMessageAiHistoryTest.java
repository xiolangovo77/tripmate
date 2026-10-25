package com.ecnu.tripmate.service;

import com.ecnu.tripmate.ai.AiItineraryGeneratorService;
import com.ecnu.tripmate.ai.AiItineraryGeneratorServiceFactory;
import com.ecnu.tripmate.cache.TripChatHistoryCache;
import com.ecnu.tripmate.model.dto.ai.TripChatTurn;
import com.ecnu.tripmate.ai.model.MessageResponseResult;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.mapper.TripMemberMapper;
import com.ecnu.tripmate.model.entity.TripMessage;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.model.vo.itinerary.ItineraryVO;
import com.ecnu.tripmate.service.impl.TripMessageServiceImpl;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TripMessageAiHistoryTest {
    private TripMessageServiceImpl service;
    private TripChatHistoryCache cache;
    private AiItineraryGeneratorService ai;
    private AiItineraryGeneratorServiceFactory factory;
    private TripMessage question;
    private MessageResponseResult response;
    private final User user = User.builder().id(100L).nickname("小王").build();

    @BeforeEach
    void setup() {
        service = spy(new TripMessageServiceImpl());
        cache = mock(TripChatHistoryCache.class);
        factory = mock(AiItineraryGeneratorServiceFactory.class);
        ai = mock(AiItineraryGeneratorService.class);
        var trips = mock(TripService.class);
        var itineraries = mock(ItineraryService.class);
        var members = mock(TripMemberService.class);
        var memberMapper = mock(TripMemberMapper.class);
        when(members.getMapper()).thenReturn(memberMapper);
        when(memberMapper.selectCountByQuery(any(QueryWrapper.class))).thenReturn(1L);
        var itinerary = new ItineraryVO();
        when(itineraries.getByTripId(7L, user)).thenReturn(itinerary);
        when(itineraries.getItineraryInfosPrompt(itinerary)).thenReturn("最新行程B\n");
        when(trips.getInitItineraryPrompt(7L)).thenReturn("最新旅行和成员背景\n");
        when(cache.getRecent(7L)).thenReturn(List.of());
        when(factory.getAiItineraryGeneratorService(7L)).thenReturn(ai);
        response = new MessageResponseResult();
        response.setContent("建议取消第二天下午购物，待采纳");
        response.setChangeStatus("pending");
        when(ai.messageResponse(anyString())).thenReturn(response);
        question = TripMessage.builder().id(5L).tripId(7L).senderId(100L).role("user")
                .recallStatus("normal").content("取消第二天下午购物").build();
        doReturn(question).when(service).getById(5L);
        doAnswer(invocation -> {
            TripMessage saved = invocation.getArgument(0);
            saved.setId(6L);
            return true;
        }).when(service).save(any(TripMessage.class));
        ReflectionTestUtils.setField(service, "tripService", trips);
        ReflectionTestUtils.setField(service, "itineraryService", itineraries);
        ReflectionTestUtils.setField(service, "tripMemberService", members);
        ReflectionTestUtils.setField(service, "aiItineraryGeneratorServiceFactory", factory);
        ReflectionTestUtils.setField(service, "chatHistoryCache", cache);
        ReflectionTestUtils.setField(service, "eventPublisher", mock(ApplicationEventPublisher.class));
    }

    @Test
    void fullSnapshotIsSentButOnlyOriginalQuestionAndReplyAreScheduledForCache() {
        var previous = new TripChatTurn(4L, 101L, "小李", "少走路", "建议减少步行", "pending");
        when(cache.getRecent(7L)).thenReturn(List.of(previous));
        assertEquals(response.getContent(), service.addAi(7L, 5L, user));
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).messageResponse(prompt.capture());
        assertTrue(prompt.getValue().contains("最新旅行和成员背景"));
        assertTrue(prompt.getValue().contains("最新行程B"));
        var input = cn.hutool.json.JSONUtil.parseObj(prompt.getValue());
        assertEquals(1, input.getJSONArray("history").size());
        assertEquals("少走路", input.getJSONArray("history").getJSONObject(0).getStr("question"));
        assertEquals("取消第二天下午购物", input.getJSONObject("currentQuestion").getStr("content"));
        verify(factory).getAiItineraryGeneratorService(7L);
        var turn = ArgumentCaptor.forClass(TripChatTurn.class);
        var order = inOrder(service, cache);
        order.verify(service).save(any(TripMessage.class));
        order.verify(cache).appendAfterCommit(eq(7L), turn.capture());
        assertEquals(new TripChatTurn(6L, 100L, "小王", "取消第二天下午购物",
                response.getContent(), "pending"), turn.getValue());
    }

    @Test
    void failedDatabaseSaveDoesNotScheduleCacheWrite() {
        doReturn(false).when(service).save(any(TripMessage.class));
        assertThrows(BusinessException.class, () -> service.addAi(7L, 5L, user));
        verify(cache, never()).appendAfterCommit(anyLong(), any());
    }

    @Test
    void invalidModelStatusDoesNotPersistReplyOrCache() {
        response.setChangeStatus("applied");
        assertThrows(BusinessException.class, () -> service.addAi(7L, 5L, user));
        verify(service, never()).save(any(TripMessage.class));
        verify(cache, never()).appendAfterCommit(anyLong(), any());
    }

    @Test
    void anotherTripsQuestionIsRejectedBeforeReadingHistory() {
        question.setTripId(99L);
        assertThrows(BusinessException.class, () -> service.addAi(7L, 5L, user));
        verifyNoInteractions(cache, factory, ai);
    }
}
