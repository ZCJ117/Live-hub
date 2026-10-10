# SPEC-13 · 秒杀模块亮点审查与简历口径

> 编制日期：2026-10-10
> 审查视角：后端面试官 / 高并发系统设计（8 年经验）
> 目标读者：项目作者本人（用于简历撰写、面试陈述）
> 证据基线：`master` 工作区（含未提交改动），所有 `file:line` 均为实测阅读所得
> 本文是三篇拆分中的**第 1 篇（审查篇）**。改进方案见 [SPEC-14](SPEC-14-秒杀P0稳定性加固.md) / [SPEC-15](SPEC-15-秒杀P1P2优化与深度建设.md)。

---

## 0. 文档定位与边界

### 0.1 三篇分工

| 文档 | 定位 | 内容 |
|---|---|---|
| **SPEC-13（本文）** | 审查篇 | 六维度「有亮点 / 无亮点」判定与依据、改进项分流表、简历与面试口径 |
| [SPEC-14](SPEC-14-秒杀P0稳定性加固.md) | 止血篇 | P0-1 入口限流防刷 / P0-2 在途补偿器 / P0-3 活动时间窗 / P0-4 入参校验 |
| [SPEC-15](SPEC-15-秒杀P1P2优化与深度建设.md) | 优化篇 | P1-1 池化压测 / P1-2 缓存 / P1-3 Cluster / P1-4 依赖保护 / P2-1 事件表 / P2-2 风控 |

阅读顺序建议：**先读本文定口径 → 再做 SPEC-14 止血 → 有余力再做 SPEC-15**。

### 0.2 与既有 SPEC 的分工

`docs/specs/` 下的 SPEC-01 ~ SPEC-11 是 **缺陷修复规格**（README §0 明确定位为"只收录会导致错误行为的系统缺陷与安全漏洞"）。
本文与 SPEC-14/15 是 **能力建设规格**——回答"这个模块有没有能写进简历、经得起面试追问的亮点，没有的话怎么补"。

因此：

| 关系 | 说明 |
|---|---|
| **不重复** | SPEC-03（秒杀主链路正确性）、SPEC-04（一致性补偿与对账）、SPEC-05（缓存正确性）、SPEC-06（鉴权与越权）、SPEC-07（Feign 契约）、SPEC-08（MQ 可靠性）已记录的缺陷，本文只引用、不重述。 |
| **新增发现** | 本文 §2 发现的 **两处既有 SPEC 未记录的新缺陷**：(a) 秒杀活动时间窗完全不校验；(b) Lua 脚本的 Cluster 不兼容（`CROSSSLOT`）。对应方案分别在 SPEC-14 §2.3 与 SPEC-15 §2.3。 |
| **修正口径** | §4 给出简历/面试表述的**可用与不可用清单**——这是本文最直接的价值。 |
| **前置依赖** | SPEC-14/15 的所有改进项默认 **SPEC-03/04/08 的缺陷已修复**。未修复前，方案建立在会出错的链路上。 |

### 0.3 已确认的选型边界

| 决策项 | 结论 |
|---|---|
| 优先级口径 | 两者兼顾，按 ROI 排 P0/P1/P2 |
| 组件边界 | **只用现有栈，零新依赖**（Redis+Lua / RocketMQ / Redisson / Sentinel Core / Caffeine，均已在仓库内） |
| 本轮交付 | 只出文档；是否实施由后续决定 |

---

## 1. 模块现状总结表

