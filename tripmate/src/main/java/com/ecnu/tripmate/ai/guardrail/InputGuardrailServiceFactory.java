package com.ecnu.tripmate.ai.guardrail;


import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class InputGuardrailServiceFactory {

    @Resource(name = "checkChatModel")
    private ChatModel chatModel;

    /**
     * 提示词检查服务
     * @return
     */
    @Bean
    public InputGuardrailService getInputGuardrailService(){
        // 无旅行上下文的通用服务，避免启动时加载固定旅行1的数据库和记忆。
        return AiServices.builder(InputGuardrailService.class)
                .chatModel(chatModel)
                .build();
    }

}
