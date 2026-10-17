package com.ecnu.tripmate.model.enums.trip;

import lombok.Getter;

/** 出发和返回共用的旅行时段。 */
@Getter
public enum TripPeriodEnum {

    MORNING("上午", "morning", 1),
    AFTERNOON("下午", "afternoon", 2),
    EVENING("晚上", "evening", 3),
    FLEXIBLE("不限", "flexible", 0);

    /**
     * 枚举值的中文说明
     */
    private final String text;
    /**
     * 枚举对应的业务值
     */
    private final String value;
    /**
     * 时段先后顺序：上午1、下午2、晚上3；不限为0，不参与先后比较
     */
    private final int order;

    TripPeriodEnum(String text, String value, int order) {
        this.text = text;
        this.value = value;
        this.order = order;
    }

    public static TripPeriodEnum getEnumByValue(String value) {
        for (TripPeriodEnum period : values()) {
            if (period.value.equals(value)) {
                return period;
            }
        }
        return null;
    }

    /** 不限时段不参与同日先后顺序比较。 */
    public boolean isEarlierThan(TripPeriodEnum other) {
        return other != null && this != FLEXIBLE && other != FLEXIBLE && order < other.order;
    }
}
