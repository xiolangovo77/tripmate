# TripMate 会员额度秒杀实施与验收方案

## 一、额度的单位与使用规则

活动库存按**份**计，会员个人账户按 **AI 助手使用次数**计。每份含多少次由活动发布时确定，领取单和发放消息中的 `grantAmount` 表示该份包含的次数。例如，每份含 5 次，会员抢到一份后，Redis 库存立即减 1 份，待发放事务提交后，该会员账户增加 5 次。100 份全部到账，对应 100 条发放流水，合计发放 500 次。

建议首版按用户主动发起并成功保存结果的操作计次。同一次操作中的上下文整理、模型调用或内部重试合并计费；输入和输出 token 可单独记录，用于统计模型成本。

| 用户操作 | 建议计次规则 |
|---|---|
| 创建旅行时自动校验出发地和目的地 | 0 次。城市校验属于创建流程，模型成本由平台承担。 |
| 主动生成行程 | 行程成功保存后，发起人使用 1 次。 |
| 在群聊中 `@AI` 提问或提出修改要求 | AI 回答或修改建议成功保存后，发消息的人使用 1 次。同一条回复对全体成员可见。 |
| 查看回复、忽略建议或仅更新建议状态 | 0 次。 |
| 主动让 AI 根据一条或多条建议重新生成行程 | 建议首版在操作前提示“更新行程将使用 1 次额度”。新行程成功保存后，向本次更新的发起人计 1 次，多条建议合并更新也只计 1 次。 |

**开发与验收边界。** 本期秒杀验收到额度到账为止，账户增量按发放的次数核对。后续接入使用额度时，先校验操作权限和余额，再按业务请求号预扣 1 次。群聊可用操作类型、旅行 ID 和原消息 `replyToId` 标识一次请求，并核对原消息作者；行程生成和更新使用重试时保持不变的请求号。结果保存成功后确认扣减，明确失败后退回，客户端超时时先查询原请求状态。相同请求只产生一次扣减，重复退款只退回一次。

## 二、第一步：活动建模与详情缓存

先增加四张业务表，并给用户增加可为空的 `memberExpireAt`。现有用户默认无会员权益，测试账号可显式配置有效期。

| 表 | 关键字段及约束 |
| --- | --- |
| `quota_activity` | 活动 ID、名称、展示规则、起止时间、总份数、每份额度、发布状态、配置版本。发布后固定总份数、时间窗口和每份额度，展示文案仍可编辑。 |
| `quota_claim` | `claimNo` 唯一，`(activityId, userId)` 联合唯一，保存领取时的额度快照、状态和到账时间。 |
| `quota_account` | `userId` 唯一，记录用户额度余额，支持首次领取时建账。 |
| `quota_flow` | `amount` 为有符号次数。发放（`grant`）为正，按 `claimNo` 唯一；扣减（`debit`）为负，退回（`refund`）为正，两者共用原使用请求号，按 `(requestNo, flowType)` 唯一。退回前核对原扣减的用户和次数，流水与余额变动在同一事务内提交。 |

### 2.1 Caffeine 本地缓存

`GET /api/quota/activities/{id}` 分别读取**活动配置**和**实时库存**，再组装详情。配置按 Caffeine → Redis → MySQL 读取。每个 Spring Boot 实例各自维护下面两张 Caffeine 缓存，每张默认最多 1,024 条。

| 缓存 | 键 → 值 | 过期策略 |
| --- | --- | --- |
| `generations` | 活动 ID → 当前的 `ActivityCacheGeneration` 对象，保存本机接受的最低配置版本 | 最后一次访问后 5 分钟过期 |
| `snapshots` | 上述对象 → `ActivityConfig` 快照，含名称、规则、起止时间、总份数、每份额度、发布状态和配置版本 | 写入后 30 秒过期 |

收到更高版本的失效通知时，实例为该活动换一个新的 `ActivityCacheGeneration` 对象。即使旧查询随后完成，它填入的快照也只属于旧对象。读取线程返回前会再检查一次，发现对象已更换就按新版本重读。

### 2.2 Redis 共享数据

以活动 ID 为 42 为例，下表中的键都以 `tripmate:quota:activity:{42}:` 开头。同一活动的键共享 `{42}`，便于 Lua 在同一个 Redis Cluster 槽内操作相关键。

