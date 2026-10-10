# SPEC-14 · 秒杀 P0 稳定性加固

> 编制日期：2026-10-10
> 目标读者：项目作者本人（用于项目迭代开发）
> 证据基线：`master` 工作区（含未提交改动），所有 `file:line` 均为实测阅读所得
> 本文是三篇拆分中的**第 2 篇（止血篇）**。审查结论与简历口径见 [SPEC-13](SPEC-13-秒杀模块亮点审查与简历口径.md)；P1/P2 优化见 [SPEC-15](SPEC-15-秒杀P1P2优化与深度建设.md)。

---

## 0. 文档定位与前置依赖

### 0.1 本文范围

只收录 **P0 级**（面试杀伤力 × 真实缺陷严重度 × 实施成本 综合 ROI 最高）的四项：

| 编号 | 改进项 | 维度 | 性质 | 是否触碰数据契约 |
|---|---|---|---|---|
| P0-1 | 秒杀入口双层限流与防刷 | ⑤⑥ | 补空白 | 否 |
| P0-2 | 在途订单补偿器（含 DLQ 处置） | ②④ | **真缺陷** | **是**（明细 Hash 增 `ts` 字段） |
| P0-3 | 秒杀活动时间窗校验 | ①⑥ | **真缺陷**（新发现） | 否（新增独立 key） |
| P0-4 | 秒杀入参校验加固 | ⑥ | 补空白 | 否 |

### 0.2 前置依赖（硬性）

本文所有方案默认 **SPEC-03 / SPEC-04 / SPEC-08 的缺陷已修复**。未修复前：

- SPEC-03 的库存双扣、消息静默丢失会让 P0-2 的补偿器建立在错误账本上；
- SPEC-04 的 key 拼法不一致会让补偿器读到错误的 key；
- SPEC-08 的 DLQ 链路双向断开会让 P0-2 的第 3 点（DLQ 合流）无从落地。

### 0.3 选型边界

**只用现有栈，零新依赖**：Redis + Lua / RocketMQ / Redisson / Gateway（Sa-Token）/ Jakarta Validation / Caffeine，全部已在仓库内。

### 0.4 通用约定

- `*IT` 命名的集成测试需 `-Dtest=` 显式指定。
- surefire 用户属性名是 **`-Dsurefire.failIfNoSpecifiedTests=false`**；拼错会使下游模块**静默跳过** = 假绿。
- 标注「**必须实测**」的条目不接受仅单元测试通过。

---

## 1. 改进项总览

| 编号 | 一句话目标 | 涉及模块 | 步骤编号 |
|---|---|---|---|
| P0-1 | 秒杀路径「用户 + IP」双层令牌桶限流，超限 429，故障 fail-open | gateway-service | A3 |
| P0-2 | 超时未落库的在途单 T+60s 内自动重投或安全释放；DLQ 不再是黑洞 | order-service（+ common 常量） | B1–B4 |
| P0-3 | 时间窗外秒杀请求被拒，且与对账侧活跃券口径一致 | voucher-service + order-service | A2 |
| P0-4 | 非法 voucherId 在 Controller 层被拒，不产生 Redis 调用 | order-service | A1 |

---

## 2. 改进项详细方案

> 每个方案统一给出：**问题现状 / 改进目标 / 技术选型 / 核心实现思路 / 效果评估指标**。
> 「改进目标」采用可验证句式；「效果评估指标」均为可测量量。

### 2.1 P0-1 · 秒杀入口双层限流与防刷

**问题现状**

`/voucher-order/seckill/{id}` 经网关无任何限流（`AgentRateLimitFilter.java:38` 只对 `/agent/` 生效）。Lua 之前的唯一闸门是"每人一次"，攻击者可用多账号或脚本打满入口；而 Redis 是**单线程**的，入口被打满会级联影响全站所有依赖 Redis 的接口。

**改进目标**

