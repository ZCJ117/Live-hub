# SPEC-16 · 秒杀链路接入 RocketMQ 事务消息

> 编制日期：2026-10-10
> 目标读者：项目作者本人（用于项目迭代开发 + 面试深度加分）
> 证据基线：`master@bfa6ddc`；本文所有「字节码实证」均来自 `javap` 反编译本机 `.m2` 中的
> `rocketmq-spring-boot-2.2.3.jar` / `rocketmq-client-4.9.4.jar`，非文档推断
> 本文是 [SPEC-15 §2.5](SPEC-15-秒杀P1P2优化与深度建设.md) 方案 (b) 的落地篇。审查口径见 [SPEC-13](SPEC-13-秒杀模块亮点审查与简历口径.md)。

---

## 0. 文档定位与前置依赖

### 0.1 本文范围

SPEC-15 §2.5 把「本地事件表 / 事务消息」定为二选一，并留下结论：

> 方案 (a) 是本项目的正确形态；方案 (b) 只有先做了 (a) 的本地表才有资格谈，
> 二者不是平行选项，**(b) 是 (a) 的上层**。
> 这是简历里「RocketMQ 事务消息 + 本地事件表」表述唯一能成立的路径。

方案 (a) 已由 SPEC-15 P2-1 交付（`tb_seckill_outbox` + `SeckillOutboxDeliverer`，已合入 `master`）。本文落地 (b)，
把秒杀入口的投递从 `syncSend` 换成 `sendMessageInTransaction`（half message + 本地事务 + broker 回查）。

### 0.2 前置依赖

- 前置：SPEC-15 P2-1 已落地（`tb_seckill_outbox` 表、`SeckillOutbox` 实体、`SeckillOutboxMapper` 均已在 `master`）。
- 前置：SPEC-03 / 04 / 14 的秒杀主链路与补偿逻辑已修复。
- 本文**不触碰** Redis 预扣契约（`seckill.lua`）、不触碰消费端幂等、不触碰对账口径。

### 0.3 选型边界

**零新依赖**。`rocketmq-spring-boot-starter:2.2.3` 已在 `order-service/pom.xml:131-134`，
`@RocketMQTransactionListener` 与 `sendMessageInTransaction` 均为该 starter 自带能力。

### 0.4 通用约定

- 标注「**必须实测**」的条目不接受仅单元测试通过（沿用 SPEC 全局约定）。
- DB 集成测试用 `-Dtest=` 显式指定。
- surefire 用户属性名是 `-Dsurefire.failIfNoSpecifiedTests=false`；拼错会使下游模块静默跳过 = 假绿。

---

## 1. 改进项总览

| 编号 | 一句话目标 | 涉及模块 | 步骤编号 |
|---|---|---|---|
| T-1 | 秒杀入口投递改为事务消息，half message 与本地事件行由 broker 提交/回滚绑定 | order-service | T1–T5 |
| T-2 | 新增 broker 回查处理，落库状态即本地事务状态 | order-service | T3 |
| T-3 | 补投器保留为「事务状态未知」的快速兜底（与回查互补，非重复） | order-service | 不改 |

---

## 2. 详细方案

### 2.1 技术前提（字节码实证，全部已核实）

