# 旅行群聊增量同步

旅行房间和 AI 行程页的 `TripChat` 共用所在页面的旅行 SSE 连接。用户消息和 AI 回复成功保存后发布 `TripMessagesChangedEvent`，`TripMessageEventListener` 在事务提交成功后广播 `trip.messages.changed`。SSE 仅提示查询，不传输消息正文，也不作为历史消息队列。

## 查询接口

接口前缀为 `/api`，响应均为 `{code, data, message}`，`data` 是 `TripMessageVO` 数组，ID 按字符串处理。

用户消息包含 `senderNickname` 和 `senderAvatarUrl`，从用户表按当前批次的发送者 ID 批量读取，不复制到 `trip_member` 或消息表。AI 消息和已删除用户的这两个字段为空。聊天中自己的头像使用当前登录资料，其他发送者使用消息返回的头像；无头像或加载失败时显示昵称首字，AI 使用独立标识。已加载的其他用户头像会在重新查询对应消息（例如重新进入页面）后更新。

| 接口 | 参数 | 顺序和用途 |
| --- | --- | --- |
| `GET /tripMessage/page` | `tripId`、`pageSize`、可选 `lastCreateTime` + `lastId` | 原接口，按创建时间、ID降序，首次读取最近一页，之后向前加载历史 |
| `GET /tripMessage/latest` | `tripId`、`pageSize`（默认30，范围1–50）、可选 `lastCreateTime` + `lastId` | 新接口，按创建时间、ID升序，读取游标之后的新消息 |
| `GET /tripMessage/byIds` | `tripId`、`ids`（逗号分隔，1–50个ID） | 补查已加载消息的当前状态，不推进消息游标 |

`latest` 的联合游标条件为：

```sql
WHERE tripId = ?
  AND (createTime > ? OR (createTime = ? AND id > ?))
ORDER BY createTime ASC, id ASC
LIMIT ?
```

`lastCreateTime`、`lastId` 必须同时传入或同时省略；时间使用接口原始返回值，不使用页面格式化后的时间。不传游标时从最早消息开始，供初始为空的群聊接收首批消息。没有新消息时返回成功的空数组。两个新接口均校验登录、旅行存在性和 joined 成员资格，查询限定当前旅行，并沿用逻辑删除过滤。

## 前端同步

`useTripMessages` 管理消息数据与请求生命周期，`TripChat` 保留输入、发送和展示逻辑。

- 首次使用 `page` 获取最近30条消息，分别保存最新、最早两组游标。
- SSE 通知、手动刷新或发送完成后，使用 `latest` 读取新增消息，按ID合并并排序。满30条继续请求下一批，直到不足一批；仅在响应仍有效并已合并后推进游标。
- 历史分页使用最早游标，只补入缺失的记录，不清空已经加载的消息，也不移动最新游标。
- 查询期间的新通知合并为待查询标记，当前请求完成后继续读取。读取失败保留已有消息和已成功推进的游标。
- 连接恢复、页面重新可见及前台每30秒补查均从保留的游标继续。隐藏、离线、卸载或切换旅行时取消请求，过期响应不能更新当前页面。
- 采纳/忽略修改的是旧消息，创建时间游标无法反映。收到 `trip.suggestions.changed`、恢复连接、手动刷新或兜底同步时，仅对已加载且仍为 pending 的 AI 消息分批调用 `byIds`，合并状态。
- 新消息仅在用户接近聊天底部时自动滚动到底部；加载历史时保留阅读位置。

现有 `(tripId, createTime, id)` 索引可用于联合游标查询，本次无需新增数据库字段。需要同时更新前后端；代理配置与单实例广播范围沿用 [成员同步说明](MEMBER_REALTIME.md)。

创建时间游标反映消息的创建顺序，并非事务提交序号。它不用于同步历史消息的任意编辑、删除，也不保证读取到创建游标较小但晚于后续消息提交的记录；若后续要求严格覆盖这类并发提交，需要引入持久化变更序号或补偿机制。