| # | 维度 | 现状一句话 | 亮点判定 |
|---|---|---|---|
| 1 | 并发控制与防超卖 | Lua 单次 EVAL 原子完成"查库存→判重→DECR→SADD→写明细"，DB 侧 `stock>0` 乐观条件兜底，一人一单三层防护 | ✅ **有亮点**（有一处面试追问会掉分，见 §2.1） |
| 2 | 缓存策略与 DB 一致性 | 对账等式 `Redis 可售 := DB 余量 − 在途` 设计正确；但**券路径零缓存**，缓存组件只有空值缓存、无互斥重建/逻辑过期 | ⚠️ **半个亮点**（一致性是亮点，缓存是空白） |
| 3 | 接口性能优化 | 架构上异步（Redis 预扣 + MQ 削峰 + 立即返 orderId）；但**无任何连接池/线程池调优**，**秒杀无压测基线** | ⚠️ **亮点不足** |
| 4 | 分布式锁 / 队列 | 消费端按订单粒度 Redisson 锁 + RocketMQ 重试 3 次 + DLQ；但**无事务消息、无本地事件表**，且 DLQ 落地后无人处置 | ✅ **有亮点**（简历口径必须改，见 §4） |
| 5 | 限流 / 降级 / 熔断 | 限流基建存在但**只覆盖 `/agent/**`**；秒杀入口**无限流、无熔断、无降级预案**；全仓 Feign 无 fallback | ❌ **无亮点**（最大空白） |
| 6 | 安全防护（防刷/幂等/风控） | 幂等与越权防护扎实（三层幂等、userId 从登录态强注入）；**秒杀入口零频控、零风控**，活动时间窗不校验 | ⚠️ **半有亮点**（幂等强，防刷/风控空白） |

---

## 2. 六维度审查结论与依据

### 2.1 维度一：并发控制与防超卖 → ✅ 有亮点

**依据**

| 机制 | 实证 |
|---|---|
| Lua 单次 EVAL 原子完成「查库存 → 判重 → DECR → SADD → 写明细」 | `order-service/src/main/resources/seckill.lua:22-62` |
| 返回值语义分离 `0成功 / 1无库存 / 2重复 / 3key缺失`，`null` 折叠为 3 防拆箱 NPE | `order-service/.../service/impl/VoucherOrderServiceImpl.java:104-130` |
| DB 侧乐观条件扣减 `stock = stock - 1 WHERE voucher_id = ? AND stock > 0` | `voucher-service/.../service/impl/VoucherServiceImpl.java:100-104` |
| 一人一单三层：Lua `SISMEMBER/SADD` + DB `uk_user_voucher` + 消费端 count 复核 | `seckill.lua:34,42` / `docs/SQL/start.sql:166-169` / `SeckillOrderConsumer.java:133-143` |
| MQ 发送失败回滚预扣（`INCR` + `SREM` + `HDEL`） | `VoucherOrderServiceImpl.java:136-143,159-171` |

**面试官会追问、且当前会掉分的点**

`stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), ...)` 传入的是**空 KEYS**（`VoucherOrderServiceImpl.java:106`），所有 key 在脚本内字符串拼接（`seckill.lua:17-19,58`）。后果：

- `seckill:stock:{vid}`、`seckill:order:{vid}`、`seckill:order:detail:{vid}` 三者的 CRC16 slot **互不相同**；
- `seckill:order:queue`（`seckill.lua:58`）无 voucherId 后缀，是全局限定 key，**必然跨 slot**。

→ **迁移到 Redis Cluster 会立即 `CROSSSLOT Keys in request don't hash to the same slot` 报错**。当前单节点 Redis 掩盖了这个限制。这与"Redis 单线程保证原子性"是同一个知识点的两面，面试官大概率会顺着问下去。补强方案见 [SPEC-15 §2.3](SPEC-15-秒杀P1P2优化与深度建设.md)。

### 2.2 维度二：缓存策略与 DB 一致性 → ⚠️ 半个亮点

**真亮点**

`SeckillConsistencyServiceImpl.expectedRedisStock`（`order-service/.../service/impl/SeckillConsistencyServiceImpl.java:368-381`）给出的等式：

```
Redis 可售数 := max(0, DB 余量 − 在途)
在途 := SCARD(seckill:order:{vid}) − COUNT(tb_voucher_order WHERE voucher_id = ?)
```

配合 `@Scheduled(fixedRate = 300000)` 定时对账（`:298-334`）、双向修复入口（`:126-184` / `:186-252`）、审计表 `tb_seckill_consistency_audit`（`:160-166`），构成完整闭环。这个设计同时规避了"凭空补货导致超卖"和"漏补导致少卖"，**比多数同题材项目的对账逻辑更严谨**。

**空白**

- 券查询**零缓存**：`getSeckillStock` 直查 DB（`VoucherServiceImpl.java:113-120`），`queryVoucherById` / `queryVouchersByIds`（`VoucherController.java:69-90`）同样直查；voucher-service 全模块 grep `@Cacheable|CacheClient|MultiLevelCache` **零命中**。
- 二级缓存**只在 shop-service 启用**（`shop-service/src/main/resources/application.yaml:47-52`），voucher-service 的 `application.yaml` 无 `hmdp.cache.enabled`。
- 缓存组件只有**空值缓存**（`common/.../cache/MultiLevelCache.java:77-79`），**无互斥重建、无逻辑过期**（全仓零命中）。

