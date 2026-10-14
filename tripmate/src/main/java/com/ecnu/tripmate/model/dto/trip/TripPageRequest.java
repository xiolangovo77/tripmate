package com.ecnu.tripmate.model.dto.trip;

import com.ecnu.tripmate.common.PageRequest;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.core.keygen.KeyGenerators;
import dev.langchain4j.agent.tool.P;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class TripPageRequest extends PageRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

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
     * 旅行状态：筹备中、已确认、已结束、已取消
     */
    private String status;

    /**
     * 开始时间
     */
    private LocalDateTime beginTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;
}

