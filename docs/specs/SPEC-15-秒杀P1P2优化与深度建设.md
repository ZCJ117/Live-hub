# SPEC-15 · 秒杀 P1/P2 优化与深度建设

> 编制日期：2026-10-10
> 目标读者：项目作者本人（用于项目迭代开发 + 面试深度加分）
> 证据基线：`master` 工作区（含未提交改动），所有 `file:line` 均为实测阅读所得
> 本文是三篇拆分中的**第 3 篇（优化篇）**。审查结论与简历口径见 [SPEC-13](SPEC-13-秒杀模块亮点审查与简历口径.md)；P0 止血见 [SPEC-14](SPEC-14-秒杀P0稳定性加固.md)。

---

## 0. 文档定位与前置依赖

### 0.1 本文范围

收录 **P1（补空白 / 深度加分）** 与 **P2（深度建设）** 共六项：

| 编号 | 改进项 | 维度 | 性质 | 是否触碰数据契约 |
|---|---|---|---|---|
| P1-1 | 连接池 / 线程池调优 + 秒杀压测基线 | ③ | 补空白 | 否 |
| P1-2 | 缓存组件补互斥重建 + 券元信息接入 | ② | 补空白 | 否 |
| P1-3 | Lua 的 Cluster 兼容（条件触发） | ① | 深度加分 | **是**（Redis key 命名） |
| P1-4 | 依赖保护：Feign 超时对齐 + 消费端快速失败 | ⑤ | 补空白 | 否 |
| P2-1 | 本地事件表 / 事务消息的正确形态 | ④ | 深度加分 | **是**（新增表） |
| P2-2 | 风控（黑名单 / 设备维度） | ⑥ | 深度加分 | 否 |

### 0.2 前置依赖

- 通用前置：本文方案默认 **SPEC-03 / 04 / 08 的缺陷已修复**。
- **P2-2 依赖 P0-1 的规则框架**先落地（见 [SPEC-14 §2.1](SPEC-14-秒杀P0稳定性加固.md)）。
- **P1-3 若执行，必须并入 SPEC-14 的批次 B**——它与 P0-2 同批，两者都触碰 Redis key 契约。
- P2-1 的前置是 P0-2 已落地（P0-2 是低成本的事后补救，P2-1 是把窗口压到 0 的彻底方案）。

### 0.3 选型边界

**只用现有栈，零新依赖**：Redis + Lua / RocketMQ / Redisson / Sentinel Core / Caffeine / HikariCP，全部已在仓库内。

### 0.4 通用约定

- `*IT` 命名的集成测试需 `-Dtest=` 显式指定。
- surefire 用户属性名是 **`-Dsurefire.failIfNoSpecifiedTests=false`**；拼错会使下游模块**静默跳过** = 假绿。
- 标注「**必须实测**」的条目不接受仅单元测试通过。

---

## 1. 改进项总览

| 编号 | 一句话目标 | 涉及模块 | 步骤编号 |
|---|---|---|---|
| P1-1 | 产出秒杀接口 QPS/p99 基线，定位并移除连接池瓶颈 | order-service / voucher-service | A4 |
| P1-2 | 缓存组件具备防击穿能力；热点券元信息走 L1+L2 | common / voucher-service | C1–C2 |
| P1-3 | 脚本可在 Redis Cluster 下执行（无 `CROSSSLOT`）——**条件触发** | order-service / common | B5 |
| P1-4 | 下游故障时快速失败 + 可观测，而非线程线性堆积 | order-service | A5 |
| P2-1 | 消息投递与本地订单状态同事务，丢失窗口 = 0 | order-service | D1 |
| P2-2 | 秒杀入口可识别并拒绝已知恶意账号 / 设备 | gateway-service | D2 |

---

## 2. 改进项详细方案

> 每个方案统一给出：**问题现状 / 改进目标 / 技术选型 / 核心实现思路 / 效果评估指标**。

### 2.1 P1-1 · 连接池 / 线程池调优 + 秒杀压测基线

**问题现状**

全仓**无** `server.tomcat.threads.max`、**无** `spring.datasource.hikari.maximum-pool-size`（默认 **10**）；order-service Redis Lettuce `max-active: 10`（`application.yaml:27-31`）；秒杀链路**无任何压测基线**（`docs/specs/README.md:21` 的 QPS≥21027 是商户查询的）。

