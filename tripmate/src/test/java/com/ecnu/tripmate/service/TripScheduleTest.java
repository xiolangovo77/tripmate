package com.ecnu.tripmate.service;

import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.mapper.ItineraryMapper;
import com.ecnu.tripmate.mapper.TripMemberMapper;
import com.ecnu.tripmate.model.dto.trip.TripAddRequest;
import com.ecnu.tripmate.model.dto.trip.TripScheduleRequest;
import com.ecnu.tripmate.model.dto.trip.TripUpdateRequest;
import com.ecnu.tripmate.model.entity.Trip;
import com.ecnu.tripmate.model.entity.TripMember;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.service.impl.TripServiceImpl;
import com.ecnu.tripmate.util.TripScheduleValidator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 无数据库、无网络，验证实际服务的日期校验和持久化参数。 */
class TripScheduleTest {
    private TripServiceImpl service;
    private ItineraryMapper itineraries;
    private TripMemberService members;
    private User owner;

    @BeforeEach
    void setup() {
        service = spy(new TripServiceImpl());
        itineraries = mock(ItineraryMapper.class);
        members = mock(TripMemberService.class);
        ReflectionTestUtils.setField(service, "itineraryMapper", itineraries);
        ReflectionTestUtils.setField(service, "tripMemberService", members);
        owner = new User();
        owner.setId(11L);
    }

    private <T extends TripScheduleRequest> T schedule(T request) {
        request.setDepartureLocation(" 上海 ");
        request.setDestination("杭州");
        request.setDepartureDate(LocalDate.of(2028, 2, 28));
        request.setReturnDate(LocalDate.of(2028, 3, 1));
        request.setDeparturePeriod("morning");
        request.setReturnPeriod("evening");
        return request;
    }

    @Test
    void calculatesInclusiveLeapYearAndSameDay() {
        var request = schedule(new TripScheduleRequest());
        assertEquals(3, TripScheduleValidator.validateAndNormalize(request));
        assertEquals("上海", request.getDepartureLocation());
        request.setReturnDate(request.getDepartureDate());
        assertEquals(1, TripScheduleValidator.validateAndNormalize(request));
    }

    @Test
    void rejectsReversedDatesMissingDatesAndDatabaseOverflow() {
        var request = schedule(new TripScheduleRequest());
        request.setReturnDate(request.getDepartureDate().minusDays(1));
        assertThrows(BusinessException.class, () -> TripScheduleValidator.validateAndNormalize(request));
        request.setReturnDate(null);
        assertThrows(BusinessException.class, () -> TripScheduleValidator.validateAndNormalize(request));
        request.setReturnDate(request.getDepartureDate().plusDays(65535));
        assertThrows(BusinessException.class, () -> TripScheduleValidator.validateAndNormalize(request));
    }

    @Test
    void validatesSameDayPeriodsAndDefaults() {
        var request = schedule(new TripScheduleRequest());
        request.setReturnDate(request.getDepartureDate());
        request.setDeparturePeriod("evening");
        request.setReturnPeriod("morning");
        assertThrows(BusinessException.class, () -> TripScheduleValidator.validateAndNormalize(request));
        request.setReturnPeriod(null);
        assertEquals(1, TripScheduleValidator.validateAndNormalize(request));
        assertEquals("flexible", request.getReturnPeriod());
        request.setDeparturePeriod("night");
        assertThrows(BusinessException.class, () -> TripScheduleValidator.validateAndNormalize(request));
    }

    @Test
    void validatesLocations() {
        var request = schedule(new TripScheduleRequest());
        request.setDepartureLocation(" ");
        assertThrows(BusinessException.class, () -> TripScheduleValidator.validateAndNormalize(request));
        request.setDepartureLocation("沪".repeat(121));
        assertThrows(BusinessException.class, () -> TripScheduleValidator.validateAndNormalize(request));
    }

    @Test
    void creationPersistsDatesAndDerivedDaysAndAddsOwner() {
        var request = schedule(new TripAddRequest());
        request.setBudgetLimit(BigDecimal.valueOf(800));
        doAnswer(call -> {
            Trip saved = call.getArgument(0);
            assertEquals(3, saved.getTravelDays());
            assertEquals(request.getDepartureDate(), saved.getDepartureDate());
            assertEquals(request.getReturnDate(), saved.getReturnDate());
            assertEquals("morning", saved.getDeparturePeriod());
            assertEquals("evening", saved.getReturnPeriod());
            assertEquals("上海", saved.getDepartureLocation());
            assertEquals("planning", saved.getStatus());
            saved.setId(22L);
            return true;
        }).when(service).save(any(Trip.class));
        assertEquals(22L, service.addTrip(request, owner));
        verify(members).add(22L, "owner", null, owner, request.getBudgetLimit());
    }

