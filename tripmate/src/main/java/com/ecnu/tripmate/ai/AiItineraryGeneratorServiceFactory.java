package com.ecnu.tripmate.ai;


import com.ecnu.tripmate.service.TripMessageService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;

@Configuration
@Slf4j
public class AiItineraryGeneratorServiceFactory {

    @Resource
    private ChatModel chatModel;

    @Resource
    private McpToolProvider mcpToolProvider;

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    @Lazy
    private TripMessageService tripMessageService;

    /**
     * AI 服务实例缓存
     * 缓存策略：
     * - 最大缓存 1000 个实例
     * - 写入后 30 分钟过期
     * - 访问后 10 分钟过期
     */
    private final Cache<Long, AiItineraryGeneratorService> serviceCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .expireAfterAccess(Duration.ofMinutes(10))
            .removalListener((key, value, cause) -> {
                log.debug("AI 服务实例被移除，appId: {}, 原因: {}", key, cause);
            })
            .build();

    /**
     * 根据 appId 获取服务（带缓存）
     */
    public AiItineraryGeneratorService getAiItineraryGeneratorService(long tripId) {
        return serviceCache.get(tripId, this::createAiItineraryGeneratorService);
    }

    /**
     * 根据旅行id获取服务
     * @param tripId
     * @return
     */
    private AiItineraryGeneratorService createAiItineraryGeneratorService(long tripId){
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory
                .builder()
                .id(tripId)
                .chatMemoryStore(redisChatMemoryStore)
                .maxMessages(50)
                .build();
        // 加载历史记忆
        tripMessageService.loadChatMemory(tripId, chatMemory);
        return AiServices.builder(AiItineraryGeneratorService.class)
                .chatModel(chatModel)
                .toolProvider(mcpToolProvider)
                .chatMemory(chatMemory)
                .build();
    }

    /**
     * 行程生成服务
     * @return
     */
    @Bean
    public AiItineraryGeneratorService getAiItineraryGeneratorService(){
        // 无旅行上下文的通用服务，避免启动时加载固定旅行1的数据库和记忆。
        return AiServices.builder(AiItineraryGeneratorService.class)
                .chatModel(chatModel)
                .toolProvider(mcpToolProvider)
                .build();
    }

}
