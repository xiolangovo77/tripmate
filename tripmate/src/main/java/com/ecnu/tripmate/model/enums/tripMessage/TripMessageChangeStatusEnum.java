package com.ecnu.tripmate.model.enums.tripMessage;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

@Getter
public enum TripMessageChangeStatusEnum {

    NONE("无修改建议", "none"),
    PENDING("待处理", "pending"),
    APPLIED("已应用", "applied"),
    IGNORED("已忽略", "ignored");

    private final String text;

    private final String value;

    TripMessageChangeStatusEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     * @return 枚举值
     */
    public static TripMessageChangeStatusEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (TripMessageChangeStatusEnum anEnum : TripMessageChangeStatusEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}