| 键后缀 | Redis 类型及内容 | 用途与有效期 |
| --- | --- | --- |
| `config` | String，序列化后的 `ActivityConfig` JSON | 各实例共享的配置快照，写入后 300～360 秒过期 |
| `config-version` | String，补齐到 19 位的配置版本号 | Lua 回填快照前原子比较版本，拒绝旧版本覆盖新版本；当前实现未设 TTL |
| `stock` | String，当前剩余份数 | 每次查询已发布活动详情都直接读取；发布时初始化，当前实现未设 TTL |
| `reservations` | Hash，预留 `userId → claimNo` 映射 | 库存恢复时用于核对全部预扣，包含已投递但可能尚未入账的份额；当前实现未设 TTL |
| `pending` | Hash，预留 `userId → claimNo` 映射 | 库存恢复时用于核对待投递预扣；当前实现未设 TTL。待投递消息正文保存在 outbox Stream |

首次发布时，Lua 同时初始化 `stock` 和两个 Hash，并在两个 Hash 中写入 `_total` 保存初始总份数；事务提交后预热配置。重复发布和应用重启都不会重置库存。`stock = 0` 表示售罄；键缺失时详情返回 `remainingStock = null`、`stockAvailable = false`，暂停领取。恢复时先核对 MySQL 领取单和两个 Hash，再用总份数减去已预扣份数。

### 2.3 同一活动的并发读取与配置更新

**以同一实例的 10 个线程同时查询活动 42 为例。** 假设期间没有配置更新，10 个请求均成功，且 Redis 配置已预热，则按当前代码可推得以下读取次数。

| 本机配置状态 | Redis 配置读取 | Redis 库存读取 |
| --- | ---: | ---: |
| Caffeine 快照命中 | 0 次 | 10 次 |
| Caffeine 快照刚过期 | 1 次 | 10 次 |
| 关闭 L1（`quota.cache.local-enabled=false`） | 10 次 | 10 次 |

快照刚过期时，10 个线程对同一个键调用 `snapshots.get(generation, loader)`，其中一个线程执行加载，其余线程复用结果。若 Redis 配置也过期，该加载线程继续查 MySQL，依次回填 Redis 和 Caffeine。不同实例各自合并本机的加载。

配置更新时，MySQL 在同一事务内修改展示信息并递增版本。提交后，更新实例用 Lua 更新 `config-version` 并删除旧 `config`，使本机快照失效，再向 Pub/Sub 频道 `tripmate:quota:activity:invalidate:v1` 发送 `活动ID:版本号`。其他实例据此切换本地缓存代。同步失败的事件在本机每 5 秒重试；实例漏收广播时，旧快照按写入后 30 秒的 TTL 过期。

**双实例验收。** 分别预热 A、B，在 A 修改活动文案并提交，验证 B 在原快照过期前已读到新版；再回滚一次更新，验证两个实例仍返回上次提交的版本。

## 三、第二步：Lua 预扣与可靠投递

本步验收到同一领取单号进入 RabbitMQ 持久化队列。领取接口立即返回 `PROCESSING`，第三步消费者负责实际到账。

| 接口 | 使用场景 |
| --- | --- |
| `POST /api/quota/activities/{id}/claims` | 活动页领取；身份取自登录态，返回 `activityId`、`claimNo`、`status`。 |
| `GET /api/quota/activities/{id}/claims/me` | 活动页按活动 ID 查本人领取结果。每人每期一份，活动 ID 与登录用户 ID 即可定位唯一领取。 |

1. 发布时准备本期规则、库存和 Stream 消费组。领取时服务端读取会员有效期。Lua 先查本人既有标记；首次领取再校验会员、活动窗口和库存。
2. Lua 原子写入 Stream、扣一份库存、登记用户领取标记及待投递索引。消息带有 `claimNo`、`activityId`、`userId`、`grantAmount`。
3. 任务读取新消息或接管未确认消息，通过默认交换机按队列名向持久化队列发送持久化消息。仅在 publisher confirm 为 ACK 且 mandatory 消息未退回后，原子清理 Stream 记录与待投递索引。超时或重启沿用原领取单号重投。
4. 查询接口按活动 ID 和登录用户 ID 读取 MySQL 领取记录。尚未入库时读取该用户的 Redis 预扣标记。Broker 确认投递后仍显示“处理中”。

**断点验收。** 以 `quota.dispatch.enabled=false` 启动，领取后检查库存减一、Stream 留下一条记录、查询返回原单号。恢复投递后验证该单号进入持久化队列，Stream 待办清空，领取标记保留。管理页面使用 15672 端口，应用使用 AMQP 端口。本地凭据放在忽略提交的开发配置或环境变量中。

在 `tripmate` 模块目录执行以下命令可复现。用例连接开发环境的 MySQL、Redis 和 RabbitMQ，使用独立的临时活动、用户和队列，并在结束时清理。

```powershell
mvn "-Dtest=QuotaClaimDeliveryIT" "-Dquota.dev.it=true" test
```

