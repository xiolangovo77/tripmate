package com.ecnu.tripmate.event;

/** 用户消息或AI回复保存后，通知旅行群聊读取增量消息。 */
public record TripMessagesChangedEvent(long tripId) {
}