- 网关对秒杀路径实施「用户维度 + IP 维度」双层令牌桶限流，超限返回 HTTP 429；
- 秒杀入口的可承载 QPS 由配置显式决定，而非无限。

**技术选型**

复用栈内已有组件，**零新依赖**：

- `gateway-service/src/main/resources/limiter/token-bucket.lua`（已实现，通用）
- `gateway-service/.../limit/AgentTokenBucketLimiter.java`（已接受任意 key，**无需修改**）

**核心实现思路**

1. 新增 `RateLimitProperties`（`hmdp.rate-limit.rules[]`），每条规则含：
   `path-prefix` / `dimension(loginId|ip)` / `capacity` / `refill-per-sec` / `message`。
2. 把 `AgentRateLimitFilter` 泛化为 `PathRateLimitFilter`：`getOrder()` 保持 `-10`，按 path 前缀匹配规则表，**逐条判定，任一失败即 429**。
3. 秒杀规则示例：

```yaml
hmdp:
  rate-limit:
    rules:
      - path-prefix: /agent/
        dimension: loginId
        capacity: 10
        refill-per-sec: 10
      - path-prefix: /voucher-order/seckill/
        dimension: loginId      # 单用户 5 QPS
        capacity: 5
        refill-per-sec: 1
      - path-prefix: /voucher-order/seckill/
        dimension: ip           # 单 IP 20 QPS，防多账号
        capacity: 20
        refill-per-sec: 5
```

4. **保留**未登录/无效 token 时放行的既有行为（`AgentRateLimitFilter.java:42-51`）——后续 `SaTokenGatewayConfig` 会拒绝；在代码注释与本文档同步该取舍（SPEC-06 §1.8 已记录）。

**效果评估指标**

| 指标 | 目标 |
|---|---|
| 限流组件单次开销 p99 | < 1 ms（单次 EVAL） |
| 单用户在 1s 内的第 2 次秒杀请求 | 被 429（可构造验收） |
| 压测中**正常用户**被 429 的比例 | 0% |
| Redis 抖动时的行为 | fail-open 放行，不因限流故障阻断下单 |
| 限流命中数 | 纳入指标，可观测 |

---

### 2.2 P0-2 · 在途订单补偿器（防"预扣泄漏" + 打通 DLQ 黑洞）

**问题现状**

1. **预订泄漏**：`syncSend` 成功但消费端始终未消费（broker 重启丢内存消息、消费组长时间下线）时，`seckill:stock:{vid}` 已 `DECR`、`seckill:order:{vid}` 已 `SADD`、`seckill:order:detail:{vid}` 残留一条，但 `tb_voucher_order` **无记录**。用户看到"秒杀成功"却永无订单，且因 `SISMEMBER` 命中**无法再次购买**。
2. **对账掩盖**：现有对账只校验**聚合等式**（`SeckillConsistencyServiceImpl.java:368-381`），在途订单一进一出刚好抵消，等式仍然成立——**这条泄漏在现有监控下不可见**。
3. **DLQ 黑洞**：`SeckillOrderDLQConsumer.java:64-76` 只把失败单 `rightPush` 到 `seckill:order:pending`，**无消费者、无告警、无重放**。

**改进目标**

- 超时未落库的在途单在 T + 60s 内被自动重投或安全释放；
- `seckill:order:pending` 具备可观测与可重放能力；
- 释放操作的**误释放数为 0**。

**技术选型**（全部栈内）

- 数据源：`seckill:order:detail:{vid}` Hash（已存在，值含 voucherId/userId/orderId）
- 调度：`@Scheduled`（`SeckillConsistencyServiceImpl` 已有先例）
- 互斥：Redisson `lock:order:{orderId}` —— **刻意复用消费端同一把锁**（`SeckillOrderConsumer.java:114`），避免与正在处理的消费冲突
- 重投：`SeckillOrderProducer.sendSeckillOrderMessage`

**核心实现思路**