用例用 20 个工作线程发起同一会员的 100 次领取请求。库存为 1 份时，断言所有请求返回同一单号，剩余库存为 0，Stream 中只有 1 条消息。随后移除临时队列制造路由失败，核对待确认记录仍在；重建队列并等待 30 秒接管期后，新投递实例使用原单号补发。最后核对 RabbitMQ 消息的持久化属性、活动、用户、额度及单号，接口仍返回 `PROCESSING`。

### 3.1 Redis Stream与RabbitMQ的对比

![Redis Stream 消息队列架构图](D:\Google\Downloads\Redis Stream 消息队列架构图.png)

| 对比维度     | Redis Stream                                          | RabbitMQ                                                     |
| ------------ | ----------------------------------------------------- | ------------------------------------------------------------ |
| 本质         | Redis 内置的消息日志数据结构                          | 专业的消息中间件                                             |
| 消息生产     | 生产者通过 XADD 写入 Stream                           | 生产者将消息发送到 Exchange                                  |
| 消息路由     | 无独立的路由器，生产者直接指定 Stream                 | Exchange 根据路由规则将消息投递到 Queue                      |
| 消息存储     | Stream 保存消息，按消息 ID 排序                       | Queue 存储待消费的消息                                       |
| 消费者组     | 显式支持 Consumer Group                               | 无完全对应的机制，多个 Consumer 可竞争消费同一 Queue         |
| 多消费者消费 | 同一 Group 内多个 Consumer 分摊消息                   | 同一 Queue 的多个 Consumer 竞争消费消息                      |
| 多组独立消费 | 一个 Stream 可以创建多个 Consumer Group，每组独立消费 | 一个 Exchange 可以将消息路由到多个 Queue，每个 Queue 独立消费 |
| 消息确认     | XACK 确认，从对应 Group 的 PEL 移除                   | basicAck 确认，消息通常从 Queue 中移除                       |
| 未确认消息   | 使用 PEL 记录已投递但未确认的消息                     | Broker 维护 Unacked 消息状态                                 |
| 消费者宕机   | 通过 XAUTOCLAIM 等命令转移未确认消息                  | 消费者连接或 Channel 关闭后，未确认消息通常重新入队          |
| 消息重试     | 需要应用程序结合 PEL、XAUTOCLAIM 等机制实现           | 支持重新入队、拒绝确认、死信队列等机制                       |
| 消息持久化   | 依赖 Redis 的 AOF、RDB 等持久化机制                   | 支持持久化队列及持久化消息                                   |
| 消息删除     | XACK 不删除原始消息，需要单独清理                     | 消息确认后通常从 Queue 中删除                                |
| 消费语义     | 通常按至少一次消费设计，需要业务幂等                  | 通常按至少一次消费设计，需要业务幂等                         |
| 适用场景     | 轻量异步任务、事件日志、简单消息队列                  | 复杂消息路由、业务解耦、可靠异步任务                         |

### 3.2 RabbitMQ 生产者可靠性配置

~~~yaml
rabbitmq:
  publisher-confirm-type: correlated
  publisher-returns: true
  template:
    mandatory: true
~~~

| 配置                                 | 作用                                              |
| ------------------------------------ | ------------------------------------------------- |
| `publisher-confirm-type: correlated` | 开启 Confirm 机制，通过 ACK/NACK 确认消息发布结果 |
| `publisher-returns: true`            | 开启 Spring 接收消息退回通知的机制                |
| `mandatory: true`                    | 要求 RabbitMQ 退回无法路由到 Queue 的消息         |

**核心区别：**
- **Confirm：** 确认消息是否成功发布，不保证成功路由到 Queue。
- **Mandatory：** 要求 RabbitMQ 退回无法路由的消息。
- **Returns：** Spring 接收退回消息，并通过回调处理。

**性能：** 正常情况下 Returns 和 Mandatory 的额外开销很小，只有消息无法路由时才会触发退回处理。

### 3.3 RabbitMQ 队列持久化与消息持久化

**TripMate 额度秒杀已同时实现队列持久化和消息持久化。**

| 类型       | 实现方式                                          | 作用                                                      |
| ---------- | ------------------------------------------------- | --------------------------------------------------------- |
| 队列持久化 | `new Queue(queueName, true)`                      | `durable = true`，RabbitMQ 重启后队列仍然存在             |
| 消息持久化 | `setDeliveryMode(MessageDeliveryMode.PERSISTENT)` | `delivery_mode = 2`，消息以持久化模式发送，支持重启后恢复 |
| 发布确认   | `publisher-confirm-type: correlated`              | 通过 ACK/NACK 确认消息发布结果                            |

**核心区别：**