| # | 事实 | 证据 | 对设计的影响 |
|---|---|---|---|
| F1 | order-service 现有的 `rocketMQTemplate` **本来就是 `TransactionMQProducer`**，无需新建 | `RocketMQAutoConfiguration.defaultMQProducer` → `RocketMQUtil.createDefaultMQProducer`，偏移 26 / 61 均为 `new TransactionMQProducer(...)`；`RocketMQTransactionConfiguration` 偏移 89 对 `getProducer()` 做 `checkcast TransactionMQProducer` | 事务监听器直接挂在默认 template 上，**不新增 bean、不新增 producer 组** |
| F1b | 同一 JVM 内两个 producer 不能共用 producer group | `MQClientInstance.registerProducer` 用 `producerTable.putIfAbsent(group, producer)`，已存在则记 `the producer group[{}] exist already.` 并返回 `false`；`DefaultMQProducerImpl.start` 据此抛 `MQClientException("... has been created before, specify another name please.")` | **仅当一个组要挂两个 producer 时才触发**。本方案只有一个 producer，不触发；但这是一条禁令：后续不得为"隔离"再建同组 producer |
| F2 | `executeLocalTransaction` 抛异常 → 客户端写死 `ROLLBACK_MESSAGE` | `DefaultMQProducerImpl.sendMessageInTransaction(Message, LocalTransactionExecuter, Object)` 偏移 335：`getstatic LocalTransactionState.ROLLBACK_MESSAGE; astore 6`，随后偏移 351 `endTransaction(..., ROLLBACK, localException)` | 异常**不会**被转成 UNKNOW，回查不会因此触发；入口侧把「非 COMMIT」一律按失败处理即可，无死分支 |
| F3 | `@RocketMQTransactionListener` 默认线程池 `corePoolSize=1`、`maximumPoolSize=1`、`blockingQueueSize=2000` | 注解 `AnnotationDefault` 属性；`DefaultMQProducerImpl.initTransactionEnv` 把注解线程池赋给 `checkExecutor`，该字段全文仅出现于 `checkTransactionState` | 该池**只服务回查路径**（`checkLocalTransaction`），默认单线程会串行化回查；`executeLocalTransaction` 在调用方线程内联执行，**不受该池管辖**。仍须显式配置（见 §2.5） |
| F4 | 监听器收到的消息 payload 是**原始 `byte[]`**（`getBody()`），**不是**反序列化后的对象 | `RocketMQUtil.convertToSpringMessage(MessageExt)` 与 `convertToSpringMessage(Message)` 均为 `MessageBuilder.withPayload(msg.getBody())` | `checkLocalTransaction` 拿不到 `arg`（回查是异步的），必须自行反序列化消息体 |
| F5 | `TransactionSendResult.getLocalTransactionState()` 把本地事务结果**同步回传给调用方线程** | 客户端在 `endTransaction` 后构造 `TransactionSendResult` 并 `setLocalTransactionState(localTransactionState)` | 入口侧可以按 `sendStatus` + `localTransactionState` 分档决策，无需轮询 |
| F6 | `endTransaction` 失败被**静默吞掉**（只打 error 日志，不抛） | 客户端 `catch (Throwable e) { log.error("local transaction execute " + state + ", but end broker transaction failed", e); }` | 存在「本地返回 COMMIT 但 broker 未收到」的窗口，由回查兜底（§2.4 第 4 行） |

### 2.2 架构与数据流

**新增文件**

| 文件 | 职责 |
|---|---|
| `order-service/src/main/java/com/hmdp/order/mq/SeckillOrderTransactionListener.java` | `@RocketMQTransactionListener`（用默认 `rocketMQTemplateBeanName`），实现 `executeLocalTransaction` / `checkLocalTransaction` |

**改动文件**

| 文件 | 改动 |
|---|---|
| `mq/SeckillOrderProducer.java` | 新增 `sendSeckillOrderMessageInTransaction(SeckillOrderMessage)`，返回 `TransactionSendResult` |
| `service/impl/VoucherOrderServiceImpl.java` | 入口 `seckillVoucher` 去掉手写 INSERT + `syncSend`，改调事务消息；失败分支按 F5 分档 |
| `.e2e/rocketmq/broker.conf` | 调小回查参数以便实测（仅 E2E 环境） |

**明确不改**：`SeckillOutboxDeliverer`、`SeckillOutbox`、`SeckillOutboxMapper`、`SeckillOrderConsumer`（消费端幂等）、`seckill.lua`。

**数据流**

