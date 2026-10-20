package com.ecnu.tripmate.event;

/** AI修改建议新增或状态变更，提交后通知客户端重新查询。 */
public record TripSuggestionsChangedEvent(long tripId) {
}
