# 接口契约与后端限制

核对依据：工作区 `后端接口文档.md`，以及当前 `tripmate/src/main/java/com/ecnu/tripmate` 下的 Controller、DTO、VO、Enum、ServiceImpl。接口前缀统一 `/api`。本次已同步修改后端和前端，以下以当前 Java 实现为准。

当前用户查询：GET `/user/get`，无参数，返回 Session 对应的 LoginUserVO，未登录返回 40100。

旅行状态更新：POST `/trip/updateStatus`，JSON `{ id, status }`，status 使用 confirmed 或 cancelled；仅发起人，返回 boolean，前端严格要求 true。

## 页面到 API 的映射

| 操作        | HTTP 与地址（省略 /api）                    | 参数与响应                                                                                                                                           |
| ----------- | ------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| 注册        | POST `/user/register`                       | JSON：userAccount、userPassword、checkPassword；返回 Long                                                                                            |
| 登录        | POST `/user/login`                          | JSON：userAccount、userPassword；返回 LoginUserVO，并写入 Session                                                                                    |
| 退出        | POST `/user/logout`                         | 无请求体；返回 boolean                                                                                                                               |
| 资料与偏好  | POST `/user/update`                         | JSON：userAccount、nickname、avatarUrl、travelPace、favoriteActivities；返回 boolean                                                                 |
| 创建旅行    | POST `/trip/add`                            | JSON：departureLocation、destination、departureDate、departurePeriod、returnDate、returnPeriod、budgetLimit；返回 tripId                             |
| 旅行列表    | POST `/trip/page`                           | JSON：pageNum、pageSize、可选 title/departureLocation/destination/departureDate/returnDate/travelDays/status/beginTime/endTime；返回数组，没有总条数 |
| 旅行详情    | GET `/trip/get`                             | Query：tripId；返回 ownerId、title、六个路线日期字段、travelDays、status、hasItinerary、tripMembers                                                  |
| 旅行修改    | POST `/trip/update`                         | JSON：id、title、六个路线日期字段；仅发起人                                                                                                          |
| 本次偏好    | POST `/tripMember/update`                   | JSON：tripId、userId、budgetLimit、travelPace、favoriteActivities；仅本人                                                                            |
| 退出旅行    | POST `/tripMember/updateStatus`             | JSON：tripId、userId、status=left；仅本人                                                                                                            |
| 创建邀请    | POST `/tripInvitation/add`                  | JSON：tripId、inviteCode；返回邀请记录 ID；旧 active 邀请被撤销                                                                                      |
| 当前邀请    | GET `/tripInvitation/getById`               | Query：tripId；返回 inviteCode、expiresTime，无 status                                                                                               |
| 验证邀请    | POST `/tripInvitation/check`                | JSON：tripId、inviteCode；返回 boolean；无需登录                                                                                                     |
| 撤销邀请    | POST `/tripInvitation/updateStatus`         | JSON：tripId、inviteCode、status=revoked                                                                                                             |
| 加入旅行    | POST `/tripMember/add`                      | JSON：tripId、inviteCode、budgetLimit；后端固定 member 角色                                                                                          |
| 历史消息    | GET `/tripMessage/page`                     | Query：tripId、pageSize=30、可选 lastCreateTime；返回数组                                                                                            |
| 普通消息    | POST `/tripMessage/add`                     | JSON：tripId、content；不回复时省略 replyToId；返回用户消息 ID                                                                                       |
| AI 回复     | POST `/tripMessage/addAi`                   | JSON：tripId、replyToId=用户消息 ID；返回字符串，并在后端保存 AI 消息                                                                                |
| AI 建议列表 | POST `/tripMessage/getAiByChangeStatus`     | JSON：tripId、changeStatus；仅发起人，返回 id/content/createTime                                                                                     |
| 忽略建议    | POST `/tripMessage/updateChangeStatusByIds` | JSON：tripId、ids、changeStatus=ignored；返回更新数量                                                                                                |
| 生成上下文  | GET `/trip/getPrompt`                       | Query：tripId；返回后端综合的旅行/成员提示词                                                                                                         |
| 生成行程    | POST `/itinerary/add`                       | JSON：tripId、initItineraryPrompt；仅发起人；返回行程 ID                                                                                             |
| 查找行程 ID | GET `/itinerary/getIdByTripId`              | Query：tripId；不存在行程时返回 null                                                                                                                 |
| 行程详情    | GET `/itinerary/get`                        | Query：id；返回 ItineraryVO 和 itineraryItemVOList，无行程自身 ID                                                                                    |
| 采纳并修改  | PUT `/itinerary/update`                     | JSON：id、messageList（AI 消息原文）；返回新行程 ID                                                                                                  |
| 确认行程    | PUT `/itinerary/confirmed`                  | **Query：id**，无 JSON 请求体；返回更新条数                                                                                                          |