> 结论表述建议：不要写"完善的缓存策略"。写"秒杀库存走 Redis 预扣账本 + 定时对账保证最终一致"更准确。

### 2.3 维度三：接口性能优化 → ⚠️ 亮点不足

**有的**：Redis 预扣 + MQ 削峰 + 立即返回 orderId；库存扣减在消费者异步完成。

**缺的（都可量化）**

| 项 | 实证 |
|---|---|
| 发送仍阻塞等 broker ack（"半异步"） | `SeckillOrderProducer.java:47-61` 的 `syncSend(..., 3000)` |
| 无 Tomcat 线程池配置（默认 `threads.max=200`） | 全仓 yaml grep `tomcat\|threads` 零命中 |
| 无 HikariCP 连接池配置（**默认 `maximum-pool-size=10`**） | 全仓 yaml grep `hikari\|maximum-pool-size` 零命中 |
| Redis 连接池偏小 | `order-service/.../application.yaml:27-31`：`max-active: 10` |
| **秒杀链路无压测基线** | `docs/specs/README.md:21` 的 `QPS ≥ 21027` 是**商户信息查询**的基线，与秒杀无关 |

### 2.4 维度四：分布式锁 / 队列 → ✅ 有亮点（口径需修正）

**依据**

| 机制 | 实证 |
|---|---|
| 消费端按订单粒度 Redisson 锁 `lock:order:{orderId}`，`tryLock(10, 30, SECONDS)` | `SeckillOrderConsumer.java:114-123`，解锁 `:187-191` |
| 获取锁失败 → **抛异常触发重试**（而非静默 return） | `:118-123` |
| 幂等三层：`selectById(orderId)` + `(userId,voucherId)` count + `DuplicateKeyException` | `:126-131` / `:133-143` / `:173-180` |
| 明细 Hash 仅在**成功落库后**删除 | `:184-185` |
| RocketMQ `maxReconsumeTimes = 3`，耗尽后进 `%DLQ%seckill-order-consumer-group` | `:43-47` / `:204-209` |
| DLQ handler 落 `seckill:order:pending` | `SeckillOrderDLQConsumer.java:64-76` |
| 对账任务独立锁 `lock:stock:sync:` / `lock:repair:` | `SeckillConsistencyServiceImpl.java:46-47` |

**必须删掉的简历表述**（全仓 grep 确认**不存在**）

| 表述 | 实际实现 |
|---|---|
| ❌ "RocketMQ **事务消息**" | 用的是 `syncSend`（`SeckillOrderProducer.java:49-53`）；`sendMessageInTransaction` **全仓零命中** |
| ❌ "**本地事件表** 防丢防重" | 无 `outbox` / `tb_seckill_event` / `LocalEvent` / `event_table`，**全仓零命中** |

**真实缺口**

1. `syncSend` 成功但消费端始终未消费（broker 重启丢内存消息、消费组长时间下线）→ Redis 预扣不释放、用户被 `SADD` 标记后无法再买，**无任何补偿**（详见 [SPEC-14 §2.2](SPEC-14-秒杀P0稳定性加固.md)）。
2. DLQ 只 `rightPush` 到 `seckill:order:pending`，**无消费者、无告警、无重放工具**——落进黑洞。
3. 生产端消息**未设置 `keys=`**（`SeckillOrderProducer.java:51`），排障时无法按 orderId 反查消息轨迹。

### 2.5 维度五：限流 / 降级 / 熔断 → ❌ 无亮点（最大空白）

**依据**

