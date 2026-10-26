package com.ecnu.tripmate.controller;

import com.ecnu.tripmate.cache.ItineraryCache;
import com.ecnu.tripmate.constant.UserConstant;
import com.ecnu.tripmate.exception.GlobalExceptionHandler;
import com.ecnu.tripmate.mapper.TripMemberMapper;
import com.ecnu.tripmate.mapper.TripMessageMapper;
import com.ecnu.tripmate.model.entity.*;
import com.ecnu.tripmate.model.vo.itinerary.ItineraryVO;
import com.ecnu.tripmate.model.vo.trip.TripMemberVO;
import com.ecnu.tripmate.service.impl.*;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实控制器、Session 登录校验和成员校验串联；仅数据库和缓存使用替身。 */
class PrivateTripReadTest {
    private MockMvc mvc;
    private TripMemberMapper memberMapper;
    private TripMessageMapper messageMapper;
    private ItineraryCache cache;
    private TripMessage message;
    private final User user = User.builder().id(100L).build();

    @BeforeEach
    void setup() {
        var trips = spy(new TripServiceImpl());
        var members = spy(new TripMemberServiceImpl());
        var itineraries = spy(new ItineraryServiceImpl());
        var messages = spy(new TripMessageServiceImpl());
        memberMapper = mock(TripMemberMapper.class);
        messageMapper = mock(TripMessageMapper.class);
        cache = mock(ItineraryCache.class);
        var trip = Trip.builder().id(7L).ownerId(100L).title("私人旅行")
                .departureLocation("上海").destination("杭州").travelDays(2)
                .departureDate(LocalDate.of(2028, 4, 1)).returnDate(LocalDate.of(2028, 4, 2))
                .departurePeriod("morning").returnPeriod("evening").build();
        doReturn(trip).when(trips).getById(7L);
        var member = TripMember.builder().tripId(7L).userId(100L).status("joined")
                .budgetLimit(BigDecimal.valueOf(800)).build();
        var memberVO = new TripMemberVO();
        memberVO.setUserId(100L);
        memberVO.setBudgetLimit(BigDecimal.valueOf(800));
        memberVO.setNickname("伙伴");
        doReturn(List.of(memberVO)).when(members).toMemberVOs(anyList());
        when(memberMapper.selectCountByQuery(any(QueryWrapper.class))).thenReturn(1L);
        when(memberMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(member));
        var itineraryMapper = mock(com.ecnu.tripmate.mapper.ItineraryMapper.class);
        ReflectionTestUtils.setField(trips, "itineraryMapper", itineraryMapper);
        ReflectionTestUtils.setField(trips, "tripMemberService", members);
        ReflectionTestUtils.setField(members, "mapper", memberMapper);
        ReflectionTestUtils.setField(members, "tripService", trips);
        ReflectionTestUtils.setField(itineraries, "tripMemberService", members);
        ReflectionTestUtils.setField(itineraries, "itineraryCache", cache);
        doReturn(Itinerary.builder().id(8L).tripId(7L).build()).when(itineraries).getById(8L);
        var snapshot = new ItineraryVO();
        snapshot.setId(8L);
        snapshot.setTripId(7L);
        when(cache.get(7L)).thenReturn(snapshot);
        ReflectionTestUtils.setField(messages, "tripMemberService", members);
        ReflectionTestUtils.setField(messages, "tripService", trips);
        ReflectionTestUtils.setField(messages, "mapper", messageMapper);
        message = TripMessage.builder().id(9L).tripId(7L).content("私聊内容").recallStatus("normal").build();
        doReturn(message).when(messages).getById(9L);
        when(messageMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of());

        var tripController = new TripController();
        var itineraryController = new ItineraryController();
        var messageController = new TripMessageController();
        var memberController = new TripMemberController();
        var users = new UserServiceImpl();
        for (Object controller : List.of(tripController, itineraryController, messageController, memberController)) {
            ReflectionTestUtils.setField(controller, "userService", users);
        }
        ReflectionTestUtils.setField(tripController, "tripService", trips);
        ReflectionTestUtils.setField(itineraryController, "itineraryService", itineraries);
        ReflectionTestUtils.setField(messageController, "tripMessageService", messages);
        ReflectionTestUtils.setField(memberController, "tripMemberService", members);
        mvc = MockMvcBuilders.standaloneSetup(tripController, itineraryController, messageController, memberController)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private MockHttpServletRequestBuilder read(String path) {
        if (path.equals("/tripMessage/get")) return post(path).param("id", "9");
        if (path.equals("/tripMessage/getAiByChangeStatus")) {
            return post(path).contentType("application/json").content("{\"tripId\":7,\"changeStatus\":\"pending\"}");
        }
        return get(path).param("tripId", "7").param("id", "8");
    }

    private MockHttpSession session() {
        var session = new MockHttpSession();
        session.setAttribute(UserConstant.USER_LOGIN_STATE, user);
        return session;
    }

    @ParameterizedTest
    @ValueSource(strings = {"/trip/get", "/trip/getPrompt", "/itinerary/get", "/itinerary/getIdByTripId",
            "/itinerary/getByTripId", "/tripMessage/get", "/tripMessage/page", "/tripMessage/latest",
            "/tripMessage/getAiByChangeStatus", "/trip/7/members"})
    void anonymousCannotReadPrivateData(String path) throws Exception {
        mvc.perform(read(path)).andExpect(jsonPath("$.code").value(40100));
        verifyNoInteractions(cache, messageMapper, memberMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/trip/get", "/trip/getPrompt", "/itinerary/get", "/itinerary/getIdByTripId",
            "/itinerary/getByTripId", "/tripMessage/get", "/tripMessage/page", "/tripMessage/latest",
            "/tripMessage/getAiByChangeStatus", "/trip/7/members"})
    void outsidersAndFormerMembersCannotReadEvenWithKnownIds(String path) throws Exception {
        // 当前已加入记录为空；历史上是否加入过，都不能通过有效成员校验。
        when(memberMapper.selectCountByQuery(any(QueryWrapper.class))).thenReturn(0L);
        when(memberMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of());
        mvc.perform(read(path).session(session())).andExpect(jsonPath("$.code").value(40101));
        verifyNoInteractions(cache, messageMapper);
        var query = org.mockito.ArgumentCaptor.forClass(QueryWrapper.class);
        if (path.equals("/trip/7/members")) verify(memberMapper).selectListByQuery(query.capture());
        else verify(memberMapper).selectCountByQuery(query.capture());
        assertTrue(query.getValue().toSQL().contains("joined"), "权限查询必须限制已加入状态");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/trip/get", "/trip/getPrompt", "/itinerary/get", "/itinerary/getIdByTripId",
            "/itinerary/getByTripId", "/tripMessage/get", "/trip/7/members"})
    void activeMembersCanRead(String path) throws Exception {
        mvc.perform(read(path).session(session())).andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void recalledMessageStillRequiresMembershipAndNeverReturnsOriginalContent() throws Exception {
        message.setRecallStatus("recalled");
        mvc.perform(read("/tripMessage/get").session(session()))
                .andExpect(jsonPath("$.data").value("消息已撤回"));
        when(memberMapper.selectCountByQuery(any(QueryWrapper.class))).thenReturn(0L);
        mvc.perform(read("/tripMessage/get").session(session())).andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void permissionIsCheckedOnEveryReadEvenWhenItineraryIsCached() throws Exception {
        mvc.perform(read("/itinerary/get").session(session())).andExpect(jsonPath("$.code").value(0));
        when(memberMapper.selectCountByQuery(any(QueryWrapper.class))).thenReturn(0L);
        mvc.perform(read("/itinerary/get").session(session())).andExpect(jsonPath("$.code").value(40101));
        verify(cache, times(1)).get(7L);
    }
}