1. **补时间戳（唯一的数据契约改动）**：`seckill.lua:45-49` 的 `cjson.encode` 增加 `ts` 字段。Lua 沙箱无可靠 `os.time`，故由 Java 侧作为 `ARGV[4]` 传入。
   - 兼容性：消费端从**消息体**取字段，不解析 Hash 内的 JSON；新增字段为**纯增量**，不影响既有解析。
   - 该改动**必须同步**：`seckill.lua` 调用点（`VoucherOrderServiceImpl.java:104-107`）+ 契约测试。

2. **新增 `SeckillInFlightCompensator`（order-service）**：

```
@Scheduled(fixedDelay = 60000)
  └─ Redisson lock:compensate:seckill（多实例互斥）
       └─ 遍历 listActiveSeckillVoucherIds()
            └─ HGETALL seckill:order:detail:{vid}
                 └─ 对 now - ts > T(默认 120s) 的条目：
                      ① tryLock(lock:order:{orderId})   ← 与消费端同一把
                      ② selectById(orderId) 存在？ → 仅 HDEL，清理消费端崩溃残留
                      ③ 不存在 → 重投 MQ（retryCount 自增，上限 2 次）
                      ④ 重投耗尽仍不落库 → 回滚预扣：
                           INCR seckill:stock:{vid}
                           SREM seckill:order:{vid} userId
                           HDEL seckill:order:detail:{vid} orderId
                         + rightPush seckill:order:pending + 指标
```

3. **DLQ 与补偿器合流（见 §3 D3，选 B）**：`SeckillOrderDLQConsumer` 写 pending 改为**同步写一条到 `seckill:order:detail:{vid}`（`ts = now`）**，直接复用补偿器的扫描与重投路径——**少一套数据结构、少一套逻辑**。
   - 这会引入一个语义变化：明细 Hash 从"在途"扩展为"在途 + 待处置"。需在 `RedisConstants.java:39-40` 的注释里同步说明。

4. **幂等与安全前置**：所有回滚动作以「取到订单锁 + 再查一次 DB 无该 orderId」为前置条件；`INCR` 前二次确认。

**效果评估指标**

| 指标 | 目标 |
|---|---|
| 在途超时数 | 随补偿收敛至 0 |
| 自动重投成功率 | 可观测（重投成功 / 重投总数） |
| **误释放数** | **必须为 0**（释放后 DB 又出现该 orderId 即计入） |
| `seckill:order:pending` 长度 | 稳态 0；非 0 触发 WARN 告警 |
| 补偿收敛后 `redisStock == expectedRedisStock` | 恒成立（复用既有等式断言） |
| 端到端恢复时间 | ≤ T + 60s（构造"MQ 未消费"场景实测） |

---

### 2.3 P0-3 · 秒杀活动时间窗校验

> 本项是 §SPEC-13 §2.6 记录的**新缺陷**（既有 SPEC-01~11 未覆盖）。

**问题现状**

`seckill.lua:22-36` 只校验库存与重复下单，**不校验 `beginTime`/`endTime`**；`addSeckillVoucher` 创建即写库存 key 且不设 TTL（`VoucherServiceImpl.java:65-67`）；order-service 全模块 grep `beginTime|endTime` **零命中**。→ 活动开始前即可抢购，结束后仍可抢购。而对账侧 `listActiveSeckillVoucherIds`（`VoucherServiceImpl.java:141-143`）**有**时间窗过滤，两侧口径不一致。

**改进目标**

- 时间窗外的秒杀请求被拒绝，且失败文案可区分"未开始 / 已结束"。

**技术选型**

现有栈（Redis Hash + Java 侧前置判断）。

**核心实现思路**

**推荐方案（见 §3 D2，选 A：不动 Lua 契约）**：

