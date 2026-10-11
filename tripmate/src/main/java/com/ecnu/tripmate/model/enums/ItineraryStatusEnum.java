package com.ecnu.tripmate.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

@Getter
public enum ItineraryStatusEnum {

    DRAFT("草案", "draft"),
    CONFIRMED("已确认", "confirmed");

    private final String text;

    private final String value;

    ItineraryStatusEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     * @return 枚举值
     */
    public static ItineraryStatusEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (ItineraryStatusEnum anEnum : ItineraryStatusEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}