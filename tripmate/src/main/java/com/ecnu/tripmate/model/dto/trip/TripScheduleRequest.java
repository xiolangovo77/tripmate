package com.ecnu.tripmate.model.dto.trip;

import lombok.Data;
import com.ecnu.tripmate.model.enums.trip.TripPeriodEnum;
import java.io.Serializable;
import java.time.LocalDate;

/** 旅行路线与日期。天数由服务端计算，不接受客户端指定。 */
@Data
public class TripScheduleRequest implements Serializable {

    /**
     * 出发地
     */
    private String departureLocation;

    /**
     * 目的地
     */
    private String destination;

    /**
     * 出发日期
     */
    private LocalDate departureDate;

    /**
     * 出发时间段
     */
    private String departurePeriod = TripPeriodEnum.FLEXIBLE.getValue();

    /**
     * 回到出发地的日期
     */
    private LocalDate returnDate;

    /**
     * 回到出发地的时间段
     */
    private String returnPeriod = TripPeriodEnum.FLEXIBLE.getValue();
}
