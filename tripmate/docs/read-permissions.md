# 旅行数据读取权限

本次修改复用 `UserService.getLoginUser`、`TripMemberService.checkMemberAccess`、`TripService.checkTripOwner` 和 `TripInvitationService.checkInviteCode`，沿用 Session、BaseResponse 和现有业务错误码。

| 接口（均在 `/api` 下） | 访问规则 |
| --- | --- |
| GET `/trip/get`、`/trip/getPrompt` | 登录且当前已加入旅行；保护成员预算、偏好和生成上下文 |
| GET `/trip/{tripId}/members` | 沿用现有有效成员校验 |
| GET `/itinerary/get`、`/itinerary/getIdByTripId`、`/itinerary/getByTripId` | 登录且当前已加入；旧接口同样先校验权限再读取缓存 |
| POST `/tripMessage/get` | 根据消息所属旅行检查有效成员身份，再返回内容或“消息已撤回” |
| GET `/tripMessage/page`、`/tripMessage/latest`、`/tripMessage/byIds` | 统一使用有效成员校验；历史分页不能绕过退出状态 |
| POST `/tripMessage/getAiByChangeStatus` | 当前仍在旅行中的发起人 |
| GET `/tripInvitation/getById` | 仅发起人，防止外部用户取得邀请码后自行加入 |
| POST `/tripInvitation/preview` | 可匿名，但每次必须提交有效的 `tripId`、`inviteCode` |

受保护接口未登录返回 `40100`，非成员和已退出成员返回 `40101`。保持现有 HTTP/业务响应约定，前端继续通过 `body.code` 判断。普通 JSON 接口直接返回 `ResultUtils.success(...)`，不额外设置缓存响应头，也不新增拦截器。

邀请预览使用独立的 `TripInvitationPreviewVO`，只返回：`title`、`departureLocation`、`destination`、`departureDate`、`departurePeriod`、`returnDate`、`returnPeriod`、`travelDays`、`ownerNickname`、`memberCount`、`joined`。不包含成员列表、成员 ID、预算、偏好或行程。`joined` 从当前 Session 与已加入成员记录计算；匿名为 false。错误、过期、撤销的邀请码或非筹备状态均不返回摘要。

前端检查邀请和提交加入前均调用预览接口；只有加入成功或确认已是成员之后，才进入房间请求受保护数据。加入接口仍独立校验邀请码，不信任预览成功状态。房间读取失败时清除旧数据；离开路由时不再使用缺失旅行 ID 发起成员补查。

## 验证

无需数据库、Redis、COS 或真实 AI：

```powershell
# tripmate 目录
mvn -o '-Dtest=*,!TripmateApplicationTests,!AiItineraryGeneratorServiceFactoryTest' test

# frontend 目录
npm test
npm run build
npx playwright test e2e/invitation-privacy.spec.js e2e/chat-references.spec.js e2e/schedule.spec.js e2e/status-flow.spec.js
```

`PrivateTripReadTest` 使用真实控制器、Session 登录逻辑及成员校验，数据库和缓存使用替身，覆盖匿名、非成员/退出成员、有效成员、缓存命中后退出、撤回消息。`InvitationPreviewTest` 验证预览字段白名单、无效邀请码拒绝、当前会话 joined 状态和邀请码管理权限。浏览器测试通过响应夹具验证匿名预览、登录返回、加入前复验、加入后读取、已加入直达、非成员拒绝与现有页面流程，不代表真实服务联调。

前后端需同步发布。现有接口 URL 和正常成功响应保持兼容；旧前端邀请页会因请求受保护详情而报权限错误。无需数据库迁移。AI 费用限流配置沿用现有实现。
