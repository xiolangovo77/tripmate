package com.ecnu.tripmate.ai.tools;

import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * 当前时间工具
 * 支持 AI 通过工具调用的方式获取当前日期和时间
 */
@Slf4j
public class CurrentTimeTool {

    private static final ZoneId TIME_ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 获取当前时间
     *
     * @return 当前日期、时间、星期和时区
     */
    @Tool("获取当前日期、时间、星期和时区。涉及今天、明天、后天、星期几等时间相关问题时调用")
    public String getCurrentTime() {
        ZonedDateTime now = ZonedDateTime.now(TIME_ZONE);

        String currentDateTime = now.format(FORMATTER);
        String dayOfWeek = now.getDayOfWeek()
                .getDisplayName(TextStyle.FULL, Locale.CHINA);

        String result = String.format(
                "当前日期时间：%s\n星期：%s\n时区：%s（UTC+08:00）",
                currentDateTime,
                dayOfWeek,
                TIME_ZONE.getId()
        );

        log.info("获取当前时间：{}", currentDateTime);

        return result;
    }
}