- **队列持久化：** 保证 RabbitMQ 重启后队列仍然存在。
- **消息持久化：** 保证消息具备重启后恢复的能力。
- **Publisher Confirm：** 确认 Broker 已处理消息发布，降低消息丢失风险。

**总结：** TripMate 已实现队列持久化 + 消息持久化 + Publisher Confirm，配合消费者 ACK、业务幂等和失败补偿，提高额度消息处理的可靠性。

### 3.4 第二步新增的 Redis 结构

本步新增 `rules` 和 `outbox` 两个活动级 key，沿用 2.2 节的 `stock`、`reservations` 和 `pending`。以活动 1001 为例，五个 key 共用前缀 `tripmate:quota:activity:{1001}:`。其中 `{1001}` 是 Redis Cluster 的 hash tag，使同一活动的数据落在同一槽中，供 Lua 原子操作。

| 新增 key 后缀 | 类型 | 字段与用途 |
| --- | --- | --- |
| `rules` | Hash | 发布时写入 `start`、`end`（Unix 时间戳，单位为秒）和 `grantAmount`（每份发放的 AI 使用次数）。Lua 用 Redis 当前时间判断 `start ≤ 当前时间 < end`，预扣时将每份额度复制到消息中。 |
| `outbox` | Stream | 发布时创建消费组 `quota-dispatch`。每笔成功预扣通过 `XADD` 写入一条消息，字段为 `claimNo`、`activityId`、`userId`、`grantAmount`。投递任务用 Stream 条目 ID 确认消息，重发时沿用消息中的领取单号 `claimNo`。 |

1. **预扣成功**时，同一次 Lua 执行写入 `outbox`、将 `stock` 减一，并在 `reservations` 和 `pending` 中分别写入 `userId → claimNo`。前者用于重复领取和本人查询，后者用于核对待投递预扣。两个 Hash 的保留字段 `_total` 均保存活动总份数。
2. **投递任务读取消息**后，Redis 将条目登记在消费组的待确认列表（PEL）中。若投递实例退出，其他实例可在条目空闲满 30 秒后接管，按原单号重新投递。
3. **RabbitMQ 确认且消息成功路由**后，Lua 执行 `XACK`、`XDEL` 和 `HDEL`，清除该条目的待确认状态、消息正文和 `pending` 中对应用户的索引。`reservations` 保留领取单号，供活动页继续查询。当前实现对这五个 key 均未设置 TTL。

### 3.5 Redis Stream → RabbitMQ 投递性能优化

**当前问题：** `QuotaStreamDispatcher` 采用逐条发送、同步等待 Publisher Confirm 的方式：

~~~java
rabbitTemplate.send("", queue.getName(), message, correlation);
var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
~~~

- 每条消息最多阻塞 5 秒，虽然正常 ACK 通常更快，但串行等待会限制吞吐量。
- 假设每条消息确认耗时 100ms，100 条消息仅等待确认就需要约 10 秒。
- RabbitMQ 响应缓慢时，Redis Stream 消息可能大量积压，影响额度到账速度。

**优化方案：异步 Confirm + 批量投递 + 在途消息限流。**

1. **异步发送：** 连续发送多条消息，通过 `CorrelationData.getFuture().whenComplete()` 异步处理 Confirm，避免逐条阻塞。
2. **在途限流：** 使用 `Semaphore` 控制同时等待 Confirm 的消息数量，例如最多 100 条，避免无限积压。
3. **可靠确认：** 只有 Confirm ACK 且没有 Return 时，才执行 Redis Lua，原子完成 `XACK + XDEL + HDEL pending`。
4. **失败补偿：** Confirm NACK、Return 或超时均不确认 Stream 消息，继续保留 PEL 待办，由 `XPENDING + XCLAIM` 重新投递。
5. **幂等保障：** 重投继续使用原 `claimNo`，MySQL 通过唯一约束和事务保证额度只入账一次。

**验收方式：** 分别使用同步 Confirm 和异步 Confirm 投递 100、1,000 条消息，比较投递耗时、吞吐量、PEL 积压数量及失败恢复情况，确保无漏发、无重复入账。

**总结：** 保留 Publisher Confirm 和 Redis Stream 补偿机制，将逐条同步等待改为受限并发的异步确认，提高 RabbitMQ 投递吞吐量。

## 四、第三步：入账事务与消费恢复

将 `QuotaGrantConsumer` 和 `QuotaGrantService` 拆开。消费者负责收消息和手动 ACK，独立的事务服务负责数据库写入，让代码中有一个清晰、可注入故障的“**数据库已提交、RabbitMQ 尚未确认**”位置。

