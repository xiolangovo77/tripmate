package com.ecnu.tripmate.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.deepseek-tripmate-chat-model")
@Data
public class TripMateChatModelConfig {

    private String baseUrl;

    private String apiKey;

    private String modelName;

    private Integer maxTokens;

    private Boolean logRequests = false;

    private Boolean logResponses = false;

    private Boolean strict_json_schema = true;

    private String response_format;

    /**
     * 实现旅行搭子核心功能的多例Chatmodel
     */
    @Bean
    @Scope("prototype")
    public ChatModel TripMateChatModelPrototype() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .maxTokens(maxTokens)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .strictJsonSchema(strict_json_schema)
                .responseFormat(response_format)
                .build();
    }
}
