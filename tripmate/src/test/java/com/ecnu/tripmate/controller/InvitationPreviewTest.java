package com.ecnu.tripmate.controller;

import com.ecnu.tripmate.constant.UserConstant;
import com.ecnu.tripmate.exception.GlobalExceptionHandler;
import com.ecnu.tripmate.mapper.*;
import com.ecnu.tripmate.model.entity.*;
import com.ecnu.tripmate.service.impl.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InvitationPreviewTest {
    private MockMvc mvc;
    private TripInvitationMapper invitations;
    private TripMemberMapper members;
    private UserMapper users;
    private Trip trip;
    private TripInvitation invitation;

    @BeforeEach
    void setup() {
        var service = new TripInvitationServiceImpl();
        var trips = spy(new TripServiceImpl());
        invitations = mock(TripInvitationMapper.class);
        members = mock(TripMemberMapper.class);
        users = mock(UserMapper.class);
        trip = Trip.builder().id(7L).ownerId(100L).title("杭州同行").status("planning")
                .departureLocation("上海").destination("杭州").travelDays(2)
                .departureDate(LocalDate.of(2028, 4, 1)).returnDate(LocalDate.of(2028, 4, 2))
                .departurePeriod("morning").returnPeriod("evening").build();
        doReturn(trip).when(trips).getById(7L);
        invitation = TripInvitation.builder().id(10L).tripId(7L).inviteCode("ABCD2345")
                .status("active").expiresTime(LocalDateTime.now().plusDays(1)).build();
        when(invitations.selectOneByQuery(any(QueryWrapper.class))).thenReturn(invitation);
        when(invitations.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(invitation));
        when(members.selectCountByQuery(any(QueryWrapper.class))).thenReturn(2L, 0L);
        when(users.selectOneByQuery(any(QueryWrapper.class)))
                .thenReturn(User.builder().nickname("发起人").password("不能公开").build());
        ReflectionTestUtils.setField(service, "mapper", invitations);
        ReflectionTestUtils.setField(service, "tripService", trips);
        ReflectionTestUtils.setField(service, "tripMemberMapper", members);
        ReflectionTestUtils.setField(service, "userMapper", users);
        var controller = new TripInvitationController();
        ReflectionTestUtils.setField(controller, "tripInvitationService", service);
        ReflectionTestUtils.setField(controller, "userService", new UserServiceImpl());
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder preview(String code) {
        return post("/tripInvitation/preview").contentType("application/json")
                .content("{\"tripId\":7,\"inviteCode\":\"" + code + "\"}");
    }

    private MockHttpSession session(long id) {
        var session = new MockHttpSession();
        session.setAttribute(UserConstant.USER_LOGIN_STATE, User.builder().id(id).build());
        return session;
    }

    @Test
    void anonymousWithValidCodeReceivesOnlyPreviewFieldsAndNoSession() throws Exception {
        var result = mvc.perform(preview("ABCD2345")).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ownerNickname").value("发起人"))
                .andExpect(jsonPath("$.data.memberCount").value(2))
                .andExpect(jsonPath("$.data.joined").value(false)).andReturn();
        var data = new ObjectMapper().readTree(result.getResponse().getContentAsString()).get("data");
        Set<String> fields = new HashSet<>();
        data.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("title", "departureLocation", "destination", "departureDate", "departurePeriod",
                "returnDate", "returnPeriod", "travelDays", "ownerNickname", "memberCount", "joined"), fields);
        assertNull(result.getRequest().getSession(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "wrong", "expired", "revoked", "confirmed"})
    void invalidInvitationNeverReturnsTripOrMemberInformation(String reason) throws Exception {
        String code = "ABCD2345";
        switch (reason) {
            case "missing" -> code = "";
            case "wrong" -> when(invitations.selectOneByQuery(any(QueryWrapper.class))).thenReturn(null);
            case "expired" -> invitation.setExpiresTime(LocalDateTime.now().minusDays(1));
            case "revoked" -> invitation.setStatus("revoked");
            case "confirmed" -> trip.setStatus("confirmed");
        }
        mvc.perform(preview(code)).andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(users, members);
    }

    @Test
    void joinedFlagUsesCurrentSessionAndActiveMembership() throws Exception {
        when(members.selectCountByQuery(any(QueryWrapper.class))).thenReturn(2L, 1L);
        mvc.perform(preview("ABCD2345").session(session(101L)))
                .andExpect(jsonPath("$.data.joined").value(true));
        var query = org.mockito.ArgumentCaptor.forClass(QueryWrapper.class);
        verify(members, times(2)).selectCountByQuery(query.capture());
        String sql = query.getAllValues().get(1).toSQL();
        assertTrue(sql.contains("joined") && sql.contains("101") && sql.contains("userId"));
    }

    @Test
    void formerMemberPreviewDoesNotRevealOtherMembersOrClaimJoined() throws Exception {
        mvc.perform(preview("ABCD2345").session(session(101L)))
                .andExpect(jsonPath("$.data.joined").value(false))
                .andExpect(jsonPath("$.data.tripMembers").doesNotExist());
    }

    @Test
    void onlyOwnerCanRetrieveInvitationCode() throws Exception {
        mvc.perform(get("/tripInvitation/getById").param("tripId", "7"))
                .andExpect(jsonPath("$.code").value(40100));
        mvc.perform(get("/tripInvitation/getById").param("tripId", "7").session(session(101L)))
                .andExpect(jsonPath("$.code").value(40101));
        verifyNoInteractions(invitations);
        mvc.perform(get("/tripInvitation/getById").param("tripId", "7").session(session(100L)))
                .andExpect(jsonPath("$.data.inviteCode").value("ABCD2345"));
    }

    @Test
    void invitationWithoutExpiryRemainsValid() throws Exception {
        invitation.setExpiresTime(null);
        mvc.perform(preview("ABCD2345")).andExpect(jsonPath("$.code").value(0));
    }
}
