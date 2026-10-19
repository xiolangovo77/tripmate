package com.ecnu.tripmate.event;

/**
 * 旅行成员变更事件，事务提交成功后用于通知客户端重新查询成员列表。
 *
 * @param tripId 成员发生变化的旅行ID
 */
public record TripMembersChangedEvent(long tripId) {
}
