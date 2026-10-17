package com.ecnu.tripmate.ai;

import com.ecnu.tripmate.ai.model.ItineraryResult;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AiItineraryGeneratorServiceFactoryTest {

    @Resource
    private AiItineraryGeneratorService aiItineraryGeneratorService;

    @Test
    void getAiItineraryGeneratorService() {
        ItineraryResult answer = aiItineraryGeneratorService.generatorItinerary("我明天早上要从上海去南京，大后天晚上回来，预算5k，帮我计划一下");
        Assertions.assertNotNull(answer);
    }
}