| 执行位置 | 处理方式 |
| --- | --- |
| MySQL 事务内 | 建立或锁定领取单，核对活动、用户及额度快照。首次发放在同一事务内写唯一流水、原子增加账户余额、将领取单更新为已到账。任一步失败，整笔回滚。 |
| 遇到重复消息 | 根据 `claimNo` 核对已到账记录和消息内容，匹配后直接结束。若 `(activityId, userId)` 已被另一单号占用，记录冲突供核对，避免把不同单号误判为成功。 |
| 事务提交后 | 事务服务正常返回后，消费者才 ACK。提交前退出会触发重新处理；提交后、ACK 前退出会触发重新投递，由已到账领取单识别。 |
| 持续消费失败 | 有限次数退避重试，耗尽后进入死信队列，保留原单号以便重放。消息超时或进入死信时保持库存预留，核对数据库入账结果后再决定重发或补偿，避免同一份库存既已到账又被释放。 |

先用一条消息验证这个断点。消费线程在事务方法返回后、ACK 前阻塞，从外部停止消费者进程；重启后检查**原单号收到两次、只产生一条流水、余额只增加一次**。单条通过后再扩大到 100 条。

## 五、第四步：按三条简历目标验收

| 验收项 | 实验设计 | 通过口径 |
| --- | --- | --- |
| **活动详情 100 并发热读** | 同一版本、同一接口、同一活动、相同实例数和连接池。A 关闭 L1，B 开启 L1；两组都实时读取 Redis 库存。用 JMeter 预热后以 100 个并发用户持续请求，交替运行至少 3 轮。 | 同时报告成功 QPS、P95、P99、业务错误率、L1 命中率及每请求 Redis 命令数。目标是 P95 **24 → 9 ms**、吞吐 **4,600 → 7,600 QPS**，两组均填实测值。 |
| **1,000 人抢 100 份** | 准备 1,000 个不同的有效会员，提前登录并各自保留会话，同步发起领取。再新建一期活动，用同一个会员并发重试 100 次。 | 第一组最终库存为 0，恰有 100 名不同用户到账、100 条发放流水，账户总增量等于 `100 × 每份额度`。第二组返回同一有效单号，库存只减 1，流水只有 1 条。 |
| **100 笔重投恢复** | 测试模式下让消费者逐条提交、暂缓 ACK，将 prefetch 配到足以容纳 100 条未确认消息。确认 100 笔均已提交且 broker 显示 100 条 unacked 后，强停进程，关闭故障开关再重启。 | 从恢复消费者开始订阅计时，到这 100 笔全部 ACK，目标 **≤3 秒**。同时记录进程启动耗时。核对 ready、unacked 和死信数均为 0，再按 100 个原单号逐笔比对领取单、唯一流水和用户余额增量，漏发、重复入账均为 0。 |

缓存压测尤其值得保留一项解释数据：热读稳定后，启用 L1 应主要省去**活动配置读取**，每个详情请求仍有一次库存读取。若 Redis 配置读取量明显下降，而接口 P95 没达到 9 ms，这仍是有效结果；结合连接池等待、库存读取耗时和压测端负载，可以判断下一步优化哪里。最终保留 JMeter 脚本、原始结果、故障注入开关和逐单对账 SQL，三条简历描述就都能复现。

### 5.1 活动详情热读实测（2026-11-21）

同一已发布活动、100 并发热读，每种模式各测 3 轮。取各组 3 轮指标的中位数，开启 Caffeine 后，**P95 从 28.23 ms 降至 23.77 ms（下降 15.8%），成功吞吐从 5,148 提升至 5,747 QPS（提升 11.6%）**。共完成 970,709 个正式测量请求，HTTP、业务状态和返回值校验均为 0 错误。

| 执行顺序 | 配置读取模式 | 成功请求数 | 成功 QPS | P95（ms） | P99（ms） |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | 仅 Redis | 155,297 | 5,175.5 | 25.645 | 35.020 |
| 2 | Caffeine + Redis | 172,419 | 5,746.7 | 23.771 | 33.912 |
| 3 | Caffeine + Redis | 161,757 | 5,390.3 | 25.921 | 36.245 |
| 4 | 仅 Redis | 153,579 | 5,118.7 | 30.962 | 48.907 |
| 5 | 仅 Redis | 154,484 | 5,148.0 | 28.233 | 39.153 |
| 6 | Caffeine + Redis | 173,173 | 5,771.5 | 22.313 | 34.787 |

**测量口径。** 两个独立的 Spring Boot JVM 加载相同的活动模块，各自监听本机 HTTP 端口，只有 L1 开关不同。每轮只请求其中一个实例，两组连接同一开发环境的 MySQL 8.0.45 和 Redis，均实时读取库存。压测机为 i5-11400H、12 个逻辑处理器、约 15.7 GiB 内存，使用 Java 21.0.10，两个 JVM 各配置 256～512 MiB 堆。Node.js 24.15.0 压测进程与服务同机，使用 100 个闭环请求循环和 HTTP 长连接。每轮先预热 10 秒，再测量 30 秒，从请求发起计时到完整响应接收并校验通过。成功 QPS 用成功数除以实际耗时，含最后一批在途请求完成的时间；单轮 P95、P99 按成功请求延迟排序取对应分位数。

