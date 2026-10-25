package com.ecnu.tripmate.event;

/** 更新开始或结束时立即通知客户端重新查询更新状态，不依赖事务提交。 */
public record TripItineraryUpdatingEvent(long tripId) {
}