1. `addSeckillVoucher` 在同事务内写入 `seckill:window:{vid}` Hash（字段 `begin`/`end`，值为 epoch millis），TTL 与活动周期对齐。
2. `VoucherOrderServiceImpl.seckillVoucher` 在调用 Lua **之前**做一次 `HMGET seckill:window:{vid} begin end` 判定，窗口外直接返回。
   - **为何可接受非原子**：时间窗判定不存在并发正确性问题，边界上的秒级误差不影响业务；且它不参与"In 检查—扣减"的原子序列——真正的并发闸门仍是 Lua。
   - key 缺失时按"无窗口限制"放行（向后兼容，避免历史券被误拒）。
3. `SeckillFailMessages` 新增两条文案；`SeckillMetrics` 新增计数器。

**备选方案（把判定下沉到 Lua）**：新增 `ARGV[4]=beginMs, ARGV[5]=endMs`，脚本返回新增 `4=未开始 / 5=已结束`。
- 优点：与扣减同一次 EVAL，天然原子。
- 代价：**必须同步修改脚本头注释的返回值契约**（`seckill.lua:3`）、Java 侧分支（`VoucherOrderServiceImpl.java:110-129`）、以及相关契约测试。改动面更大。

**效果评估指标**

| 指标 | 目标 |
|---|---|
| 时间窗外请求拒绝率 | 100%（开始前 / 结束后各构造一例） |
| 新增 Redis 读开销 p99 | < 0.5 ms |
| 入口判定与 `listActiveSeckillVoucherIds` 口径 | 一致（新增契约测试锁定） |
| 历史无窗口 key 的券 | 行为不变（回归全绿） |

---

### 2.4 P0-4 · 秒杀入参校验加固

**问题现状**：`VoucherOrderController.java:35-38` 的 `seckillVoucher(@PathVariable("id") Long voucherId)` 无 `@Valid`、无正数校验，可传 `0` / 负数 / 超大值直接打到 Lua。

**改进目标**：非法入参在 Controller 层被拒，不产生 Redis 调用。

**技术选型**：现有栈（Jakarta Validation，Spring Boot 自带）。

**核心实现思路**：Controller 加 `@Validated`，参数加 `@Positive`；复用现有 `@RestControllerAdvice` 返回 400。

**效果评估指标**：负数 / 0 / 超大值入参拒绝率 100%；被拒请求的 Redis 调用数为 0。

---

## 3. 待决决策点（P0 相关）

| # | 决策点 | 选项 A | 选项 B | 本文建议 |
|---|---|---|---|---|
| D2 | 时间窗校验放 Java 前置还是下沉 Lua | **Java 前置**（不动 Lua 契约） | 下沉 Lua（原子，但改返回值契约） | **选 A**。时间窗无并发正确性问题，契约改动面不值当。 |
| D3 | DLQ 失败单是"写 pending 列表"还是"写明细 Hash 合流补偿器" | 保持 pending 列表 + 新增重放器 | **写明细 Hash，复用补偿器** | **选 B**。少一套数据结构与逻辑（CLAUDE.md 第 2 条）。 |

> 另两个决策点（D1 Sentinel 熔断、D4 Cluster 兼容）属 P1/P2 范围，见 [SPEC-15 §3](SPEC-15-秒杀P1P2优化与深度建设.md)。

---

## 4. 实施步骤

> 批次编号沿用拆分前的全局编号，**A4 / A5 属 P1，移至 SPEC-15**，本文不列。

### 批次 A —— 纯增量，无契约变更（可独立上线）

| 步 | 动作 | verify |
|---|---|---|
| A1 | P0-4 入参校验（`@Validated` + `@Positive`） | 单测：负数/0/超大值 → 400，且 Redis 调用数为 0 |
| A2 | P0-3 时间窗：`addSeckillVoucher` 写 `seckill:window:{vid}`；入口前置判定 | 单测：窗口外拒绝；契约测试：入口口径 == `listActiveSeckillVoucherIds` |
| A3 | P0-1 限流：`RateLimitProperties` + `PathRateLimitFilter` 泛化 + 秒杀规则 | 实测：单用户 1s 内第 2 次请求 → 429；未登录仍由 Sa-Token 401 |