**改进目标**

- 产出秒杀接口的 QPS / p95 / p99 / 错误率 / 超卖数基线；
- 定位并移除瓶颈，使基线可解释。

**技术选型**

现有栈（Spring Boot 配置 + 现有 `SeckillMetrics`）；压测工具在仓库外（wrk / JMeter），不改业务代码。

**核心实现思路**

1. 为 order-service / voucher-service 显式配置 `server.tomcat.threads.max`、`spring.datasource.hikari.maximum-pool-size`、Redis pool `max-active`。
2. 用 `SeckillMetrics` 暴露计数（请求 / 成功 / 失败 / 库存不足 / 重复下单 / MQ 发送失败 / 消费成功 / DLQ）。
3. 阶梯压测 100 → 500 → 1000 并发，记录 QPS、p99、错误率，找出拐点并归因（通常是 Hikari 或 Redis pool）。
4. 压测后**必须**跑一次对账等式断言，确认超卖数 = 0。

**效果评估指标**

| 指标 | 目标 |
|---|---|
| 秒杀接口 QPS | 给出实测值（相对调优前提升幅度可量化） |
| p99 | 给出实测值 |
| **超卖数** | **恒为 0** |
| 错误率 | < 0.1% |
| 订单最终一致率 | 100%（对账等式成立） |

---

### 2.2 P1-2 · 缓存组件补互斥重建 + 券元信息接入

**问题现状**

`MultiLevelCache.get` 在 L2 未命中时**直接回源**（`common/.../cache/MultiLevelCache.java:57-84`），无互斥/单飞保护；缓存组件只有空值缓存（`:77-79`），**无逻辑过期**。voucher-service 未启用二级缓存，券查询全部直查 DB。

**改进目标**

- 缓存组件具备防击穿能力（同一 key 并发回源收敛为 1 次）；
- 热点券**元信息**走 L1 + L2。

**技术选型**：现有 `MultiLevelCache` + Caffeine + Redis + Redisson（均已在栈内）。

**核心实现思路**

1. `MultiLevelCache.get` 回源前用 Redisson `tryLock("lock:cache:rebuild:" + key)` 包住 `loader.get()`；未拿到锁则短暂 sleep 后**重读 L2**（标准双检）。
2. voucher-service 开 `hmdp.cache.enabled: true`，`queryVoucherById` 接缓存（TTL 用既有 `RedisConstants.cacheTtlSeconds()`，含抖动）。
3. **明确不缓存 `getSeckillStock`**：它是对账用的**强一致读**，缓存会直接破坏 SPEC-13 §2.2 的等式。此约束需写进 `SeckillConsistencyServiceImpl` 的类注释。

**效果评估指标**

| 指标 | 目标 |
|---|---|
| 券元信息缓存命中率 | 给出实测值 |
| 回源 DB QPS 降幅 | 可量化 |
| 击穿场景（热点 key 过期瞬间）下 DB 峰值连接数 | 相对无锁版本显著下降 |
| 库存读数 | **必须**仍为直查 DB（新增契约测试锁定） |

---

### 2.3 P1-3 · Lua 的 Cluster 兼容（条件触发）

> **触发条件**：仅当计划将 Redis 迁移到 Cluster / 分片时执行。若长期单节点，本项可作为**面试口头加分项**而不动代码（见 §3 D4）。

**问题现状**：空 KEYS 传参 + 脚本内拼 key + 全局 key `seckill:order:queue`（`seckill.lua:17-19,58`）→ Cluster 下必然 `CROSSSLOT`。这是 SPEC-13 §2.1 记录的面试追问掉分点。

**改进目标**：脚本可在 Cluster 下执行（所有 key 同 slot）。

**技术选型**：纯脚本 + 调用点改动。

**核心实现思路**

1. 所有 key 用 `{voucherId}` hash tag 包裹：`seckill:stock:{123}`、`seckill:order:{123}`、`seckill:order:detail:{123}`。
2. 全局 `seckill:order:queue` 改为按券分片 `seckill:order:queue:{voucherId}`。
3. KEYS 显式传入而非脚本内拼接（`Collections.emptyList()` → 实际 key 列表）。