```
seckillVoucher
 1. 时间窗校验                                   ← 不变
 2. orderId = redisIdWorker.nextId("order")      ← 不变
 3. Lua 预扣（Redis）                             ← 不变
 4. rocketMQTemplate.sendMessageInTransaction(TOPIC, msg)
      └─ broker 写 half message（RMQ_SYS_TRANS_HALF_TOPIC，消费者不可见）
      └─ broker 回调 executeLocalTransaction(msg, arg)
             orderId = JSON.parseObject((byte[]) msg.getPayload(), SeckillOrderMessage.class)   ← F4
             INSERT tb_seckill_outbox(orderId, userId, voucherId, status=PENDING, retryCount=0)
             return COMMIT | ROLLBACK
      └─ 客户端 endTransaction 把状态发回 broker（可能静默失败 —— F6）
             COMMIT   → 消息搬进真实 topic，投递
             ROLLBACK → 丢弃 half message
      └─ return TransactionSendResult(sendStatus, localTransactionState)     ← F5
 5. 按 §2.4 分档
 6. COMMIT → markOutboxDelivered(orderId)
```

第 6 步语义变化：原为「已投递」，现为「broker 已确认提交、投递由 broker 负责」。保留它是为了让补投器
不重复捞正常单。

### 2.3 回查与本地事务判据

**回查触发条件**：broker 未收到 `endTransaction`，有两条来源 ——
(a) 进程在 `executeLocalTransaction` 返回之前崩溃/hang；
(b) `endTransaction` 的 oneway 调用静默失败（F6），本地已返回 COMMIT 但 broker 从未收到。
（`executeLocalTransaction` 抛异常走的是 ROLLBACK 快路径，见 F2，不产生回查。）
(b) 是回查作为**唯一**恢复路径的情形：入口侧已 `markOutboxDelivered`，行不再是 `status=0`，补投器无从捞起。

```
broker TransactionalMessageCheckService（transactionCheckInterval 周期）
  → CHECK_TRANSACTION_STATE → seckill-producer-group（F1：复用默认 template 的组）
  → SeckillOrderTransactionListener.checkLocalTransaction(msg)
       orderId = JSON.parseObject((byte[]) msg.getPayload(), SeckillOrderMessage.class).getOrderId()
       return seckillOutboxMapper.selectById(orderId) != null ? COMMIT : ROLLBACK
```

**判据的语义**：事件行存在 ⟺ 本地事务已提交。行被删除（投递失败分支，§2.4 第 3 行）⟺ 本地事务已回滚。
两者与 §2.4 的失败分支严格一致，不存在判据与业务动作打架的档位。

### 2.4 失败分支语义

| 情形 | sendStatus | localTransactionState | 入口动作 | 用户看到 |
|---|---|---|---|---|
| 正常 | `SEND_OK` | `COMMIT` | `markOutboxDelivered` | `orderId` |
| half message 未落盘（broker 不可达/超时） | 非 `SEND_OK`，或抛 `MessagingException` | — | 无本地事务，直接回滚 Redis 预扣 | 发送失败 |
| INSERT 失败（`executeLocalTransaction` 抛异常，F2） | `SEND_OK` | `ROLLBACK` | **先删事件行 → 删成功才回滚预扣** | 发送失败 |
| `endTransaction` 静默失败（F6） | `SEND_OK` | `COMMIT` | `markOutboxDelivered` | `orderId`，后续回查确认 |

**第 3 行必须先删事件行、再回滚预扣**，顺序即正确性：反了会在「回滚完成但行未删」的崩溃点留下一条待投递记录，
补投出去会在已释放的预扣上重新建单 —— 超卖方向。该论证沿用现有实现与
`SeckillVoucherServiceTest#投递失败时先删事件行再回滚预扣`，不重新推导。

**删除失败时不回滚预扣**（保留），接受「用户先看到一次失败、稍后真的拿到订单」的少卖，也不接受超卖。同现有取舍。

**入口侧不写 UNKNOW 档**：由 F2，框架不会替我们产生 UNKNOW（异常→ROLLBACK），
`executeLocalTransaction` 也不返回它。按 CLAUDE.md 第 2 条不为不可能场景加分支，
代码写成 `if (state != COMMIT) → 失败路径` 即可。

