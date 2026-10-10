package com.ecnu.tripmate.model.vo.trip;

import com.ecnu.tripmate.model.entity.TripMember;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class TripVO implements Serializable {
    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 发起人用户ID
     */
    private Long ownerId;

    /**
     * 旅行名称
     */
    private String title;

    /**
     * 目的地
     */
    private String destination;

    /**
     * 旅行天数
     */
    private Integer travelDays;

    /**
     * 旅行成员
     */
    private List<TripMemberVO> tripMembers;
}