**风险（必须同批处理）**：key 命名变更 = **破坏 SPEC-04 §5.2 冻结的契约**（`RedisConstants.java:39-40` 注释明确"本批冻结的契约"），需同批更新 `RedisConstants`、`SeckillKeyContractTest`、`SeckillConsistencyServiceImpl` 的读写点。

**效果评估指标**：单机行为完全不变（回归全绿）；Cluster 环境下脚本可成功执行（无 `CROSSSLOT`）。

---

### 2.4 P1-4 · 依赖保护：Feign 超时对齐 + 消费端快速失败

**问题现状**

维度⑤的"降级/熔断"部分在秒杀链路上完全空白：全仓 `@FeignClient` 无一个 fallback（`order-service/.../feign/VoucherFeignClient.java:16-17`）；`order-service` 的 Feign 超时为 `connectTimeout/readTimeout = 5000/5000`（`application.yaml:127-132`）；兜底调度为 MQ 重试 3 次后进 DLQ（`SeckillOrderConsumer.java:204-209`）。若 voucher-service 变慢，消费线程会被 5s 超时逐个占住，**吞吐崩塌而不是快速失败**。

**改进目标**

- 秒杀链路对下游依赖的故障表现为**快速失败 + 可观测**，而非线程堆积。

**技术选型**

**不引入 Sentinel**（见 §3 D1），全部栈内：显式超时配置 + 既有 try/catch + `SeckillMetrics`。

**核心实现思路**

1. 收紧 `order-service` → `voucher-service` 的 Feign 超时至与内部端点真实耗时匹配的量级（内部扣库存是单条 UPDATE，ms 级）。
2. 消费端已有显式 `catch (Exception) → throw RuntimeException` 触发重试（`SeckillOrderConsumer.java:146-152`），**保持**——这是"应该重试"的正确语义。
3. 增加"连续失败"观测：用既有 `SeckillMetrics` 统计 Feign 失败率，超过阈值时 WARN 告警（避免依赖 P2-1 才具备可观测性）。
4. 若后续确需真熔断，再按 §3 D1 重新决策是否引入栈内的 Sentinel Core。

**效果评估指标**

| 指标 | 目标 |
|---|---|
| Feign 调用 p99 | 收紧后显著下降 |
| voucher-service 下线时消费线程堆积 | 快速失败（不出现 5s × N 的线性堆积） |
| Feign 失败率 | 可观测，超阈值告警 |

---

### 2.5 P2-1 · 本地事件表 / 事务消息的正确形态

**问题现状**：当前是"同步发送 + 失败回滚"（`VoucherOrderServiceImpl.java:136-143`），SPEC-14 P0-2 的补偿器是**事后补救**（T + 60s 窗口），不是"事前不丢"。

**改进目标**：消息投递与本地订单状态**同事务**，消除"发送成功但从未被消费"的窗口。

**技术选型**：二选一——(a) 本地事件表 `tb_seckill_outbox`（同库事务写入 + 独立投递器 + 投递成功标记）；(b) RocketMQ 事务消息 `sendMessageInTransaction`（half message + 本地事务回查）。

**核心实现思路**

- 方案 (a) 更适合本项目：与现有 MySQL 同库，`@Transactional` 直接覆盖，无需依赖 broker 的回查接口；投递器可用现有 `@Scheduled` 模式。
- 方案 (b) 需实现 `RocketMQLocalTransactionListener` 的 `executeLocalTransaction` / `checkLocalTransaction`，并保证回查幂等。

**⚠️ 关键澄清：本项目当前"无对象可绑"**

RocketMQ 事务消息的语义是「本地 DB 事务提交 ⟺ 消息可投递」，它**必须绑定一个本地数据库事务**。而本项目生产端的实际动作是：

```
Redis Lua 预扣  →  syncSend  →  返回 orderId
```

**生产端根本没有 DB 写入**——库存在 Redis 扣（`seckill.lua:39`），订单落库发生在消费者一侧（`SeckillOrderConsumer.java:168`）。Redis 与 MySQL 之间没有事务可言，因此：

