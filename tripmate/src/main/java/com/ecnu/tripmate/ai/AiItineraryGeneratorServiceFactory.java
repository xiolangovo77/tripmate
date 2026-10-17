package com.ecnu.tripmate.ai;


import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class AiItineraryGeneratorServiceFactory {

    @Resource
    private ChatModel chatModel;

    @Resource
    private McpToolProvider mcpToolProvider;

    /**
     * 行程生成服务
     * @return
     */
    @Bean
    public AiItineraryGeneratorService getAiItineraryGeneratorService(){
        return AiServices.builder(AiItineraryGeneratorService.class)
                .chatModel(chatModel)
                .toolProvider(mcpToolProvider)
                .build();
    }

}