## 枚举与参数

- 旅行节奏：`relaxed`、`balanced`、`packed`。
- 活动偏好是 **JSON 数组字符串**，例如 `"[\"美食\",\"自然\"]"`，不是直接发送数组；后端要求非空数组。原型的“选填”已按实际校验改为至少一项。
- 成员角色：`owner`、`member`；本人状态：`joined`、`left`。
- 旅行状态：`planning`、`confirmed`、`finished`、`cancelled`；行程状态：`draft`、`confirmed`，二者不是同一个字段。
- AI 建议状态：`none`、`pending`、`applied`、`ignored`；只有 pending 的正常 AI 消息可被处理。
- 邀请码必须满足 `[A-HJ-NP-Z2-9]{8}`；服务端设置 7 天有效期。期限与有效性均以服务器响应为准。
- 注册账号至少 4 字符，密码至少 6 字符。个人资料 userAccount 为权限校验字段，不能用修改资料接口更换账号。
- LocalDateTime 无时区后缀；游标按原值发送，不转换成浏览器本地时区。页面原样展示后端时间。

## OpenAPI 未表达的实际行为

后端本次新增 `GET /user/get`，`tripMember/add` 增加 inviteCode 并由服务端固定 member 角色；旧 OpenAPI Markdown 尚未体现这些变化。前端已按当前 Controller 和 DTO 适配。其他业务语义需要结合 Service 实现理解：

1. `tripMember/add` Controller 变量名为 tripMemberId，但 Service 实际返回 **tripId**。前端不把返回值当成员 ID；加入后按邀请中的 tripId 跳转并重新读取成员。
2. `itinerary/update` 删除旧行程及明细，再创建新草案，因此响应 Long 是**新的 ID**。不能按原 ID 再查询，也不能先调用状态更新将建议设为 applied；后端更新事务会检查建议仍为 pending。
3. `trip/page` 返回 `List<TripPageVO>`，不是 `{ records, total }`。前端按页大小决定是否可翻下一页，恰好一整页时允许下一页为空。
4. 没有旅行的用户在 `TripMemberServiceImpl.getTripIdsByUserId` 抛出 `40000 / 该用户不存在旅行信息`；另有 `40400 / 不存在旅行信息` 分支。前端仅将这两种明确响应转为空列表，不吞掉其他 40000 或权限错误。
5. 没有邀请码返回 `40400 / 该旅行没有邀请码`；没有建议返回 `40400 / ai消息列表为空`。各自按空状态展示。
6. `getIdByTripId` 没有行程返回成功且 data=null。`itinerary/get` 则会对没有明细的行程报错，前端保留该错误而非伪造时间线。
7. OpenAPI 同时列出 form 与 JSON 的接口，实际使用 `@RequestBody` 的均发送 `application/json`；GET 和 confirmed 使用 Query 参数。

## 后端需补充或修正的问题

### 会话和资料

后端已提供 `GET /user/get`，通过 Session 返回 LoginUserVO。前端启动及进入受保护页面时查询该接口，不再依赖 sessionStorage 用户快照或 trip/page 探测。刷新和同一浏览器的新标签页可以直接恢复身份。

`userUpdate` 已同步更新当前 Session 的 User。前端保存后重新读取当前用户，新建/加入旅行无需重新登录即可带入新昵称和偏好。`getLoginUser` 仍不重新查询数据库，其他独立 Session 修改资料后，本会话数据可能过时；这属于尚存的跨设备同步限制。

### 邀请与成员权限

`tripMember/add` 已接收 inviteCode，调用 checkInviteCode 检查旅行筹备状态、邀请码归属、到期时间及撤销状态；公开接口固定使用 member，内部创建旅行仍添加 owner。前端正式加入请求只提交 tripId、inviteCode、budgetLimit，不接收或发送角色。预校验继续保留，用于展示邀请是否有效；最终校验由加入接口执行。并发撤销与加入之间的数据库锁定/一致性不属于本次前端改动。

`TripInvitationServiceImpl.get` 接收 loginUser 但没有发起人/成员授权检查；当前前端仅向发起人提供管理入口。`trip/get`、`itinerary/get`、`getIdByTripId`、`trip/getPrompt` 无服务端会话/成员校验；前端路由和界面限制不能保护直接调用这些接口的数据访问，后端应补齐。

消息的成员校验只检查成员记录存在，未过滤 `status=joined`。前端读取详情后只允许当前已加入成员进入房间，但已退出成员的直接 API 请求仍需后端限制。