- 网关限流 `AgentRateLimitFilter` **只匹配 `/agent/`**（`gateway-service/.../filter/AgentRateLimitFilter.java:29,38`）→ **秒杀接口 `/voucher-order/seckill/{id}` 完全无限流**。
- 底层令牌桶 `AgentTokenBucketLimiter` + `limiter/token-bucket.lua` 是通用实现（key 由调用方给），**只是没有接到秒杀路径上**。
- Sentinel 只存在于 agent-service（编程式 `DegradeRule`，RT 2000ms / slowRatio 0.5，`SentinelRuleConfig.java:40-49`），**秒杀链路零熔断**。
- 全仓 `@FeignClient` **无一个 fallback / fallbackFactory**（含 `order-service/.../feign/VoucherFeignClient.java:16-17`）。
- 无网关路由超时/重试配置（`gateway-service/.../application.yaml` grep 零命中）。
- 无降级预案：Redis key 缺失直接返回 `STOCK_KEY_MISSING`（`VoucherOrderServiceImpl.java:126-128`），**不回退 DB 直查**。

> 这是六个维度里唯一"完全没有可讲内容"的一项，也是 ROI 最高的补强方向。

### 2.6 维度六：安全防护 → ⚠️ 半有亮点

**扎实的**

| 项 | 实证 |
|---|---|
| 幂等：`DuplicateKeyException` 捕获 + orderId 主键 + `seckill:deduct:{vid}` Set | `SeckillOrderConsumer.java:173-180` / `VoucherServiceImpl.java:84-98` |
| 越权防护：`myOrders`/`refund` 的 userId **从登录态强制注入**，拒绝传参 | `VoucherOrderController.java:51-55,64-71` |
| 验证码频控：手机号 60s/次 + 24h/10 次，IP 24h/20 次 | `UserServiceImpl.java:48-52,86-105` |
| 验证码一次性（校验成功即删）+ 不明文入日志 | `UserServiceImpl.java:137` / `:75-77` |
| 敏感配置走环境变量 `${MYSQL_PASSWORD:}` / `${REDIS_PASSWORD:}` | 全部 8 个服务的 `application.yaml` |

**空白的**

| 项 | 实证 |
|---|---|
| **秒杀入口零频控**：登录有频控，下单接口没有——攻击面直接暴露 | `/voucher-order/seckill/{id}` 无任何限流（配合维度五） |
| **入参无校验**：`seckillVoucher(@PathVariable("id") Long voucherId)` 无 `@Valid`、无正数校验 | `VoucherOrderController.java:35-38` |
| **活动时间窗不校验**：Lua 只看库存与重复下单 | `seckill.lua:22-36`；order-service 全模块 grep `beginTime\|endTime` **零命中**（★ 新发现） |
| **零风控**：无黑名单 / 设备指纹 / 行为风控 | 全仓 grep 零命中 |

**★ 活动时间窗缺陷展开**

`addSeckillVoucher` 在创建券时**立即**写入 `seckill:stock:{vid}`，且**不设 TTL**（`VoucherServiceImpl.java:65-67`）。由于下单入口不校验 `beginTime`/`endTime`：

- 活动**开始前**只要知道 voucherId，即可抢购成功并占用库存；
- 活动**结束后**只要库存未耗尽，仍可继续抢购。

对照：对账侧的 `listActiveSeckillVoucherIds` **有**时间窗过滤（`VoucherServiceImpl.java:141-143`），说明"活跃券"的定义在系统内是存在且被使用的——**入口与对账口径不一致**。

---

## 3. 改进总览：问题 → 方案的分流表

排序依据：**面试杀伤力 × 真实缺陷严重度 × 实施成本**，取高 ROI 优先。

