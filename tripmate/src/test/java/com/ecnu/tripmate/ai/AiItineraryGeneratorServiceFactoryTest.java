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
        ItineraryResult answer = aiItineraryGeneratorService.generatorItinerary("你好");
        Assertions.assertNotNull(answer);
    }
}