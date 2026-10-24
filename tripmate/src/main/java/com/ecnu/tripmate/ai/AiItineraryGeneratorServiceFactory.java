package com.ecnu.tripmate.ai;

import com.ecnu.tripmate.util.SpringContextUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@Slf4j
public class AiItineraryGeneratorServiceFactory {

    @Resource(name = "TripMateChatModelPrototype")
    private ChatModel chatModel;

    @Resource
    private McpToolProvider mcpToolProvider;

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
    private AiItineraryGeneratorService createAiItineraryGeneratorService(long tripId) {
        return AiServices.builder(AiItineraryGeneratorService.class)
                .chatModel(SpringContextUtil.getBean("TripMateChatModelPrototype", ChatModel.class))
                .toolProvider(mcpToolProvider)
                .build();
    }

    /**
     * 行程生成服务
     * @return
     */
    @Bean
    public AiItineraryGeneratorService getAiItineraryGeneratorService() {
        return AiServices.builder(AiItineraryGeneratorService.class)
                .chatModel(chatModel)
                .toolProvider(mcpToolProvider)
                .build();
    }

}
