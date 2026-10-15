# TripMate 前端

基于 Vue 3、Vite、JavaScript、Vue Router、Pinia 和 Axios 的正式前端。复用 `../tripmate-demo` 的绿色视觉样式和 SVG 插画，使用真实 Spring Boot 接口，不包含模拟成员、模拟 AI 回复或本地旅行数据库。原始原型与 Java 后端保持不变。

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

| 路由                                | 功能                                                                                             |
| ----------------------------------- | ------------------------------------------------------------------------------------------------ |
| `/`                                 | 原型首页、山水插画、目的地/天数/预算输入、真实创建旅行                                           |
| `/login`、`/register`               | 注册、Session 登录、返回原访问地址、退出登录                                                     |
| `/my-trips`                         | 本人旅行分页、名称/目的地/天数/状态/创建时间筛选、昵称/头像 URL/个人偏好编辑                     |
| `/trips/:tripId`                    | 真实旅行详情、成员预算和偏好、发起人修改基本信息、本人修改本次偏好、普通成员退出、邀请管理、群聊 |
| `/invitation?tripId=…&inviteCode=…` | 明信片式邀请、匿名校验、登录注册返回、预算填写、重新校验后加入、已加入成员直接进入               |
| `/trips/:tripId/itinerary`          | AI 生成、行程标题/概述/费用、按天时间线、群聊、发起人查看/采纳/忽略建议、最终确认                |

邀请管理在房间的「邀请朋友」弹窗内。邀请码通过浏览器加密随机数生成，并提交后端创建；只有后端成功返回后才显示成功。更新邀请码会由后端撤销旧邀请码；复制失败时提供可手动选择的文本。

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

普通消息只请求 `/tripMessage/add`。只有独立的 `@AI` 提及（大小写不敏感）才在用户消息保存成功后，用返回 ID 调用 `/tripMessage/addAi`。AI 响应由后端持久化，前端重新查询消息，避免重复插入虚假的 AI 消息。

AI 失败时明确显示「用户消息已保存」，重试先查询是否已有回复，然后只重试 AI，不再次保存用户消息。后端没有幂等键，超时仍可能出现不确定结果，因此不会自动重试写操作。

群聊使用普通 HTTP。手动「刷新消息」读取最新一页，「加载更早的消息」使用 `lastCreateTime` 游标；没有假设 WebSocket 或流式接口。刷新会重新建立最新页游标，较早记录可再次加载。

行程生成先读取 `/trip/getPrompt`，再调用 `/itinerary/add`。采纳建议只提交后端返回的 `{ id, content, createTime }` 列表。`/itinerary/update` 在后端事务中标记建议已应用、替换旧行程，前端使用返回的**新行程 ID**读取草案。不会提前将建议标记已应用，也不提交自行拼装的行程明细。忽略建议使用独立的状态更新接口。AI 请求超时设置为 180 秒，操作中禁用重复提交。

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
- 成员退出后，后端的重复加入检查阻止再次通过邀请加入；退出前有明确提示。发起人未开放退出入口。
- 旅行 VO 不返回旅行状态；确认行程也不会同步旅行状态，前端不会伪造「旅行已确认」状态或人数上限。
- 暂无实时推送、消息撤回、直接编辑行程明细、手动设置邀请码到期时间等接口。
- 历史消息游标排序存在后端限制，详见契约文档。

以上边界均已记录，未为绕过限制修改 Java 后端。