| 优先级 | 编号 | 改进项 | 对应维度 | 类型 | 成本 | 归属方案 |
|---|---|---|---|---|---|---|
| **P0** | P0-1 | 秒杀入口双层限流与防刷 | ⑤⑥ | 补空白 | 低（复用现有令牌桶） | [SPEC-14 §2.1](SPEC-14-秒杀P0稳定性加固.md) |
| **P0** | P0-2 | 在途订单补偿器（含 DLQ 处置） | ②④ | **真缺陷** | 中 | [SPEC-14 §2.2](SPEC-14-秒杀P0稳定性加固.md) |
| **P0** | P0-3 | 秒杀活动时间窗校验 | ①⑥ | **真缺陷**（新发现） | 低 | [SPEC-14 §2.3](SPEC-14-秒杀P0稳定性加固.md) |
| **P0** | P0-4 | 秒杀入参校验加固 | ⑥ | 补空白 | 极低 | [SPEC-14 §2.4](SPEC-14-秒杀P0稳定性加固.md) |
| **P1** | P1-1 | 连接池/线程池调优 + 压测基线 | ③ | 补空白 | 低 | [SPEC-15 §2.1](SPEC-15-秒杀P1P2优化与深度建设.md) |
| **P1** | P1-2 | 缓存组件补互斥重建 + 券元信息接入 | ② | 补空白 | 中 | [SPEC-15 §2.2](SPEC-15-秒杀P1P2优化与深度建设.md) |
| **P1** | P1-3 | Lua 的 Cluster 兼容（hash tag + 显式 KEYS） | ① | 深度加分 | 中 | [SPEC-15 §2.3](SPEC-15-秒杀P1P2优化与深度建设.md) |
| **P1** | P1-4 | 依赖保护：Feign 超时对齐 + 消费端快速失败 | ⑤ | 补空白 | 低 | [SPEC-15 §2.4](SPEC-15-秒杀P1P2优化与深度建设.md) |
| **P2** | P2-1 | 本地事件表 / 事务消息的正确形态 | ④ | 深度加分 | 高 | [SPEC-15 §2.5](SPEC-15-秒杀P1P2优化与深度建设.md) |
| **P2** | P2-2 | 风控（黑名单 / 设备维度） | ⑥ | 深度加分 | 中 | [SPEC-15 §2.6](SPEC-15-秒杀P1P2优化与深度建设.md) |

**依赖关系**：P0-1 / P0-2 / P0-3 / P0-4 四者**互不依赖**，可并行；P1-3 若执行，必须并入 SPEC-14 的**批次 B**（与 P0-2 同批，两者都触碰 Redis key 契约）；P2-2 依赖 P0-1 的规则框架先落地。

> 注意：**优先级（P0/P1/P2）≠ 上线批次（A/B/C/D）**。优先级按 ROI 排；批次按"是否触碰数据契约、上线风险"排。例如 P1-1 属 P1 优先级，但无契约变更，放在批次 A 先上线。批次划分见 SPEC-14 §3 与 SPEC-15 §3。

---

## 4. 简历与面试口径修正

> 本节是本文最直接可用的产出。**红线：不可写本仓库不存在的东西**——面试官一追问就穿帮。

### 4.1 ❌ 当前不可写（代码中不存在）

| 表述 | 实际情况 |
|---|---|
| "RocketMQ **事务消息**" | 用的是 `syncSend`；`sendMessageInTransaction` 全仓零命中 |
| "**本地事件表** 防丢防重" | 无 outbox / `tb_seckill_event` / `LocalEvent`，全仓零命中 |
| "Redis 分布式锁兜底秒杀入口" | 秒杀入口**无锁**；Redisson 只用在 MQ 消费端与对账任务 |
| "完善的缓存策略 / 多级缓存" | 券路径**零缓存**；二级缓存只在 shop-service |
| "完善的限流熔断" | 限流只覆盖 `/agent/**`；秒杀链路零熔断 |
| "压测无超卖" | 仓库内**没有秒杀压测基线**（21027 QPS 是商户查询的） |

### 4.2 ✅ 当前就可以写（有实证）

- Redis Lua 单次 EVAL 原子完成库存校验、扣减与一人一单，返回码语义化区分"无库存 / 重复下单 / key 缺失"
- 一人一单三层防护：Lua 原子判重 + DB 唯一索引 `uk_user_voucher` 兜底 + 消费端幂等复核
- DB 侧 `stock > 0` 乐观条件扣减，杜绝负库存
- 秒杀一致性对账等式：`Redis 可售数 := DB 余量 − 在途`，5 分钟定时对账 + 双向修复 + 审计留痕
- RocketMQ 消费者幂等（主键 + 唯一索引 + `DuplicateKeyException` 捕获），重试 3 次后进死信并落待处理列表
- 越权防护：订单查询/退款接口的 userId 从登录态强制注入，拒绝外部传参

### 4.3 ✅ 完成 SPEC-14 后可新增

- 秒杀入口网关层「用户 + IP」双层令牌桶限流，超限 429，且限流组件故障时 fail-open 不阻断下单
- 在途订单补偿器：超时未落库订单自动重投/安全释放，消除"秒杀成功但无订单"的预扣泄漏
- 秒杀活动时间窗在入口强校验，与对账侧活跃券口径一致

### 4.4 ✅ 完成 SPEC-15 后可新增

