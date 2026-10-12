package com.ecnu.tripmate.ai;

import com.ecnu.tripmate.ai.model.ItineraryResult;
import dev.langchain4j.service.SystemMessage;

public interface AiItineraryGeneratorService {

     /**
      * 生成行程计划
      * @param userMessage 用户消息
      * @return
      */
     @SystemMessage(fromResource = "prompt/itinerary-generator-system-prompt.txt")
     ItineraryResult generatorItinerary(String userMessage);

     /**
      * 更新行程计划
      * @param userMessage 用户消息
      * @return
      */
     @SystemMessage(fromResource = "prompt/itinerary-update-system-prompt.txt")
     ItineraryResult updateItinerary(String userMessage);
}