> 例外在**回查侧**：`checkLocalTransaction` 若连消息体都解析不出来，返回 UNKNOWN 而非 ROLLBACK——
> 那里猜 ROLLBACK 会把一个可能已提交的本地事务永久丢弃（少卖方向），UNKNOWN 只是让 broker 下一轮再问。
> 二者不是同一处判断，不要合并。

### 2.5 配置变更

**不新增任何配置项、不新增 bean**。由 F1，事务监听器直接挂在默认的 `rocketMQTemplate`
（组名沿用 `seckill-producer-group`）。依据 SPEC-15 双轴评审确立的约束——「新增配置项须对应 spec 功能点」，
此处没有需要运维调整的旋钮，做成可配只是假灵活性（CLAUDE.md 第 2 条）。

**`@RocketMQTransactionListener` 必须显式配线程池**（F3：默认 `max=1`）：

```java
@RocketMQTransactionListener(corePoolSize = 20, maximumPoolSize = 20, blockingQueueSize = 2000)
```

`maximumPoolSize` 与 Hikari `maximum-pool-size: 20` 对齐。**该池经 `initTransactionEnv` 赋给 `checkExecutor`，
只服务回查路径；`executeLocalTransaction` 同线程内联，不经此池。**因此显式配置的理由是**回查吞吐**：
默认 `max=1` 会把回查串行化，当一次崩溃留下大量半消息待查时，单线程回查成为吞吐瓶颈。

**`.e2e/rocketmq/broker.conf`（仅 E2E 环境）**：

```
transactionTimeOut = 2000          # 默认 6000：broker 判定"迟迟未收到提交"的等待
transactionCheckInterval = 3000    # 默认 60000：回查服务扫描间隔
```

half topic（`RMQ_SYS_TRANS_HALF_TOPIC` / `RMQ_SYS_TRANS_OP_HALF_TOPIC`）靠既有 `autoCreateTopicEnable = true`
自动创建，无需额外配置。**生产建议保持默认值**，本文改动只为让回查在秒级可观察。

**一条不改但必须写明的限制**：broker 当前 `flushDiskType = ASYNC_FLUSH`。事务消息「落盘才算数」的保证
在异步刷盘下**不覆盖 broker 掉电丢失**。改 `SYNC_FLUSH` 会整体拖慢并让既有压测基线失效，本次不改；
对外表述中不得宣称「事务消息保证了 broker 侧不丢」。

---

## 3. 待决决策点

无。两处原本的决策点在编制期已定：

| 原决策点 | 结论 | 依据 |
|---|---|---|
| 本地事务所绑形态：(A) INSERT 放进 `executeLocalTransaction` / (B) INSERT 排在发消息之前 | **选 A** | 形态 B 下 half message 与本地写入无任何原子性，是空壳实现，SPEC-13 §4.1 明令禁止 |
| 与 outbox / 补投器的关系：叠加 / 替换 / 旁路 | **选叠加** | 补投器覆盖的是行**仍为 `status=0`** 的窗口 —— 进程在 `executeLocalTransaction` 已提交 INSERT、**尚未** `markOutboxDelivered` 时崩溃。此窗口下回查首次发生在 `transactionTimeOut + transactionCheckInterval`（默认 6s + 60s）之后，而补投器 30s 判龄 + 3s 扫描即可捞起，快一个数量级且不依赖 broker 回查可达。补投器**不覆盖 F6**（行已被标记投递、只余 `status=1`），那里只能靠回查恢复 |

---

## 4. 实施步骤

### 批次 T —— 事务消息接入

| 步骤 | 内容 | 验证 |
|---|---|---|
| T1 | `SeckillOrderProducer` 新增 `sendSeckillOrderMessageInTransaction` | 单测 |
| T2 | 新增 `SeckillOrderTransactionListener`（`executeLocalTransaction` / `checkLocalTransaction`），显式配线程池 | 单测 5 / 6 |
| T3 | 改造 `VoucherOrderServiceImpl#seckillVoucher` 的投递与失败分支（§2.4） | 单测 1–4 |
| T4 | `broker.conf` 回查参数调小（仅 E2E 环境） | 启动 + E2E |
| T5 | 回归：`mvn -pl order-service -am test` | 全绿 |