- 秒杀接口压测基线：QPS / p99 / 错误率（超卖数 0）
- 缓存组件具备互斥重建（防击穿），热点券元信息走 L1 + L2
- Lua 脚本具备 Cluster 兼容能力（hash tag + 显式 KEYS），可讲清 `CROSSSLOT` 原理与解法
- 消息投递与本地状态同事务（本地事件表），丢失窗口为 0

### 4.5 面试预设问答（自测用）

1. **"你的 Lua 脚本为什么用 EVAL 而不是 MULTI/EXEC？迁移到 Redis Cluster 会怎样？"**
   → 当前答案：EVAL 依赖 Redis 单线程原子执行，无需 MULTI；但 key 在脚本内拼接且含全局 key，**Cluster 下会 CROSSSLOT**，需 hash tag + 显式 KEYS（SPEC-15 P1-3）。
2. **"MQ 发送成功了但消费者一直没消费，你的系统会怎样？"**
   → 当前答案：会预扣泄漏且用户无法再买，且现有对账的聚合等式**掩盖**它。SPEC-14 P0-2 是补丁。
3. **"怎么保证不超卖？"**
   → 三层：Lua 原子判重扣减 / DB `stock>0` 乐观条件 / `uk_user_voucher` 唯一索引。
4. **"秒杀接口被刷怎么办？"**
   → 当前答案：**没有防护**（回答前先做 SPEC-14 P0-1）。
5. **"Redis 挂了秒杀还能用吗？"**
   → 当前答案：返回 `STOCK_KEY_MISSING`，**不回退 DB**——这是有意的（避免绕开预扣账本），但需说明取舍。

---

## 5. 附录：证据索引

| 主题 | 文件 | 关键行 |
|---|---|---|
| Lua 脚本全文与返回值契约 | `order-service/src/main/resources/seckill.lua` | 3, 17-19, 22-62 |
| 脚本调用与返回值分支 | `order-service/.../service/impl/VoucherOrderServiceImpl.java` | 69-74, 104-130, 136-143, 159-171 |
| 消息发送（syncSend） | `order-service/.../mq/SeckillOrderProducer.java` | 32, 47-61, 74-99 |
| 消费者锁 / 幂等 / 重试 | `order-service/.../mq/SeckillOrderConsumer.java` | 43-47, 106-211, 114-123, 126-143, 167-185, 204-209 |
| DLQ handler | `order-service/.../mq/SeckillOrderDLQConsumer.java` | 29-38, 64-76 |
| 对账等式与调度 | `order-service/.../service/impl/SeckillConsistencyServiceImpl.java` | 46-47, 100-109, 201-220, 298-334, 368-381 |
| 券创建 / DB 扣减 / 查库存 | `voucher-service/.../service/impl/VoucherServiceImpl.java` | 54-69, 81-111, 113-120, 138-148 |
| Redis key 常量 | `common/.../utils/RedisConstants.java` | 14, 21-34, 36-50, 60-70 |
| 网关限流（仅 /agent/） | `gateway-service/.../filter/AgentRateLimitFilter.java` | 29, 38, 41-52 |
| 令牌桶实现 | `gateway-service/.../limit/AgentTokenBucketLimiter.java` + `limiter/token-bucket.lua` | 20-25, 32-33, 46-51 |
| Sentinel 熔断（仅 agent） | `agent-service/.../config/SentinelRuleConfig.java` | 40-49 |
| 验证码频控 | `user-service/.../service/impl/UserServiceImpl.java` | 48-52, 86-105, 137 |
| 秒杀/退款入口与越权防护 | `order-service/.../controller/VoucherOrderController.java` | 35-38, 44-71 |
| 唯一索引 | `docs/SQL/start.sql` | 166-169 |
| 二级缓存启用点 | `shop-service/src/main/resources/application.yaml` | 47-52 |
| 缓存组件（无 mutex） | `common/.../cache/MultiLevelCache.java` | 57-84, 77-79 |
| 池化配置 | `order-service/src/main/resources/application.yaml` | 27-31, 127-132, 136-137 |
| 性能基线（商户查询） | `docs/specs/README.md` | 21 |

---

## 6. 变更记录

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-10-10 | v1.0 | 由 SPEC-12 拆分为三篇之第 1 篇（审查篇）：六维度判定 + 改进分流表 + 简历口径 + 证据索引 |
