package com.ecnu.tripmate.model.vo.tripInvitation;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 旅行邀请预览 视图对象。
 * 有效邀请码持有者可查看的邀请摘要，不包含成员身份、预算、偏好或行程。
 *
 * @author Wolfcub
 */
@Data
public class TripInvitationPreviewVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 旅行名称
     */
    private String title;

    /**
     * 出发地
     */
    private String departureLocation;

    /**
     * 目的地
     */
    private String destination;

    /**
     * 出发日期
     */
    private LocalDate departureDate;

    /**
     * 出发时段
     */
    private String departurePeriod;

    /**
     * 返回出发地的日期
     */
    private LocalDate returnDate;

    /**
     * 返回出发地的时段
     */
    private String returnPeriod;

    /**
     * 旅行天数
     */
    private Integer travelDays;

    /**
     * 旅行发起人的昵称
     */
    private String ownerNickname;

    /**
     * 当前已加入旅行的成员人数
     */
    private long memberCount;

    /**
     * 当前会话用户是否已加入旅行，匿名用户为false，不返回其他成员信息
     */
    private boolean joined;
}