**批次 A 验证**：`mvn -pl gateway-service,order-service,voucher-service -am test` 全绿 + 一次端到端实测。

### 批次 B —— 数据契约新增字段（需回归 SPEC-04 契约测试）

| 步 | 动作 | verify |
|---|---|---|
| B1 | `seckill.lua` 增 `ts` 字段 + `ARGV[4]`；调用点同步 | 契约测试：lua 契约与 `RedisConstants` 一致；回归全绿 |
| B2 | `SeckillInFlightCompensator` + 单测（含误释放为 0 的断言） | 单测：构造残留明细 → 重投；构造已落库 → 仅清理 |
| B3 | DLQ 与补偿器合流（D3 选 B） | 实测：停 voucher-service → 投消息 → 重试耗尽 → 进 DLQ → 被补偿器接管 |
| B4 | pending 长度纳入指标 + WARN 告警 | 单测：长度 > 0 触发告警日志 |

**批次 B 实测脚本（复用既有配方）**：

```bash
docker exec hmdp-rocketmq-broker sh mqadmin sendMessage \
  -n <namesrv容器IP>:9876 -t seckill-order-topic -p '<json>'
```

> namesrv 容器 IP 的取法见 `docs/specs/端到端测试缺陷清单-2026-10-08.md` 与记忆 `e2e-env-bringup`；死信 topic 为 `%DLQ%seckill-order-consumer-group`。

---

## 5. 风险与测试方案

### 5.1 风险登记

| 风险 | 等级 | 触发场景 | 缓解措施 |
|---|---|---|---|
| **补偿器误释放导致超卖** | 🔴 高 | 消费端尚在处理但已超期；或 DB 查询读到主从延迟的旧值 | 释放前**必取 `lock:order:{orderId}`**（与消费端同一把锁）+ **二次查 DB** + 释放后置对账等式断言；指标单独统计误释放数 |
| 限流误伤正常用户 | 🟠 中 | 容量配置过小 | 先按观测 QPS 的 3x 配置；灰度期只记录不拦截 |
| 时间窗 key 缺失导致误拒 | 🟠 中 | 历史券无 `seckill:window` | key 缺失时放行（向后兼容），并有契约测试锁定 |
| Lua 契约变更破坏对账 | 🟠 中 | `ts` 字段新增 | 变更必须同批更新 `RedisConstants` + `SeckillKeyContractTest` + `SeckillConsistencyServiceImpl` 读写点 |
| 补偿器与消费端并发处理同一订单 | 🟠 中 | 调度周期与消费重试重叠 | 同一把 Redisson 锁 + DB 二次确认 |
| 限流组件故障阻断下单 | 🟡 低 | Redis 不可用 | 沿用现有 **fail-open** 语义（`AgentTokenBucketLimiter.java:46-51`） |

### 5.2 测试方案

**单元测试**

- 时间窗判定：边界（=begin、=end、begin 前 1ms、end 后 1ms）
- 补偿器：残留在途 → 重投；已落库 → 仅清理；**误释放数 = 0** 的核心断言
- 限流规则匹配：前缀命中 / 不命中 / 多规则任一超限
- 入参校验：负数 / 0 / 超大值

**契约测试（本项目特色，必须同步更新）**

- `SeckillKeyContractTest`：锁定 Lua key 与 `RedisConstants` 的一致性
- 新增：秒杀入口时间窗口径 == `listActiveSeckillVoucherIds`

**集成测试（`*IT` 命名，需 `-Dtest=` 显式指定）**

- 补偿器 × 真实 MySQL：构造残留明细 → 断言重投/释放

**端到端实测（必须实测，不接受仅单测通过）**

