package com.ecnu.tripmate.model.enums.trip;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

@Getter
public enum TripMemberRoleEnum {

    OWNER("发起人", "owner"),
    MEMBER("普通成员", "member");

    private final String text;

    private final String value;

    TripMemberRoleEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     * @return 枚举值
     */
    public static TripMemberRoleEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (TripMemberRoleEnum anEnum : TripMemberRoleEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}