成员状态/偏好接口仅允许本人操作，没有发起人踢人或代填偏好的接口。退出后成员记录仍在，add 的重复检查会阻止重新加入。发起人可「取消并退出旅行」：先将 trip.status 改为 cancelled，再将本人的成员状态改为 left。普通成员退出只更新自己的成员状态。若取消失败则停止退出；若取消成功而退出失败，则保留部分成功提示和重试退出入口。

### 历史消息、AI 与状态

`TripMessageServiceImpl.page` 只在没有 lastCreateTime 时指定 createTime 降序；带游标时没有排序。时间游标使用严格 `<`，同一时间超过一页的消息还可能遗漏。前端对取得的消息排序和按 ID 去重，但无法修复服务端分页遗漏。建议后端始终按 createTime/id 降序，并使用复合游标。

addAi、行程生成和修改没有幂等键。长请求超时后结果不确定，前端不会自动重发写请求；AI 重试会先查询回复，但无法彻底消除后端仍在处理或回复已超出最新页时的重复风险。

AI 回复和重试仅传递 `tripId`、`replyToId`，不查询或传递 `itineraryId`，前端不设置必须已有行程的限制。旅行上下文及历史问答记忆由后端加载。

`itinerary/update` 直接使用客户端发送的 AI 消息 content/createTime 构造提示词，建议后端按 ID 从数据库重读；前端始终传递刚从服务端读取的消息。

`updateChangeStatusByIds` 中对 applied/ignored 的校验表达式使用同一字符串同时等于两个值的 AND，无法表达预期限制。前端只用 ignored；applied 完全交给 itinerary/update 的事务处理。

确认行程接口只更新 itinerary，不更新 trip.status。按当前产品要求，前端在 `/itinerary/add` 成功返回有效 ID 后，调用 `/trip/updateStatus` 将旅行设为 confirmed；状态更新失败时保留行程，只重试状态请求。行程本身仍为草案，最终确认沿用 `/itinerary/confirmed`。TripVO/TripPageVO 现在返回 status 字段，列表可按状态筛选核对。

创建行程/确认旅行以及取消旅行/退出成员均由两个独立 HTTP 请求完成，无法保证事务原子性；前端在当前页面保留部分成功提示和定向重试。旅行变为 confirmed 或 cancelled 后，后端邀请校验会拒绝后续加入，生成入口已提示先邀请好伙伴。

暂无消息推送、撤回、直接编辑明细、邀请历史、手动到期时间、旅行/账号删除接口；未用模拟按钮替代。

## 精度与错误处理

响应在 Axios transformResponse 中使用 lossless-json，防止原生 JSON.parse 提前损失 Long 精度。ID 字段统一转为字符串；所有 DTO 中 Long 参数传字符串；不会把雪花 ID 转成 Number。接口联调已验证 Jackson 接受这些字符串 Long。

业务 `40100` 清除登录态并处理返回地址，`40101`/`40300` 展示错误；网络、HTTP 状态、超时和响应格式异常统一处理。boolean=false、更新数量不符以及无效创建 ID 都不展示成功。

## 2026-10-17：出发地与往返日期契约

- 六个字段：`departureLocation`、`destination`、`departureDate`、`departurePeriod`、`returnDate`、`returnPeriod`。创建及编辑不再接受手填天数；`travelDays` 仍在实体与返回值中，由服务端按自然日差加一计算。
- 日期使用 `LocalDate` / `DATE`，JSON 为 `yyyy-MM-dd`；返回日期指到达出发地的日期。时段使用 `morning` / `afternoon` / `evening` / `flexible`，省略或 null 默认 flexible，非法值拒绝。
- 出发地、目的地与标题最多120字符。日期必填，返回不能早于出发；同日指定时段不能倒置。最多65535天，与数据库 SMALLINT UNSIGNED 上限一致。过往日期仍可用于历史旅行。
- `/trip/page` 新增出发地模糊匹配和出发/返回日期精确匹配，原天数、创建时间筛选仍可使用。
- `/trip/get` 新增 `hasItinerary`。已有行程时服务端拒绝直接改变城市、往返日期或时段，前端锁定这些输入；标题可单独修改。更换日期或路线需新建旅行重新规划。
- 生成行程时服务端重新读取保存的旅行和成员信息，不信任客户端传回的旧提示词；现有 `initItineraryPrompt` 请求字段暂时保留兼容。修改行程时同样补入当前路线、日期、时段和成员信息。AI 返回越界 dayNumber 时拒绝持久化。
- 行程页按 `departureDate + dayNumber - 1` 展示实际日期，所有日期运算均使用日历日，不受浏览器时区/DST影响。
- 现有数据库需先增加新列；`CREATE TABLE IF NOT EXISTS` 不会升级旧表。旧表迁移参考 `sql/alter_trip_schedule.sql`，历史真实日期需补齐后再收紧非空约束。已按新表建好的数据库无需重复 ALTER。本次没有执行数据库迁移。
