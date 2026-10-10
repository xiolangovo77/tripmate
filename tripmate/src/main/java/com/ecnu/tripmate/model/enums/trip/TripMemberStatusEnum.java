package com.ecnu.tripmate.model.enums.trip;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

@Getter
public enum TripMemberStatusEnum {

    JOINED("已加入", "joined"),
    LEFT("已退出", "left");

    private final String text;

    private final String value;

    TripMemberStatusEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     * @return 枚举值
     */
    public static TripMemberStatusEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (TripMemberStatusEnum anEnum : TripMemberStatusEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}