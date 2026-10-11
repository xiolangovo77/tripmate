package com.ecnu.tripmate.model.enums;

import lombok.Getter;

@Getter
public enum TripInvitationStatusEnum {

    ACTIVE("有效", "active"),
    REVOKED("已撤销", "revoked");

    private final String text;
    private final String value;

    TripInvitationStatusEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     */
    public static TripInvitationStatusEnum getEnumByValue(String value) {
        if(value == null){
            return null;
        }
        for(TripInvitationStatusEnum anEnum : TripInvitationStatusEnum.values()){
            if(anEnum.getValue().equals(value)){
                return anEnum;
            }
        }
        return null;
    }
}