---

## 5. 风险与测试方案

### 5.1 风险登记

| # | 风险 | 等级 | 触发场景 | 缓解措施 |
|---|---|---|---|---|
| R1 | 本地事务线程池默认 `max=1`，高并发下入口串行排队 | 🔴 高 | 未显式配置注解线程池 | §2.5 显式配置并与 Hikari 对齐 |
| R2 | 入口多一次 broker 往返（half message 落盘）再等本地事务，**入口 p99 必然上升** | 🟠 中 | 事务消息的固有成本 | 写进 §6 指标；对外表述为「换的是不丢，不是更快」，不粉饰 |
| R3 | `ASYNC_FLUSH` 下不覆盖 broker 掉电 | 🟠 中 | broker 掉电丢 half message | 不改，§2.5 限制条款写明，禁止过度宣称 |
| R4 | 现有入口单测 mock 点由 `boolean` 变为 `TransactionSendResult` | 🟡 低 | T3 改动 | §5.2 清单，逐条改造 |
| R5 | 回查路径窗口毫秒级，无法稳定复现 | 🟡 低 | E2E 验证 | 回查的 COMMIT 分支降级为单测覆盖判定逻辑，在 §5.2 如实标注 |
| R6 | 后续有人为"隔离"给同一组再建一个 producer → 启动即抛 `has been created before` | 🟡 低 | 未来改动 | F1b 写成禁令；本方案不需要第二个 producer |

### 5.2 测试方案

**单元测试**

改造 `SeckillVoucherServiceTest`（mock 点：`SeckillOrderProducer` 返回 `TransactionSendResult`）：

1. `COMMIT` → 返回 orderId、`markOutboxDelivered` 被调、**预扣不回滚**
2. `ROLLBACK` → 先删事件行、删成功后才回滚预扣（**`InOrder` 断言顺序**，超卖防护关键）
3. `ROLLBACK` + 删除抛异常 → **不回滚预扣**（保留），返回失败
4. 发送失败 / 抛 `MessagingException` → 回滚预扣，且**不调用** `deleteOutboxRow`（当时根本没有行）

新增 `SeckillOrderTransactionListenerTest`：

5. `executeLocalTransaction`：INSERT 成功 → COMMIT；INSERT 抛异常 → ROLLBACK
6. `checkLocalTransaction`：行存在 → COMMIT；行不存在 → ROLLBACK

> 第 6 条必须用**真字节 payload**（`JSON.toJSONBytes(msg)`）喂入，验证 F4 的反序列化链路，
> 不得塞入 mock 对象绕过解析。
>
> 第 5 / 6 条**不需要**注册 `TableInfo`：监听器只用 `insert` / `selectById`，不经过 lambda 列名解析。
> 会被 `can not find lambda cache for this entity` 绊住的是入口侧（`markOutboxDelivered` 用
> `Wrappers.lambdaUpdate`），而 `SeckillVoucherServiceTest` 里本来就有 `initSeckillOutboxTableInfo()`，
> 不需要新增。

**端到端实测（必须实测，不接受仅单测通过）**

| # | 场景 | 期望 |
|---|---|---|
| E10 | MySQL 行锁阻塞 INSERT 触发回查（见下） | broker 回查被触发、`checkLocalTransaction` 被调用、最终由补投器兜底建单 |

**E10 操作步骤（零生产代码注入）**