- 单上方案 (b)，**本地事务是空的**，broker 回查时只能返回无意义结果，等于没解决问题；
- 要让 (b) 成立，必须先在 order-service 生产端**引入一次 DB 写入**（写一条预扣/事件行）——那恰好就是方案 (a) 的第一步。

**结论：方案 (a) 是本项目的正确形态；方案 (b) 只有先做了 (a) 的本地表才有资格谈，二者不是平行选项，(b) 是 (a) 的上层。**

**效果评估指标**：消息丢失窗口 = 0（对比 P0-2 的 T + 60s）；投递重试次数；事务回查命中率（方案 b）。

> **这是简历里"RocketMQ 事务消息 + 本地事件表"表述唯一能成立的路径**。未实施前，该表述不得写入简历（见 SPEC-13 §4.1）。

---

### 2.6 P2-2 · 风控（黑名单 / 设备维度）

**问题现状**：全仓零风控（grep 黑名单 / 设备指纹 / 风控零命中）。

**改进目标**：秒杀入口可对已知恶意账号/设备识别并拒绝。

**技术选型**：Redis Set 黑名单 + P0-1 的规则框架（网关层）。

**核心实现思路**：网关过滤器在限流判定前增加一次黑名单 `SISMEMBER`（key `risk:blacklist:user` / `risk:blacklist:ip`）；黑名单来源先由运维手工维护，后续可对接风控规则。

**效果评估指标**：黑名单命中拦截数；误杀率；新增一次 Redis 读的 p99 开销。

---

## 3. 待决决策点（P1/P2 相关）

| # | 决策点 | 选项 A | 选项 B | 本文建议 |
|---|---|---|---|---|
| D1 | 是否为 order-service 引入 Sentinel Core 做秒杀链路熔断 | 引入（`agent-service/pom.xml:116-120` 已有先例，属栈内复用） | **不引入**，用显式超时 + try/catch + 指标 | **选 B**。理由：SPEC-03 §5.5 已明确建议"不引入 Sentinel（符合简单优先）"；且 P0-1 网关限流 + 既有 `maxReconsumeTimes=3` + DLQ 已构成足够的失败隔离。引入需先推翻既有决策，不应默默改。 |
| D4 | P1-3 Cluster 兼容是否现在做 | 现在做 | **条件触发**（计划上 Cluster 时再做） | **选 B**。当前单节点运行，改动会破坏冻结契约，收益仅体现在面试讲述。 |

> 另两个决策点（D2 时间窗位置、D3 DLQ 合流）属 P0 范围，见 [SPEC-14 §3](SPEC-14-秒杀P0稳定性加固.md)。

---

## 4. 实施步骤

> 批次编号沿用拆分前的全局编号。**A1–A3 与 B1–B4 属 P0，见 SPEC-14 §4**，本文只列 P1/P2 相关步骤。

### 批次 A（续）—— 纯增量，无契约变更

| 步 | 动作 | verify |
|---|---|---|
| A4 | P1-1 池化配置 + 阶梯压测 | 实测：输出 QPS/p99 表；压测后对账等式成立、超卖数 = 0 |
| A5 | P1-4 Feign 超时收紧 + 失败率观测 | 单测：失败率超阈值告警；实测：voucher 下线时不出现线性堆积 |

### 批次 B（续）—— 需与 P0-2 同批

| 步 | 动作 | verify |
|---|---|---|
| B5 | （可选）P1-3 Cluster 兼容 —— 与 B1 同批，两者都改 Lua key 契约 | 回归全绿；Cluster 环境脚本可执行（无 `CROSSSLOT`） |

### 批次 C —— 缓存

| 步 | 动作 | verify |
|---|---|---|
| C1 | `MultiLevelCache` 补互斥重建 + 单测 | 单测：N 并发同 key 回源 → loader 只调用 1 次 |
| C2 | voucher-service 开缓存 + `queryVoucherById` 接入 | 实测：命中率日志；**契约测试锁定 `getSeckillStock` 不走缓存** |

### 批次 D —— 深度项

| 步 | 动作 | verify |
|---|---|---|
| D1 | P2-1 本地事件表 + 投递器 | 实测：kill 消费端后重启，消息不丢 |
| D2 | P2-2 风控黑名单 | 实测：黑名单用户被拦截 |

