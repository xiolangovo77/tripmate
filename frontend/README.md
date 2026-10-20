# TripMate 前端

基于 Vue 3、Vite、JavaScript、Vue Router、Pinia 和 Axios 的正式前端。采用绿色旅行手记风格，保留原型 SVG 插画，使用真实 Spring Boot 接口。应用不包含模拟成员、模拟 AI 回复或本地旅行数据库；浏览器测试中的响应夹具仅用于展示与失败分支验证。

## 启动

建议使用 Node.js 22.18+ 或 24 LTS。本次使用 Node.js 24.15.0、npm 11.12.1。

```powershell
cd D:\project\JAVA\step-12\frontend
npm install
npm run dev
```

浏览器访问 **http://127.0.0.1:5173/**。后端默认运行在 **http://localhost:8123/api**。Vite 固定使用 5173 端口，端口被占用时会报错，不会悄悄改端口。

```powershell
npm run build
npm run preview
```

生产文件输出到 `dist/`。`preview` 默认地址为 http://127.0.0.1:4173/，只用于检查生产静态文件；正式部署请配置 Web 服务器代理 `/api` 到 Spring Boot，并将前端路由回退到 `index.html`。

Vue 单文件组件与 Vite 的官方说明：[Vue 快速开始](https://vuejs.org/guide/quick-start)、[Vite 使用指南](https://vite.dev/guide/)。

## API 配置

复制 `.env.example` 为 `.env.local`，按需修改并重启开发服务：

```dotenv
VITE_API_BASE_URL=/api
API_PROXY_TARGET=http://localhost:8123
```

- `VITE_API_BASE_URL` 是浏览器请求的统一前缀。`src/api` 中地址只写 `/trip/get` 这类相对路径，因此不会出现 `/api/api`。
- `API_PROXY_TARGET` 是 Vite 转发到的后端源地址，**不带 `/api`**。代理保留请求原有的 `/api` 路径。
- 生产环境优先使用同源 `/api` 反向代理。也可将 `VITE_API_BASE_URL` 设为完整的 `https://你的后端/api`，但后端必须允许对应前端 Origin 和跨域凭据，Cookie 属性也必须匹配。
- `VITE_` 变量会打包进浏览器，请勿放入大模型密钥或其他服务端秘密。

## 页面与功能

| 路由                                | 功能                                                                               |
| ----------------------------------- | ---------------------------------------------------------------------------------- |
| `/`                                 | 旅行灵感、参数预填、登录前保留创建草稿、真实创建旅行                               |
| `/login`、`/register`               | 注册、Session 登录、返回原访问地址、退出登录                                       |
| `/my-trips`                         | 本人旅行分页、名称/目的地/天数/状态/创建时间筛选、昵称/头像 URL/个人偏好编辑       |
| `/trips/:tripId`                    | 旅行工作台、成员偏好、普通成员退出、发起人取消并退出、邀请管理、群聊               |
| `/invitation?tripId=…&inviteCode=…` | 明信片式邀请、匿名校验、登录注册返回、预算填写、重新校验后加入、已加入成员直接进入 |
| `/trips/:tripId/itinerary`          | AI 生成、行程标题/概述/费用、按天时间线、群聊、发起人查看/采纳/忽略建议、最终确认  |

邀请管理在房间的「邀请朋友」弹窗内。邀请码通过浏览器加密随机数生成，并提交后端创建；只有后端成功返回后才显示成功。更新邀请码会由后端撤销旧邀请码；复制失败时提供可手动选择的文本。

首页灵感卡只预填目的地、天数和参考预算，用户确认后才创建。未登录时填写的三项内容暂存在本标签页 sessionStorage，登录返回后可继续，创建成功会清除。我的旅行以旅行卡片为主，筛选可展开/清空，个人信息通过「编辑个人偏好」打开。

房间与行程使用统一导航；刷新内容时保留聊天输入和已挂载组件。聊天支持 Ctrl / ⌘ + Enter 发送、输入框自动增高和 @AI 提示。确认操作采用支持 Escape、焦点返回和背景滚动锁定的弹窗。动效尊重系统“减少动态效果”设置。

导航中的「旅行房间」「AI 行程」没有当前旅行时会进入个人中心选旅行。所有旅行页面通过 URL 的字符串 ID 重新读取后端，可以直接打开或刷新。

## 目录与维护方式

```text
src/
  api/             Axios 实例与按业务划分的接口调用（类似 Java 的客户端 Service）
  assets/          从原型提取的 SVG 插画
  components/      成员列表、偏好表单、邀请管理、群聊、建议列表、弹窗等
  composables/     useTrip：页面共用的旅行读取、成员身份计算
  layouts/        公共导航、页脚、全局提示
  router/         页面路由、登录拦截
  stores/         Pinia：登录用户显示信息与提示状态
  styles/         原型 CSS 和正式业务补充样式
  utils/          安全 JSON 解析、ID、格式化、枚举显示名
  views/          每个路由对应的页面组件
tests/            无需后端的契约与数据处理测试
e2e/              使用真实后端的浏览器验收
scripts/          可独立运行的真实 API 验收脚本
docs/             接口限制和验收记录
```

每个 `.vue` 文件的 `<script setup>` 是页面逻辑，`<template>` 是界面。`ref`/`reactive` 管理响应式状态；表单通过 `v-model` 绑定；业务接口统一在 `api` 内调用。后端错误在当前页面展示，成功提示只在服务端明确返回成功后出现。没有引入 UI 组件库。

```powershell
npm run format
```

使用 Prettier 整理源代码。原型 CSS 和提取的 SVG 不会被格式化器改写。

## 会话与 ID

1. Axios 使用 `withCredentials: true`，由浏览器发送后端 Cookie。
2. 启动、刷新和进入受保护路由时，通过 `GET /user/get` 查询真实 Session 和当前用户。会话失效会清除内存中的用户状态并跳转登录，不再借助旅行列表接口判断身份。
3. 用户信息仅保存在 Pinia 内存中，不依赖 sessionStorage；同一浏览器的新标签页可通过 Cookie 恢复身份。旧版本用户缓存会自动清理。跨标签页登录、退出及资料更新通过 BroadcastChannel 通知其他页面重新读取。
4. 个人资料修改后，后端已同步更新 Session，前端再次调用 `/user/get` 读取确认后的信息，无需重新登录。新建/加入旅行使用更新后的默认偏好，已有旅行偏好独立保存。
5. `lossless-json` 在数值转换前保留超出安全整数范围的整数；所有 `id`、`…Id` 字段统一为字符串。请求中 Long 参数也使用字符串，Jackson 已通过真实联调验证可接收。金额、天数等保持各自业务类型，禁止将雪花 ID 转成 Number。

详见 [接口契约与后端限制](docs/API_CONTRACT.md)。

## 群聊与行程流程

旅行房间和 AI 行程页的成员列表已接入 SSE 自动同步，支持加入、退出和本次旅行偏好变化。AI 行程页通过同一连接同步行程生成、修改与确认；断线重连及前台定时补查会恢复数据。部署与接口说明见 [旅行成员自动同步](docs/MEMBER_REALTIME.md) 和 [行程自动同步](docs/ITINERARY_REALTIME.md)。

普通消息只请求 `/tripMessage/add`。只有独立的 `@AI` 提及（大小写不敏感）才在用户消息保存成功后，携带 `tripId` 和保存返回的 `replyToId` 调用 `/tripMessage/addAi`。前端无需查询或传递行程 ID，也不要求先生成行程。AI 重试先核对是否已有回复，再使用原用户消息 ID 请求，避免重复保存问题。AI 响应及聊天记忆由后端保存，前端重新查询消息，不自行拼装历史或插入虚假的 AI 消息。

AI 失败时明确显示「用户消息已保存」，重试先查询是否已有回复，然后只重试 AI，不再次保存用户消息。后端没有幂等键，超时仍可能出现不确定结果，因此不会自动重试写操作。

群聊使用普通 HTTP。手动「刷新消息」读取最新一页，不传游标；「加载更早的消息」成对传入上一页末条消息的 `lastCreateTime` 和 `lastId` 联合游标。后端按创建时间、ID 降序分页，前端按相同字段升序展示，ID 使用字符串传输、BigInt 比较以保留精度。刷新会重新建立最新页游标，较早记录可再次加载。

行程生成先读取 `/trip/getPrompt`，再调用 `/itinerary/add`。采纳建议只提交后端返回的 `{ id, content, createTime }` 列表。`/itinerary/update` 在后端事务中标记建议已应用、替换旧行程。前端校验写入返回的 ID，再通过 `/itinerary/getByTripId` 按旅行读取当前行程 ID 和完整明细；自动同步也使用该接口，避免访问已被替换的旧行程。不会提前将建议标记已应用，也不提交自行拼装的行程明细。忽略建议使用独立的状态更新接口。AI 请求超时设置为 180 秒，操作中禁用重复提交。

### 旅行状态联动

创建行程成功后，前端调用 `POST /trip/updateStatus`，提交 `{ id: tripId, status: 'confirmed' }`。若更新状态失败，保留已生成行程并提供「重试确认旅行状态」，不会重复生成。旅行状态与行程草案/确认状态是两个独立字段；生成后旅行已确认，行程仍可讨论和最终确认。

发起人可在房间点击「取消并退出旅行」：先将旅行设为 cancelled，再将本人成员状态设为 left。取消失败不会继续退出；取消成功但退出失败时显示明确提示，可仅重试退出。普通成员退出只修改自己的成员状态。以上是两个独立请求，不具备后端事务原子性，当前页提供部分成功的恢复入口。

## 测试

```powershell
# 无需启动后端
npm test
npm run build

# 需要真实后端：自动创建独立测试账号与旅行
npm run test:integration

# 同时测试真实 AI（会调用后端配置的大模型）
$env:TEST_AI = '1'
npm run test:integration
Remove-Item Env:TEST_AI

# 浏览器测试：首次先安装 Chromium
npx playwright install chromium
npm run test:e2e

# 浏览器同时验收完整真实 AI 交互
$env:TEST_AI = '1'
npm run test:e2e
Remove-Item Env:TEST_AI
```

本工作区已有测试浏览器时，配置自动使用 `node_modules/.cache/ms-playwright`；其他环境使用 Playwright 默认缓存位置。测试服务通过 `playwright.config.js` 自动启动，已有 5173 开发服务则复用。

`TEST_API_URL` 可改变 API 脚本的后端地址（默认 `http://localhost:8123/api`）。测试账号以 `fe_` 或 `ui_` 开头，密码只在测试进程内随机生成；测试会留下测试旅行，因为当前后端没有删除旅行或账号接口，不会操作已有用户数据。

实际构建、API 联调、浏览器和截图检查结果见 [验收记录](docs/VERIFICATION.md)。

## 当前后端能力边界

- 当前用户接口返回本会话的 User；其他设备修改资料后，本会话仍可能保留旧快照，后端暂未在查询时重新读取数据库。
- 加入接口现已接收并校验邀请码，且由后端固定角色为 member；前端预校验用于显示邀请状态，正式加入请求同时提交 tripId、inviteCode、budgetLimit。
- 发起人没有移除他人、修改他人偏好的接口，因此界面仅允许本人操作。
- 成员退出后，后端的重复加入检查阻止再次通过邀请加入；退出前有明确提示。发起人退出会取消整趟旅行，其他成员记录保留。
- 旅行 VO 不返回旅行状态；前端在行程创建成功后调用旅行状态接口，列表支持通过状态筛选核对。详情页不凭行程状态推断旅行状态。已确认或已取消的旅行不能继续通过邀请加入。
- 成员和行程已接入实时推送；聊天消息、AI 建议尚未自动同步，暂无消息撤回、直接编辑行程明细、手动设置邀请码到期时间等接口。
- 历史消息游标排序存在后端限制，详见契约文档。

以上边界均已记录，未为绕过限制修改 Java 后端。

### 旅行日期改造（2026-10-17）

创建/编辑旅行改为出发地、目的地、出发日期和时段、返回日期和时段；旅行天数自动计算。房间、列表、邀请页展示往返信息，行程按实际日期展示。后端与前端需要一起更新，数据库必须已包含新版 trip 字段；现有数据库升级参考项目根目录 `sql/alter_trip_schedule.sql`，不要通过重建表删除历史数据。已有行程时锁定路线与日期，允许修改标题。

离线后端验证：在 `tripmate` 执行 `mvn -o -Dtest=TripScheduleTest,AiPromptContractTest test`。前端运行 `npm test`、`npm run build` 和 `npx playwright test e2e/schedule.spec.js e2e/status-flow.spec.js`。浏览器用响应夹具验证交互和请求体，不代表真实数据库或真实 AI 联调。