| # | 场景 | 期望 |
|---|---|---|
| E1 | 单用户 1s 内连续 2 次秒杀 | 第 2 次 429 |
| E2 | 活动开始前秒杀 | 拒绝，文案"活动未开始" |
| E3 | 停 voucher-service → 投 MQ → 重试 3 次耗尽 → DLQ | 被补偿器接管，≤ T+60s 收敛 |
| E5 | 造 N 并发抢同一券 | 订单数 == 库存扣减数，`uk_user_voucher` 无冲突异常逃逸 |

> E4（阶梯压测）属 P1-1，见 [SPEC-15](SPEC-15-秒杀P1P2优化与深度建设.md)。

**回归门槛**：`mvn -pl <modules> -am test` 全绿；涉及 order/voucher 的批次必须跑一次端到端。

---

## 6. 验收标准汇总

| # | 验收断言 | 类型 |
|---|---|---|
| 1 | 单用户 1s 内第 2 次秒杀返回 429，正常用户 0 误伤 | 必须实测 |
| 2 | 活动开始前 / 结束后秒杀均被拒，且与 `listActiveSeckillVoucherIds` 口径一致 | 必须实测 |
| 3 | 非法 voucherId（0 / 负数 / 超大值）返回 400，**Redis 调用数为 0** | 单测 |
| 4 | 停 voucher-service 制造消费失败 → 进 DLQ → 被补偿器接管，≤ T+60s 收敛 | 必须实测 |
| 5 | 补偿器**误释放数 = 0**；收敛后 `redisStock == expectedRedisStock` | 必须实测 |
| 6 | N 并发抢同一券：订单数 == 库存扣减数，超卖数 = 0 | 必须实测 |
| 7 | Redis 不可用时限流 fail-open，下单不被阻断 | 单测 + 实测 |

---

## 7. 实施修正（2026-10-10 实现前实测复核）

> 本节由实现阶段的代码实测复核产出，**修正 §2 中与当前工作区真实代码不符的表述**。
> 实现以本节为准；§2 保留原始方案以便追溯。