```
1. 另开 MySQL 会话：BEGIN; SELECT * FROM tb_seckill_outbox FOR UPDATE;
2. 发起秒杀下单
   → Lua 预扣 ✓，half message 落盘 ✓
   → executeLocalTransaction 的 INSERT 被间隙锁阻塞，客户端迟迟不返回
3. t=2s  broker 判定超时；t=3s 回查服务扫描 → CHECK_TRANSACTION_STATE 发给 producer group
   → checkLocalTransaction 查表：行尚未提交 → ROLLBACK → half message 被丢弃
4. 拍证据：broker transaction.log 的回查记录 + 应用日志的 checkLocalTransaction 调用
5. 释放行锁 → INSERT 完成 → 客户端补发 COMMIT，但 broker 已丢弃该 half message
   → 这条订单的消息确实丢了
6. 30s 后 SeckillOutboxDeliverer 扫到 status=PENDING 的行补投 → 消费端建单
```

本场景一次同时验证**回查真的会触发**与**补投器兜底真的有意义**（即「叠加」形态的价值）。

> **执行状态（2026-10-10）**：E10 **尚未实测**。执行时 Docker Desktop 守护进程未运行
> （`docker info` → `failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine`），
> 无法拉起 `hmdp-rocketmq-broker` 与 `hmdp-mysql`。上方步骤 1–7 已逐条备好，环境恢复后可直接执行。
> 这与 [SPEC-15 §5.2](SPEC-15-秒杀P1P2优化与深度建设.md) 中 E4 / E6 / E7 / E8 / E9 的未实测状态同源。
> 在此之前的全部结论均为**代码与单测层面**的，不含 broker 侧真实回查的运行证据，对外表述不得声称已实测。

**如实标注**：回查的另一分支（行已提交、COMMIT 未发出 → 回查返回 COMMIT）窗口仅毫秒级，
无法稳定复现，**降级为单测第 6 条覆盖判定逻辑**，不声称已实测。

**回归门槛**：`mvn -pl order-service -am test` 全绿。

---

## 6. 验收标准汇总

| # | 验收断言 | 类型 |
|---|---|---|
| 1 | 应用启动即完成事务监听器注册，无 `does not exist TransactionListener` / `already exists RocketMQLocalTransactionListener` 异常 | 必须实测 |
| 2 | `COMMIT` 时预扣不回滚、`ROLLBACK` 时先删行后回滚（顺序断言） | 单测 |
| 3 | `ROLLBACK` + 删除失败时不回滚预扣 | 单测 |
| 4 | 发送失败时不调用删行（无行可删） | 单测 |
| 5 | `executeLocalTransaction` 的 INSERT 成功/异常分别返回 COMMIT / ROLLBACK | 单测 |
| 6 | `checkLocalTransaction` 用真字节 payload 正确判定行存在与否 | 单测 |
| 7 | E10：构造未提交的 half message，观察到 broker 回查 + `checkLocalTransaction` 调用 + 补投器最终兜底建单 | 必须实测 |
| 8 | 秒杀正常链路端到端仍可用，`seckill-order-topic` 消费正常 | 必须实测 |

---

## 7. 变更记录

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-10-10 | v1.0 | 初版：SPEC-15 §2.5 方案 (b) 落地设计。含字节码实证前提、架构与数据流、失败分支语义、配置变更、单测 6 条 + E2E E10 |
| 2026-10-10 | v1.1 | **修正 v1.0 的 F1 误读**：原结论「必须拆独立 producer 组 + 第二个 template」不成立。实测 `RocketMQUtil.createDefaultMQProducer` 偏移 26/61 为 `new TransactionMQProducer(...)`，现有 `rocketMQTemplate` 本就是事务生产者（F1）；组唯一性约束（改记为 F1b）只在“一组挂两个 producer”时触发。据此删除 `SeckillTransactionConfig` 与 `seckill-tx-producer-group`，风险 R4 替换为 R6（禁令），批次 T 由 6 步减为 5 步 |
| 2026-10-10 | v1.2 | 修正 F3 的因果误判：注解线程池经 initTransactionEnv 赋给 checkExecutor，只服务回查路径；executeLocalTransaction 同线程内联，不受该池管辖。§2.5 的配置依据由“入口 INSERT 串行”改为“回查吞吐”。另按全分支评审收窄补投器职责（见 §3） |
