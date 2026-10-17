package com.ecnu.tripmate.util;

import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.model.dto.trip.TripScheduleRequest;
import com.ecnu.tripmate.model.enums.trip.TripPeriodEnum;
import java.time.temporal.ChronoUnit;

public final class TripScheduleValidator {
    private TripScheduleValidator() {}

    public static int validateAndNormalize(TripScheduleRequest request) {
        if (request == null) fail("旅行信息不能为空");
        request.setDepartureLocation(location(request.getDepartureLocation(), "出发地"));
        request.setDestination(location(request.getDestination(), "目的地"));
        if (request.getDepartureDate() == null || request.getReturnDate() == null) fail("出发日期和返回日期不能为空");
        if (request.getDepartureDate().getYear() < 1000 || request.getReturnDate().getYear() > 9999) fail("日期超出支持范围");
        long days = ChronoUnit.DAYS.between(request.getDepartureDate(), request.getReturnDate()) + 1;
        if (days <= 0) fail("返回日期不能早于出发日期");
        if (days > 7) fail("旅行天数不能超过7天");
        TripPeriodEnum departurePeriod = period(request.getDeparturePeriod());
        TripPeriodEnum returnPeriod = period(request.getReturnPeriod());
        request.setDeparturePeriod(departurePeriod.getValue());
        request.setReturnPeriod(returnPeriod.getValue());
        if (days == 1 && returnPeriod.isEarlierThan(departurePeriod)) {
            fail("同日往返时，返回时段不能早于出发时段");
        }
        return (int) days;
    }

    private static String location(String value, String label) {
        if (value == null || value.isBlank()) fail(label + "不能为空");
        value = value.trim();
        if (value.length() > 120) fail(label + "不能超过120个字符");
        return value;
    }

    private static TripPeriodEnum period(String value) {
        if (value == null) return TripPeriodEnum.FLEXIBLE;
        TripPeriodEnum period = TripPeriodEnum.getEnumByValue(value);
        if (period == null) fail("时段仅支持morning、afternoon、evening、flexible");
        return period;
    }

    private static void fail(String message) {
        throw new BusinessException(ErrorCode.PARAMS_ERROR, message);
    }
}
