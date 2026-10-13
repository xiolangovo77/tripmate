package com.ecnu.tripmate.model.enums.tripMessage;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

@Getter
public enum TripMessageRecallStatusEnum {

    NORMAL("正常", "normal"),
    RECALLED("已撤回", "recalled");

    private final String text;

    private final String value;

    TripMessageRecallStatusEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     * @return 枚举值
     */
    public static TripMessageRecallStatusEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (TripMessageRecallStatusEnum anEnum : TripMessageRecallStatusEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}