**独立核对 Redis 读取次数。** 在计时压测之外，用 Lettuce 监听测试客户端实际发出的 GET，只计临时活动的配置键和库存键。对同一实例同时发起 10 次详情读取，关闭 L1 时记录到 10 次配置 GET、10 次库存 GET；开启 L1 且本地快照为空时为 1 次、10 次；紧接着再读 10 次为 0 次、10 次。此采样验证了加载合并和库存直读，未对上表 6 轮的每个请求采集 L1 命中率。

**双实例和库存验证。** 在两个独立 JVM 都启用 L1 时，先让 B 缓存版本 2，再通过 A 更新。从发起更新到 B 经 HTTP 读到版本 3 用时 533 ms，包含事务、通知和客户端观察时间。另一次更新回滚后，数据库与 B 仍返回版本 3。同一草稿由两个并发事务发布后版本只增加一次；将库存改为 98 后再次发布，库存仍为 98。库存键缺失时，100 份总量扣除 3 笔不同阶段的预扣后恢复为 97 份；制造领取单号不一致后，恢复被拒绝且库存键保持缺失。两次采样创建的活动行、临时领取单和专属 Redis 键均已清理。

复现时，在仓库根目录运行以下命令。脚本会读取开发配置并写入临时测试数据，结果默认保存在 Maven 的 `target/quota-benchmark` 目录。

```powershell
mvn -o "-Dtest=QuotaActivity*Test" -f tripmate/pom.xml test
node tripmate/scripts/quota-hot-read.mjs
node tripmate/scripts/quota-hot-read.mjs tripmate/target/quota-benchmark --commands-only
node tripmate/scripts/quota-hot-read.mjs tripmate/target/quota-benchmark --cache-metrics
```

逐轮结果保留了实际耗时、成功数、错误数和 0.1 ms 粒度的延迟直方图。[热读测量数据](活动详情压测/quota-hot-read.json) · [Redis GET 采样](活动详情压测/quota-get-counts.json)

### 5.2 Redis GET 减少比例与 Caffeine 命中率

在同一临时活动上独立采集缓存指标。两组各用 100 并发请求真实详情接口，预热 10 秒后采样 65 秒，覆盖配置快照的两个 30 秒过期周期。Lettuce 监听测试节点对该活动配置键和库存键发起的 GET，Caffeine 取 `snapshots.stats()` 在窗口首尾的命中数与未命中数之差。

| 配置读取模式 | 成功请求 | 配置 GET | 库存 GET | 总 GET / 成功请求 | L1 命中 / 未命中 |
| --- | ---: | ---: | ---: | ---: | --- |
| 仅 Redis | 390,688 | 390,688 | 390,688 | 2.000000 | 未启用 |
| Caffeine + Redis | 290,874 | 2 | 290,874 | 1.000007 | 290,872 / 2 |

- **每请求 Redis GET 减少 49.9997%**。以两组各自的成功请求数归一化，按 `1 − (290,876 / 290,874) / (781,376 / 390,688)` 计算。每个详情请求仍有一次库存 GET，因此本场景可节省的总 GET 接近一半。
- **Caffeine 活动配置命中率为 99.9993%**，即 `290,872 / (290,872 + 2)`。单独统计配置键，每请求配置 GET 从 1 次降至 `2 / 290,874` 次，减少 99.9993%。

两组业务错误均为 0，临时活动数据已清理。此次补测用于统计命令数和命中率；采样期间启用了命令监听，且第二组与本机 Maven 测试有重叠，性能对比沿用 5.1 节的六轮独立测量。[100 并发缓存计数原始数据](活动详情压测/quota-cache-metrics.json)

## 六、完整秒杀流程（Redis Lua + Stream + RabbitMQ + MySQL）

本节是第二、三步的落地执行说明。**活动详情走 Caffeine → Redis → MySQL；领取请求不依赖 Caffeine 中的库存或活动展示配置。** 本期目标是将抢到的份额可靠转换为用户 AI 助手使用次数，不在秒杀链路内扣减用户的使用次数。

### 6.1 一张图看完整链路