    private TripUpdateRequest updateRequest() {
        var request = schedule(new TripUpdateRequest());
        request.setDepartureLocation("上海");
        request.setId(22L);
        request.setTitle("新标题");
        return request;
    }

    private Trip existing() {
        Trip trip = Trip.builder().id(22L).ownerId(11L).title("旧标题")
                .departureLocation("上海").destination("杭州")
                .departureDate(LocalDate.of(2028, 2, 28)).returnDate(LocalDate.of(2028, 3, 1))
                .departurePeriod("morning").returnPeriod("evening").travelDays(3).build();
        doReturn(trip).when(service).isTripExists(22L);
        doReturn(true).when(service).updateById(any(Trip.class));
        return trip;
    }

    @Test
    void editingDatesRecalculatesDaysBeforeAnItineraryExists() {
        Trip trip = existing();
        var request = updateRequest();
        request.setReturnDate(LocalDate.of(2028, 3, 2));
        assertTrue(service.update(request, owner));
        assertEquals(4, trip.getTravelDays());
        assertEquals(request.getReturnDate(), trip.getReturnDate());
    }

    @Test
    void existingItineraryLocksScheduleButAllowsTitle() {
        Trip trip = existing();
        when(itineraries.selectCountByQuery(any())).thenReturn(1L);
        var request = updateRequest();
        request.setReturnDate(request.getReturnDate().plusDays(1));
        assertThrows(BusinessException.class, () -> service.update(request, owner));
        verify(service, never()).updateById(any(Trip.class));
        request.setReturnDate(trip.getReturnDate());
        assertTrue(service.update(request, owner));
        assertEquals("新标题", trip.getTitle());
    }

    @Test
    void nonOwnerCannotChangeDates() {
        existing();
        var guest = new User();
        guest.setId(12L);
        assertThrows(BusinessException.class, () -> service.update(updateRequest(), guest));
        verify(service, never()).updateById(any(Trip.class));
    }

    @Test
    void detailAndAiContextCarryTheSavedRouteDatesAndPeriods() {
        existing();
        TripMemberMapper memberMapper = mock(TripMemberMapper.class);
        when(members.getMapper()).thenReturn(memberMapper);
        TripMember member = new TripMember();
        member.setNickname("同行者");
        member.setBudgetLimit(BigDecimal.valueOf(800));
        member.setTravelPace("balanced");
        member.setFavoriteActivities("[\"自然\"]");
        when(memberMapper.selectListByQuery(any())).thenReturn(List.of(member));
        when(itineraries.selectCountByQuery(any())).thenReturn(1L);
        var detail = service.getTripById(22L);
        assertEquals("上海", detail.getDepartureLocation());
        assertEquals(LocalDate.of(2028, 2, 28), detail.getDepartureDate());
        assertEquals("evening", detail.getReturnPeriod());
        assertTrue(detail.isHasItinerary());
        String prompt = service.getInitItineraryPrompt(22L);
        assertTrue(prompt.contains("出发地：上海"));
        assertTrue(prompt.contains("出发日期：2028-02-28"));
        assertTrue(prompt.contains("返回日期（到达出发地）：2028-03-01"));
        assertTrue(prompt.contains("返回时段：evening"));
        assertTrue(prompt.contains("旅行天数：3天"));
    }

    @Test
    void jsonUsesLocalDateAndIgnoresClientSuppliedDayCount() throws Exception {
        var mapper = JsonMapper.builder().addModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        var request = mapper.readValue("""
                {"departureLocation":"上海","destination":"杭州","departureDate":"2028-02-28",
                 "returnDate":"2028-03-01","travelDays":99,"budgetLimit":800}
                """, TripAddRequest.class);
        assertEquals(3, TripScheduleValidator.validateAndNormalize(request));
        assertEquals("flexible", request.getDeparturePeriod());
        assertThrows(Exception.class, () -> mapper.readValue("{\"departureDate\":\"2028-02-30\"}", TripAddRequest.class));
    }
}
