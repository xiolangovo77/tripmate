package com.ecnu.tripmate.ai;

import com.ecnu.tripmate.ai.model.ItineraryResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Time;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 离线验证资源绑定和 LangChain4j 的真实结构化解析，不启动 Spring、数据库或调用大模型。
 * 固定模型响应只验证接口契约，不代表模型遵循提示词的生成质量。
 */
class AiPromptContractTest {

    private static final String ITINERARY_JSON = """
            {
              "title": "结构契约校验",
              "summary": "费用待确认；时间采用24小时制。",
              "estimatedCostPerPerson": null,
              "itineraryItemResultList": [
                {
                  "dayNumber": 1,
                  "sortOrder": 1,
                  "startTime": "14:30:00",
                  "endTime": "15:30:00",
                  "activityName": "区域散步",
                  "activityType": "free_time",
                  "locationName": "目的地区域",
                  "address": null,
                  "description": "确认具体区域后安排。",
                  "estimatedCostPerPerson": 0
                },
                {
                  "dayNumber": 1,
                  "sortOrder": 2,
                  "startTime": "21:00:00",
                  "endTime": null,
                  "activityName": "办理入住",
                  "activityType": "accommodation",
                  "locationName": null,
                  "address": null,
                  "description": "住宿费用待确认。",
                  "estimatedCostPerPerson": null
                }
              ]
            }
            """;

    @Test
    void generatorLoadsPromptAndParses24HourTimesAndNullableCosts() throws IOException {
        var called = new AtomicBoolean();
        var service = service("itinerary-generator-system-prompt.txt", ITINERARY_JSON, called);
        assertItinerary(service.generatorItinerary("目的地：待确认区域；旅行天数：1天"));
        assertTrue(called.get());
    }

    @Test
    void updaterLoadsSeparatePromptAndParsesTheCompleteReplacement() throws IOException {
        var called = new AtomicBoolean();
        var service = service("itinerary-update-system-prompt.txt", ITINERARY_JSON, called);
        assertItinerary(service.updateItinerary("用户的建议如下：放慢节奏。\n【当前行程信息】"));
        assertTrue(called.get());
    }

    @Test
    void chatLoadsPromptAndPreservesBothSuggestionStates() throws IOException {
        for (String status : new String[]{"none", "pending"}) {
            var called = new AtomicBoolean();
            var service = service("message-response-system-prompt.txt",
                    "{\"content\":\"建议内容\\n尚未修改行程。\",\"changeStatus\":\"" + status + "\"}", called);
            var result = service.messageResponse("@AI 请减少跨区域移动");
            assertTrue(called.get());
            assertEquals(status, result.getChangeStatus());
            assertEquals("建议内容\n尚未修改行程。", result.getContent());
        }
    }

    private AiItineraryGeneratorService service(String resourceName, String response,
                                               AtomicBoolean called) throws IOException {
        String prompt;
        try (var stream = getClass().getResourceAsStream("/prompt/" + resourceName)) {
            assertNotNull(stream, "提示词必须存在于 classpath");
            prompt = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        assertTrue(prompt.contains("JSON"));
        assertTrue(prompt.contains(resourceName.startsWith("message-") ? "changeStatus" : "itineraryItemResultList"));
        assertFalse(prompt.contains("模拟数据返回"));
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                var system = request.messages().stream()
                        .filter(SystemMessage.class::isInstance)
                        .map(SystemMessage.class::cast)
                        .findFirst().orElseThrow();
                assertTrue(system.text().contains(prompt), "AI Service 应加载对应提示词的完整内容");
                called.set(true);
                return ChatResponse.builder().aiMessage(AiMessage.from(response)).build();
            }
        };
        return AiServices.builder(AiItineraryGeneratorService.class).chatModel(model).build();
    }

    private void assertItinerary(ItineraryResult result) {
        assertEquals("结构契约校验", result.getTitle());
        assertNull(result.getEstimatedCostPerPerson());
        assertEquals(2, result.getItineraryItemResultList().size());
        var first = result.getItineraryItemResultList().getFirst();
        assertEquals(Time.valueOf("14:30:00"), first.getStartTime());
        assertEquals(Time.valueOf("15:30:00"), first.getEndTime());
        assertEquals(BigDecimal.ZERO, first.getEstimatedCostPerPerson());
        var second = result.getItineraryItemResultList().get(1);
        assertEquals(Time.valueOf("21:00:00"), second.getStartTime());
        assertNull(second.getEndTime());
        assertNull(second.getAddress());
        assertNull(second.getEstimatedCostPerPerson());
    }
}
