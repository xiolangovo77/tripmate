# 旅行成员自动同步

旅行房间与 AI 行程页通过 `useTripEvents` 管理共享连接，每个可见页面建立一条带 Session Cookie 的 SSE 连接。`useTripMembers` 监听成员事件，只刷新成员列表、人数和预算等派生显示，不覆盖正在填写的偏好表单。AI 行程页的 `useTripItinerary` 在同一连接上监听行程事件，详见 [行程自动同步](ITINERARY_REALTIME.md)。[群聊消息](CHAT_REALTIME.md) 和 [AI 建议](SUGGESTION_REALTIME.md) 也复用这条连接。

## 后端接口

后端职责划分：

- `controller/TripMemberController`：成员增改查接口，保留原有HTTP路径。
- `service/TripMemberService` 及其实现：成员查询、权限校验和成员写入，发布业务事件。
- `event/TripMembersChangedEvent`：成员变化的业务事件。
- `controller/TripEventController`：登录校验与SSE订阅入口。
- `sse/TripMemberEventListener`：事务提交后将业务事件转为SSE通知。
- `sse/TripSseService`：连接管理、心跳、有界异步发送与清理；按事件名称合并变更通知。
- `sse/TripSseConnection`：包内连接对象及并发调度状态。

`TripSseService.broadcast` 用于通知客户端重新查询数据，相同名称的待发送通知会合并。后续业务可以增加事件监听器复用该入口；不能将它当作逐条消息的可靠投递队列。

- `GET /api/trip/{tripId}/events`：SSE；登录且处于 joined 状态的成员才能订阅。使用与普通 API 相同的前缀配置。
- `GET /api/trip/{tripId}/members`：成员快照；同样检查登录和成员权限，返回现有 `{code, data, message}` 结构，data 为成员数组。
- 成员对象包含 `avatarUrl`，按成员用户 ID 从 `user` 表批量读取当前头像；旅行详情的 `tripMembers` 使用相同转换逻辑。成员昵称仍使用 `trip_member.nickname`，不新增数据库字段。前端头像加载失败或未设置时显示昵称首字，后续成员快照查询会带回最新头像。
- `trip.members.changed`：要求重新查询成员快照。data 中的 tripId 是字符串，避免雪花 ID 精度损失。
- `trip.access.revoked`：成员已退出或旅行不存在，客户端关闭连接并清除当前旅行显示。

加入（包括通过邀请加入）、退出/成员状态变化、预算及偏好修改均在数据库事务成功提交后发布事件。事务回滚不推送。通知在独立、有界线程池发送，慢连接不会阻塞业务提交；同一连接上的连续变更会合并。

服务端每 20 秒发送心跳并复核成员资格，连接最长 2 分钟后重连，重新经过登录校验。完成、超时、发送失败及应用关闭都会清理连接。首次订阅、重连、页面恢复可见、网络恢复时，前端重新读取快照；前台每 30 秒补查，覆盖遗漏通知和代理暂时无法推送的情况。后台或离线时关闭连接并暂停查询。切换旅行、离开页面会取消请求并清理定时器。

## 部署

本阶段为单个 Spring Boot 实例的内存广播。多实例部署前需接入 Redis 等跨实例通知机制；仅有 Redis Session 并不能广播成员事件。当前补查机制可使遗漏推送最终恢复，但不代表多实例实时广播已经实现。

如果使用 Nginx，SSE 路径须关闭缓冲、缓存，并允许长连接。例如（按实际上游地址调整）：

```nginx
location ~ ^/api/trip/[0-9]+/events$ {
    proxy_pass http://127.0.0.1:8123;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 180s;
}
```

服务端已返回 `X-Accel-Buffering: no` 和 `Cache-Control: no-cache, no-transform`。跨域部署仍须允许带凭据的请求，保证 Cookie 能发送到后端。Vite 开发代理继续使用现有 `/api` 配置。

无需数据库迁移。更新后需要重新启动后端并部署新的前端构建。此次后端包结构调整涉及类重命名，重新编译时使用 `mvn clean compile` 清理旧class文件，避免残留控制器被重复扫描。