---

## 5. 风险与测试方案

### 5.1 风险登记

| 风险 | 等级 | 触发场景 | 缓解措施 |
|---|---|---|---|
| 库存这类强一致数据被误加缓存 | 🟠 中 | 有人"顺手"给 `getSeckillStock` 加 `@Cacheable` | 写进类注释 + 契约测试断言该路径无缓存注解 |
| Lua 契约变更破坏对账 | 🟠 中 | P1-3 Cluster 改名 | 变更必须同批更新 `RedisConstants` + `SeckillKeyContractTest` + `SeckillConsistencyServiceImpl` 读写点 |
| 互斥锁退化为串行瓶颈 | 🟠 中 | 热点 key 频繁过期，锁竞争激烈 | 回源持锁时间最小化 + `cacheTtlSeconds()` 抖动已降低同刻过期概率 |
| 压测数据污染真实库存 | 🟠 中 | 压测直接打真实券 | 压测用独立券 + 独立 Redis DB/前缀；压测后必须跑对账断言 |
| P2-1 引入本地表后链路复杂度上升 | 🟡 低 | 生产端多一次 DB 写 | 明确收益是"丢失窗口 = 0"，与 P0-2 的边界需在文档中写清 |
| 黑名单误杀正常用户 | 🟡 低 | 手工维护出错 | 黑名单命中只记录不封禁的灰度期；可即时移除 |

### 5.2 测试方案

**单元测试**

- 缓存：N 并发同 key 回源 → loader 只调用 1 次；空值缓存不覆盖正常值
- Feign 超时：失败率超阈值触发告警
- 黑名单：命中 → 拦截；未命中 → 直通
- 本地事件表：写表与业务落库同事务（异常时一并回滚）

**契约测试（本项目特色，必须同步更新）**

- 新增：`getSeckillStock` 路径**不含**缓存注解
- `SeckillKeyContractTest`：P1-3 执行时同步更新 key 命名断言

**端到端实测（必须实测，不接受仅单测通过）**

| # | 场景 | 期望 |
|---|---|---|
| E4 | 阶梯压测 100/500/1000 并发 | 超卖数 = 0，对账等式成立，输出 QPS/p99 基线 |
| E6 | 停 voucher-service 后发起秒杀 | 消费线程快速失败，无 5s × N 线性堆积 |
| E7 | 热点券 key 过期瞬间并发查询 | DB 回源次数收敛，峰值连接数显著下降 |
| E8 | kill 消费端后重启（P2-1 方案 a） | 消息不丢，投递器补投成功 |
| E9 | 黑名单用户发起秒杀 | 被拦截，正常用户零误杀 |

> E1 / E2 / E3 / E5 属 P0，见 [SPEC-14 §5.2](SPEC-14-秒杀P0稳定性加固.md)。

**回归门槛**：`mvn -pl <modules> -am test` 全绿；涉及 order/voucher 的批次必须跑一次端到端。

---

## 6. 验收标准汇总

| # | 验收断言 | 类型 |
|---|---|---|
| 1 | 秒杀接口 QPS / p95 / p99 / 错误率基线产出，超卖数 = 0 | 必须实测 |
| 2 | N 并发同 key 回源，loader 只调用 1 次 | 单测 |
| 3 | 券元信息缓存命中率可观测，回源 DB QPS 下降 | 必须实测 |
| 4 | `getSeckillStock` 仍为直查 DB（契约测试断言无缓存注解） | 契约测试 |
| 5 | voucher-service 下线时消费线程快速失败，无线性堆积 | 必须实测 |
| 6 | （P1-3 执行时）Cluster 环境脚本可执行，无 `CROSSSLOT`；单机行为不变 | 必须实测 |
| 7 | （P2-1 执行时）kill 消费端后重启，消息不丢 | 必须实测 |
| 8 | 黑名单用户被拦截，正常用户零误杀 | 必须实测 |

---

## 7. 变更记录

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-10-10 | v1.0 | 由 SPEC-12 拆分为三篇之第 3 篇（优化篇）：P1-1~P2-2 详细方案 + 批次 A/B/C/D + 风险测试 + 验收汇总 |
