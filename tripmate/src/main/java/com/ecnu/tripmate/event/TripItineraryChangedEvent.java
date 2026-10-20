package com.ecnu.tripmate.event;

/**
 * 旅行行程变更事件，事务提交成功后通知客户端重新查询当前行程及明细。
 *
 * @param tripId 行程发生变化的旅行ID
 */
public record TripItineraryChangedEvent(long tripId) {
}
