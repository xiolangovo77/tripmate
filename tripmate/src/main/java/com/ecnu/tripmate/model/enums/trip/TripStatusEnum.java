package com.ecnu.tripmate.model.enums.trip;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

@Getter
public enum TripStatusEnum {

    PREPARING("筹备中", "planning"),
    CONFIRMED("已确认", "confirmed"),
    FINISHED("已结束", "finished"),
    CANCELLED("已取消", "cancelled");

    private final String text;

    private final String value;

    TripStatusEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     * @return 枚举值
     */
    public static TripStatusEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (TripStatusEnum anEnum : TripStatusEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}