```text
管理员发布活动
  ├─ MySQL 提交活动发布状态、时间窗口、总份数、每份额度
  └─ Redis 首次初始化秒杀规则与库存（幂等，禁止重复覆盖已扣库存）
                         │
用户点击“领取”            ▼
POST /api/quota/activities/{id}/claims
  └─ 登录用户 ID + 会员有效期校验 + 生成候选 claimNo
                         │
                         ▼
              Redis Lua 原子执行
              ├─ 已领取？返回旧 claimNo
              ├─ 校验活动状态、时间和剩余份数
              └─ 首次领取：库存 -1 + 用户领取标记
                            + 预扣状态 + XADD 待投递 Stream
                         │
                         ├─ 失败：返回已结束/已售罄/未开抢等
                         └─ 成功：立即返回 claimNo + PROCESSING
                                      │
                              后台 Stream 投递任务
                              XREADGROUP / XPENDING / XCLAIM
                                      │
                                      ▼
                          RabbitMQ 持久化消息
                          publisher confirm + 路由确认
                                      │
                         成功后 Redis XACK（按规则清理）
                                      │
                                      ▼
                            QuotaGrantConsumer
                                      │
                                      ▼
                     QuotaGrantService：MySQL 单事务
                     ├─ 校验/创建 quota_claim，检查幂等
                     ├─ 插入 quota_flow（grant 唯一）
                     ├─ 增加 quota_account.balance
                     └─ quota_claim 更新为到账
                                      │
                         事务提交成功 → RabbitMQ ACK
                                      │
                                      ▼
                 GET /api/quota/activities/{id}/claims/me
                 返回本人领取最终状态：GRANTED / PROCESSING
```

### 6.2 Redis 数据结构与生命周期

以活动 1001 为例，以下键共享前缀 `tripmate:quota:activity:{1001}:`。

| 键后缀 | 类型 | 数据和用途 |
| --- | --- | --- |
| `rules` | Hash | `start`、`end` 为 Unix 秒，`grantAmount` 为每份次数。发布状态取自已提交活动配置。 |
| `stock` | String | 当前剩余份数。 |
| `reservations` | Hash | `userId → claimNo`；用于去重、查询与库存核对，投递确认后继续保留。 |
| `pending` | Hash | 待投递预扣的 `userId → claimNo` 对账索引。两个 Hash 的 `_total` 字段保存活动初始份数。 |
| `outbox` | Stream | 保存消息正文及专属消费组 `quota-dispatch`。 |

上述键独立于配置缓存 TTL，进程重启后仍可读取 Redis 中的待办。

### 6.3 第一步：接口校验并调用 Lua 原子预扣

1. 接口从会话取得用户 ID，读取 MySQL 会员到期时间，生成候选单号。
2. Lua 先查 `reservations`；活动售罄后再次领取也可取回原单号。首次领取在 Redis 时间落入活动窗口且库存充足时执行 `XADD`、`DECR` 和两次 `HSET`。脚本先检查数据类型和库存值，以免执行中途出错留下部分写入。
3. 发布时一次初始化库存、领取索引、规则和 Stream。运行中规则或 Stream 缺失时暂停新领取，核对数据后再恢复。

### 6.4 第二步：Stream 消费组与重投

发布时执行 `XGROUP CREATE … quota-dispatch 0 MKSTREAM`。节点以独立消费者名执行 `XREADGROUP … >`，每批最多读取 16 条。已分配但未确认的消息保留在 PEL。节点用 `XPENDING` 分页定位记录，对空闲至少 30 秒的记录执行 `XCLAIM` 并重发。

RabbitMQ 确认后通过 Lua 执行 `XACK`、`XDEL` 和 `HDEL pending`。若 Broker 收到消息、Redis 确认前进程退出，同一单号可能多次进入队列；第三步消费者负责入账幂等。

### 6.5 第三步：RabbitMQ 发送、消费与数据库入账

1. 投递服务向 **durable exchange / durable queue** 发送带持久化属性的消息，开启 publisher confirm 和 mandatory-return（或等效的可靠路由校验）。确认失败或状态不明时**不 XACK**，留待按相同单号重投。
2. `QuotaGrantConsumer` 以手动 ACK 模式收到消息，调用独立的、由 Spring 事务代理管理的 `QuotaGrantService`；**消费者本身不把数据库提交和 RabbitMQ ACK 混为一个事务**。
3. `QuotaGrantService` 在一个 MySQL 事务中：创建或锁定 `quota_claim`，检查 `claimNo` 与 `(activityId, userId)` 是否冲突并核对 `grantAmount`；若已正确到账则幂等返回；否则写入唯一 `grant` 流水、创建/锁定额度账户并**原子增加余额**、更新领取单为到账，最后一起提交。
4. **MySQL 事务提交成功后**，消费者才执行 RabbitMQ ACK。事务提交前失败不确认，交由有限次数退避重试；超过限制进入死信队列（DLQ），必须留住原单号和库存预留，核对后再重放/补偿。
5. MySQL 已提交而 RabbitMQ ACK 前进程退出时，broker 会再次投递该消息；按 `claimNo` 查到已经到账且内容一致后，直接 ACK，不重复插流水或加余额。