| # | 位置 | 原文表述 | 实测事实 | 修正 |
|---|---|---|---|---|
| M1 | §2.1 技术选型 | `AgentTokenBucketLimiter`「已接受任意 key，**无需修改**」 | 该类把 `capacity`/`refillPerSec` 作为构造期 `@Value` 固定注入（`AgentTokenBucketLimiter.java:31-37`），一个 bean 只能装一套参数；而 §2.1 的 yaml 需要 agent 10/10、秒杀 loginId 5/1、秒杀 ip 20/5 三套 | 增加 `tryAcquire(key, capacity, refillPerSec)` 重载；旧单参方法委托构造期默认值，`/agent/` 行为不变。`token-bucket.lua` 本就以 ARGV 接收这两项，零脚本改动 |
| M2 | §2.3 核心实现思路 1 | window key「TTL 与活动周期对齐」 | 与同节「key 缺失时按无窗口限制**放行**」叠加 → TTL 在 `endTime` 到期后 key 消失，活动**结束后反而放行**，与 P0-3 要修的缺陷同构 | `TTL = max(60s, secondsUntil(endTime) + 24h)`；`beginTime`/`endTime` 为 null 时不写该 key |
| M3 | §2.4 核心实现思路 | 「复用现有 `@RestControllerAdvice` 返回 400」 | order-service `pom.xml` **无** `spring-boot-starter-validation`（仅 agent/shop 有）；`SeckillExceptionHandler` 与 `GlobalExceptionHandler` 均只把 `Exception` 映射为 500，无 `ConstraintViolationException` 映射 → 非法入参会返回 **500** | order-service pom 增加 `spring-boot-starter-validation`；`SeckillExceptionHandler` 新增 `ConstraintViolationException → 400` |
| M4 | §4 批次 B1 verify | 「回归全绿」 | `SeckillVoucherServiceTest.java:55` 的桩为 `execute(any(), anyList(), any(), any(), any())`（3 个 ARGV）；新增 `ARGV[4]` 后不匹配 → 返回 null → 6 个用例集体走 key 缺失分支 | B1 必须同批更新该桩为 4 个 ARGV 匹配器 |
| M5 | §2.2 核心实现思路 3 | DLQ「同步写一条到 `seckill:order:detail:{vid}`（`ts = now`）」 | 若 `retryCount` 重置为 0，则「补偿器重投 → 消费再失败 → 再入 DLQ → 又重置」构成**无限循环**，§2.2「在途超时数随补偿收敛至 0」不可达 | DLQ 落明细时**继承该 orderId 已有条目的 `retryCount` 并 +1**，写回 `ts = now`。最多 2 轮后补偿器走「重投耗尽 → 回滚释放」，链路终止 |
| M6 | §2.2 核心实现思路 2 | 「retryCount 自增，上限 2 次」未指明存储位置 | 明细 Hash 的 JSON 在 B1 中仅新增 `ts` | `retryCount` 与 `ts` **同批写入明细 Hash 的 JSON**。故 §0.1 表中「唯一的数据契约改动」应表述为「明细 Hash 的 JSON 新增 `ts` 与 `retryCount` 两个纯增量字段」 |
| M7 | §2.2 核心实现思路 4 | 释放「回滚预扣三件套」未定义释放后如何处置**迟到消息** | 释放只回滚 Redis，**撤不回**已投递的 MQ 消息：补偿器重投的消息可能仍在 broker 排队（消费端长时间宕机后恢复即触发），DLQ 也会把已释放的单回写进明细并把 `retryCount` 重置为 1（M5 的继承在「明细已被释放删除」时失效）→ 补偿器再走完 2 轮重投后**二次释放**，每轮 INCR 一次 → 库存被反复凭空回补（无限超卖）；消费端也会在一份已回滚的预扣上重新建单（`uk_user_voucher` 拦不住：该用户此时无任何行） | 释放时写入**释放墓碑** `seckill:released:{orderId}`（TTL 24h）。消费端见到即 ACK 丢弃（`seckill.mq.consume.released`）；补偿器见到即只清理明细、绝不再回滚（走 CLEANED 分支）。墓碑必须在回滚**之前**写入，否则回滚中途崩溃会导致下一轮二次 INCR |
| M8 | §2.2 效果评估指标 / §6 验收 4 | 「端到端恢复时间 ≤ T + 60s」 | 与 §7 M5「最多 2 轮后…回滚释放」自相矛盾：完整收敛 = (2 轮重投 + 1 次首判) × 各自的 120s 超时窗口 + 60s 扫描周期 ≈ T + 7min。60s 是**发现延迟**（`@Scheduled(fixedDelay = 60000)`），不是完整收敛时间 | 「≤ T + 60s」口径收敛为**发现延迟**：在途单进入超时状态后 ≤ 60s 被本轮扫描发现并执行首个动作（重投或释放）；完整收敛 = T + (max-resend + 1) × timeout-seconds + 扫描周期。对外表述一律按后者 |

**M5/M6 合并后的明细 Hash 值契约**（`seckill:order:detail:{vid}` 的 field 值）：

```json
{"voucherId":"123","userId":"7","orderId":"9001","ts":"1760000000000","retryCount":0}
```

消费端**不解析**该 JSON（从 MQ 消息体取字段），因此新增字段对既有链路为纯增量、无解析风险。

---

## 8. 变更记录

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-10-10 | v1.0 | 由 SPEC-12 拆分为三篇之第 2 篇（止血篇）：P0-1~P0-4 详细方案 + 批次 A/B + 风险测试 + 验收汇总 |
| 2026-10-10 | v1.1 | 新增 §7 实施修正：M1~M6 六处实现前实测复核（限流器参数化、window TTL、400 映射依赖、测试桩同步、DLQ retryCount 继承、retryCount 存储位置） |
| 2026-10-10 | v1.2 | 新增 §7 M7/M8：释放墓碑（防释放后消息复活导致库存反复回补）、收敛时间口径澄清（60s 为发现延迟，非完整收敛） |
