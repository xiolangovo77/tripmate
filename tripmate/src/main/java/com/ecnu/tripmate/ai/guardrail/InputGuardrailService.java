package com.ecnu.tripmate.ai.guardrail;

import dev.langchain4j.service.SystemMessage;

public interface InputGuardrailService {

    /**
     * 创建或修改旅行时检查出发地和目的地城市
     * @param userMessage 包含 departureLocation 和 destination 的 JSON
     * @return 通过返回 true，不通过返回 false
     */
    @SystemMessage(fromResource = "prompt/trip-city-check-system-prompt.txt")
    String tripCityCheck(String userMessage);

    /**
     * 用户消息回的检查
     * @param userMessage 用户消息
     * @return
     */
    @SystemMessage(fromResource = "prompt/user-message-check-system-prompt.txt")
    String userMessageCheck(String userMessage);
}