推荐业务消息体（示例）：

```json
{
  "claimNo": "Q001",
  "activityId": 1001,
  "userId": 2001,
  "grantAmount": 5
}
```

数据库唯一约束是第二道防线：`quota_claim.claimNo` 唯一、`quota_claim(activityId, userId)` 唯一、`quota_flow` 发放类型按 `claimNo` 唯一、`quota_account.userId` 唯一。发生“同用户不同单号”的唯一键冲突时，应标记异常并核对，不能直接当作成功领取。

### 6.6 第四步：前端查询领取结果

活动页轮询或刷新时调用 `GET /api/quota/activities/{id}/claims/me`，用户 ID 取自登录态。本人在该活动已到账时返回 `GRANTED`，有预扣标记或待到账领取单时返回 `PROCESSING`，没有领取记录时返回“领取单不存在”。

### 6.7 关键异常与恢复规则

| 故障注入点 | 可能表现 | 恢复原则 |
| --- | --- | --- |
| 1,000 人同时抢 100 份 | 大量请求同一库存 key | Lua 串行原子判定，最多 100 次有效扣减；库存不允许小于 0 |
| 同一用户同时点击 100 次 | 同时提交多个候选 `claimNo` | Redis 用户标记只保留首次成功单号；后续返回同一个单号 |
| Lua 已预扣，尚未发送 RabbitMQ 就崩溃 | Redis 库存已减、MySQL 尚未入账 | Stream 消息仍在，恢复投递任务后读取或接管待办；不能释放该份库存 |
| RabbitMQ 已收到消息，但投递任务没来得及 `XACK` | Stream 消息再次发送 | 同一 `claimNo` 允许重复消息，MySQL 保证仅入账一次 |
| MySQL 已提交，RabbitMQ 还未 ACK | RabbitMQ 重新投递 | 查询已到账领取单并核对内容，直接 ACK；流水和余额不再变动 |
| 消费持续失败，进入 DLQ | 份额长时间预留 | 保留领取单号，核对是否已到账，再决定重放或补偿；不能直接加回库存 |
| Redis 库存或 Stream 数据丢失 | 无法确认库存与在途预扣 | **暂停领取**，结合 MySQL 领取记录、RabbitMQ 状态、备份/持久化记录对账后恢复；禁止直接把库存设为总份数 |

### 6.8 开发顺序与验收断点

1. **先做 Lua 预扣**：使用 1,000 个有效会员抢 100 份，验证库存、用户标记、预扣记录与 Stream 消息一致；同一会员并发重试 100 次只产生一个单号。
2. **再做 Redis Stream → RabbitMQ 投递**：停掉投递任务，成功领取一份后观察状态为 `PROCESSING`，恢复任务后确认原单号进入持久化队列，状态仍为处理中；另测发送成功但未 `XACK` 的重复投递。
3. **再做 MySQL 事务和手动 ACK**：在数据库事务返回后、RabbitMQ ACK 前强停消费者，重启后验证原单号被重投，但只存在一条发放流水，余额只增加一次。
4. **最后做 100 笔恢复与详情缓存 A/B 压测**：保留 JMeter 原始数据、Stream PEL/积压、RabbitMQ ready/unacked/DLQ、MySQL 对账 SQL 及故障开关。100 笔 ACK 恢复目标 ≤3 秒是**待测目标**，并非当前已验证的性能结论。

**最终职责归纳：** Caffeine + Redis 配置缓存负责活动详情热读；Redis Lua 负责原子抢份额；Redis Stream 负责记录可恢复的待投递消息；RabbitMQ 负责异步消费与重投；MySQL 事务、唯一约束和流水负责额度**只到账一次**。

### 6.9 预扣的意义

**Redis 保留用户领取标记 + Stream + 轻量预扣索引，MySQL 保留领取单。**

但不必让 Redis 维护完整的 `PROCESSING → SUCCESS → FAILED` 状态机。

- Redis 负责说明：**用户是否抢到了，以及消息是否还有待处理。**
- MySQL 负责说明：**这笔额度最终有没有到账。**

```
GET /api/quota/activities/1001/claims/me
             ↓
        查询 MySQL
             ↓
       是否存在领取单？
        /          \
       是           否
       ↓            ↓
   返回最终状态    查询 Redis
                    ↓
              是否存在预扣记录？
                 /       \
                是        否
                ↓         ↓
             PROCESSING  未找到
```

