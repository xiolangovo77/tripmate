package com.ecnu.tripmate.model.enums.tripMessage;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

@Getter
public enum TripMessageRoleEnum {

    USER("用户", "user"),
    ASSISTANT("AI", "assistant");

    private final String text;

    private final String value;

    TripMessageRoleEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     * @return 枚举值
     */
    public static TripMessageRoleEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (TripMessageRoleEnum anEnum : TripMessageRoleEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}