package com.ecnu.tripmate.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

@Getter
public enum TravelPaceEnum {

    RELAXED("悠闲", "relaxed"),
    BALANCED("适中", "balanced"),
    PACKED("充实", "packed");

    private final String text;

    private final String value;

    TravelPaceEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     * @return 枚举值
     */
    public static TravelPaceEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (TravelPaceEnum anEnum : TravelPaceEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}
