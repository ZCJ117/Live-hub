# SPEC-15 秒杀 P1/P2 优化与深度建设 · 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 SPEC-15 落地秒杀 P1/P2 五项改进（P1-1 池化基线、P1-2 缓存互斥+券元信息、P1-4 依赖保护、P2-1 本地事件表、P2-2 风控黑名单），产出可运行代码与可执行验证手册。

**Architecture:** 全部使用既有栈（Redis+Lua / RocketMQ / Caffeine / HikariCP / Lettuce / JMeter），零新依赖。改动集中在 `common`（缓存组件）、`order-service`（秒杀生产端 + 指标）、`voucher-service`（券查询缓存）、`gateway-service`（风控过滤器）四个模块。不触碰 `seckill.lua`、`SeckillKeyContractTest`、对账读写点与任何既有表结构。

**Tech Stack:** Java 21 · Spring Boot 3.1.12 · Spring Cloud Gateway · MyBatis Plus 3.5.6 · Redisson · Caffeine · RocketMQ 4.9.4 · JUnit 5 + Mockito · JMeter

**Spec:** `docs/specs/SPEC-15-秒杀P1P2优化与深度建设.md`

## Global Constraints

- **P1-3 不做**（spec §3 D4 选 B）：不修改 `seckill.lua`、不修改 `RedisConstants` 中已冻结的秒杀 key 前缀、不修改 `SeckillKeyContractTest`、不修改 `SeckillConsistencyServiceImpl` 的 key 读写点。
- **P2-1 形态 = D1-b**：行内投递（保留 `syncSend`）+ 定时补投。**不改变**用户侧成功/失败语义，`SeckillFailMessages.MQ_SEND_FAILED` 契约保持（SPEC-03 §9 A8）。
- **P1-2 锁实现 = `StringRedisTemplate` SETNX 单飞**，不引入 `RedissonClient` 到 `MultiLevelCache`；`shop-service` 配置零改动。
- **不改数据库既有表结构**：P2-1 只允许 `CREATE TABLE IF NOT EXISTS tb_seckill_outbox`（纯新增表）。
- **不改既有接口签名**：`VoucherOrderServiceImpl.seckillVoucher(Long)`、`MultiLevelCacheFactory.create(String, Type)`、`VoucherController.queryVoucherById(Long)` 等公开签名一律保持。
- **回归口径**：`*IT` 集成测试需 `-Dtest=` 显式指定；surefire 关闭"无匹配测试即失败"的用户属性名必须拼作 **`-Dsurefire.failIfNoSpecifiedTests=false`**（拼错会让下游模块静默跳过 = 假绿）。
- **注释语言与风格**：中文注释，`file:line` 式证据引用，与既有代码一致；每个"反直觉的取舍"必须写明理由。
- 工作目录 `D:\hm-dianping`，分支 `master`。命令在 Git Bash 下执行（正斜杠路径）。

---

## 文件结构总览

| 模块 | 文件 | 动作 | 职责 |
|---|---|---|---|
| order-service | `src/main/resources/application.yaml` | 改 | 池化参数、Feign 超时、outbox 补投间隔 |
| order-service | `src/test/java/com/hmdp/order/config/PoolAndFeignConfigContractTest.java` | 建 | 锁定池化/超时配置真的写进 yaml |
| order-service | `src/main/java/com/hmdp/order/metrics/SeckillMetrics.java` | 改 | 新增 Feign 计数与 outbox 计数 |
| order-service | `src/main/java/com/hmdp/order/metrics/FeignFailureRateMonitor.java` | 建 | Feign 失败率滑动窗口 + 超阈值告警 |
| order-service | `src/test/java/com/hmdp/order/metrics/FeignFailureRateMonitorTest.java` | 建 | 单测 |
| order-service | `src/main/java/com/hmdp/order/mq/SeckillOrderConsumer.java` | 改 | 在扣库存调用点记录成功/失败 |
| order-service | `src/test/java/com/hmdp/order/mq/SeckillOrderConsumerTest.java` | 改 | 补断言 |
| order-service | `src/main/java/com/hmdp/order/entity/SeckillOutbox.java` | 建 | 本地事件行实体 |
| order-service | `src/main/java/com/hmdp/order/mapper/SeckillOutboxMapper.java` | 建 | Mapper |
| order-service | `src/main/java/com/hmdp/order/service/impl/VoucherOrderServiceImpl.java` | 改 | 热路径落库 + 投递标记 |
| order-service | `src/main/java/com/hmdp/order/service/impl/SeckillOutboxDeliverer.java` | 建 | 定时补投器 |
| order-service | `src/test/java/com/hmdp/order/service/impl/SeckillVoucherServiceTest.java` | 改 | 落库/删除/标记顺序断言 |
| order-service | `src/test/java/com/hmdp/order/service/impl/SeckillOutboxDelivererTest.java` | 建 | 补投器单测 |
| order-service | `src/test/java/com/hmdp/order/SeckillSchedulingContractTest.java` | 改 | 补投器必须真的被注册 |
| order-service | `src/main/java/com/hmdp/order/service/impl/SeckillConsistencyServiceImpl.java` | 改 | 仅类 Javadoc：库存读禁止缓存 |
| common | `src/main/java/com/hmdp/cache/CacheRebuildLock.java` | 建 | 回源互斥锁接口（可测接缝） |
| common | `src/main/java/com/hmdp/cache/RedisCacheRebuildLock.java` | 建 | SETNX 实现 |
| common | `src/test/java/com/hmdp/cache/RedisCacheRebuildLockTest.java` | 建 | 单测（含 fail-open） |
| common | `src/main/java/com/hmdp/cache/MultiLevelCache.java` | 改 | 回源加锁 + 双检 |
| common | `src/main/java/com/hmdp/cache/MultiLevelCacheFactory.java` | 改 | 装配锁 |
| common | `src/test/java/com/hmdp/cache/MultiLevelCacheTest.java` | 改 | 既有 11 例适配 + 新增并发单飞 |
| common | `src/main/java/com/hmdp/utils/RedisConstants.java` | 改 | 新增 3 个键常量 |
| voucher-service | `src/main/java/com/hmdp/voucher/service/VoucherCacheService.java` | 建 | 券元信息二级缓存 |
| voucher-service | `src/main/java/com/hmdp/voucher/controller/VoucherController.java` | 改 | 查询走缓存 / 写后失效 |
| voucher-service | `src/main/resources/application.yaml` | 改 | `hmdp.cache.enabled: true` + 池化 |
| voucher-service | `src/test/java/com/hmdp/voucher/SeckillStockNoCacheContractTest.java` | 建 | 库存强一致读契约 |
| gateway-service | `src/main/java/com/hmdp/gateway/config/RiskBlacklistProperties.java` | 建 | 黑名单配置 |
| gateway-service | `src/main/java/com/hmdp/gateway/filter/RiskBlacklistFilter.java` | 建 | 黑名单过滤器 |
| gateway-service | `src/main/resources/application.yaml` | 改 | 黑名单路径前缀 |
| gateway-service | `src/test/java/com/hmdp/gateway/filter/RiskBlacklistFilterTest.java` | 建 | 单测 |
| sql | `sql/spec15-seckill-outbox.sql` | 建 | 建表 DDL |
| docs | `docs/loadtest/SPEC-15-P1-1-秒杀压测手册.md` | 建 | 压测操作手册 |
| docs | `docs/loadtest/seckill-baseline.jmx` | 建 | JMeter 计划模板 |
| docs | `docs/loadtest/SPEC-15-验证手册.md` | 建 | E4/E6/E7/E8/E9 实测步骤 |

---

## Task 1: P1-1 池化配置 + 配置契约测试

**Files:**
- Modify: `order-service/src/main/resources/application.yaml`
- Modify: `voucher-service/src/main/resources/application.yaml`
- Create: `order-service/src/test/java/com/hmdp/order/config/PoolAndFeignConfigContractTest.java`

**Interfaces:**
- Consumes: 无
- Produces: 无代码接口；产出配置键 `server.tomcat.threads.max`、`spring.datasource.hikari.maximum-pool-size`、`spring.data.redis.lettuce.pool.max-active`

**背景**：`SeckillMetrics` 已存在 21 个计数器，覆盖 spec §2.1 要求的全部秒杀计数（请求/成功/失败/库存不足/重复下单/MQ 发送失败/消费成功/DLQ），**本任务不需要改任何 Java 业务代码**。

- [ ] **Step 1: 写失败的配置契约测试**

创建 `order-service/src/test/java/com/hmdp/order/config/PoolAndFeignConfigContractTest.java`：

```java
package com.hmdp.order.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 池化与依赖保护配置契约（SPEC-15 P1-1 / P1-4）
 *
 * <p>为什么用测试锁住 yaml：这些参数是"补空白"类改动，没有业务行为可断言。
 * 而仓库存在一条真实风险——{@code bootstrap.yaml} 里
 * {@code spring.config.import: optional:nacos:order-service.yaml}，Nacos 上的同名
 * data-id 会**覆盖**本地 application.yaml。本地文件被删/被覆盖时若不红，等于没做。
 *
 * <p>本测试只保证"本地文件里确实写了这些键"，不保证运行时生效（那需要实测，见验证手册）。
 */
class PoolAndFeignConfigContractTest {

    private static Properties loadApplicationYaml() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yaml"));
        return factory.getObject();
    }

    @Test
    void 显式声明Tomcat线程上限() {
        assertEquals("200", loadApplicationYaml().getProperty("server.tomcat.threads.max"),
                "SPEC-15 P1-1：必须显式声明 tomcat 线程上限，消除「未配置」歧义");
        assertEquals("200", loadApplicationYaml().getProperty("server.tomcat.accept-count"));
    }

    @Test
    void 显式声明Hikari连接池上限() {
        Properties p = loadApplicationYaml();
        assertEquals("20", p.getProperty("spring.datasource.hikari.maximum-pool-size"),
                "SPEC-15 P1-1：Hikari 默认 10 是秒杀链路的已知瓶颈");
        assertEquals("10", p.getProperty("spring.datasource.hikari.minimum-idle"));
        assertEquals("3000", p.getProperty("spring.datasource.hikari.connection-timeout"));
    }

    @Test
    void Redis连接池上调() {
        assertEquals("50", loadApplicationYaml().getProperty("spring.data.redis.lettuce.pool.max-active"),
                "SPEC-15 P1-1：原值 10 会在高并发下成为 Lettuce 排队点");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl order-service -am test -Dtest=PoolAndFeignConfigContractTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL —— `expected: <200> but was: <null>`（tomcat 键不存在）

- [ ] **Step 3: 改 order-service application.yaml**

在 `server:` 段（`port`/`address` 之后）追加：

```yaml
server:
  port: 8084 # 服务端口，用于接收HTTP请求
  address: 127.0.0.1 # 仅绑定回环，业务端口不发布到公网（SPEC-06 §5.2）
  # Tomcat 线程池显式化（SPEC-15 P1-1）：默认值同为 200，显式声明是为消除
  # 「到底是没配还是配了 200」的歧义，并给阶梯压测一个可调的旋钮
  tomcat:
    threads:
      max: 200
    accept-count: 200
```

在 `spring.datasource:` 段末尾追加：

```yaml
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver # 数据库驱动类名，MySQL 8.x 使用 com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://${MYSQL_HOST:127.0.0.1}:${MYSQL_PORT:3306}/hmdp?useSSL=false&serverTimezone=Asia/Shanghai # 数据库连接URL，格式：jdbc:mysql://ip:port/数据库名?参数
    username: root # 数据库用户名
    password: ${MYSQL_PASSWORD:} # 数据库密码
    # 连接池显式化（SPEC-15 P1-1）：Hikari 默认 maximum-pool-size=10，
    # 在秒杀入口的高并发下是首要排队点；connection-timeout 压到 3s 使
    # 池耗尽时**快速失败**而非默认 30s 挂住 Tomcat 线程
    hikari:
      maximum-pool-size: 20
      minimum-idle: 10
      connection-timeout: 3000
```

把 redis lettuce pool 的 `max-active: 10` 改为 `50`、`max-idle: 10` 改为 `20`：

```yaml
    lettuce:
      pool:
        max-active: 50 # 最大活跃连接数（SPEC-15 P1-1：原 10 为压测拐点首要嫌疑）
        max-idle: 20 # 最大空闲连接数
        min-idle: 10 # 最小空闲连接数
        time-between-eviction-runs: 10s # 连接池空闲连接检测周期
```

> `commons-pool2` 已在 `order-service/pom.xml:38` 声明 —— 上述 Lettuce 池参数真实生效，不是空转。

- [ ] **Step 4: 运行 order-service 配置测试确认通过**

Run: `mvn -pl order-service -am test -Dtest=PoolAndFeignConfigContractTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 5: 对 voucher-service 做同样的 yaml 改动**

在 `voucher-service/src/main/resources/application.yaml` 中：

1. `server:` 段追加 `tomcat.threads.max: 200` + `accept-count: 200`（注释同 Step 3）
2. `spring.datasource:` 段追加 `hikari.maximum-pool-size: 20` / `minimum-idle: 10` / `connection-timeout: 3000`
3. lettuce pool：`max-active: 50` / `max-idle: 20` / `min-idle: 10`
   （注意：现有文件 `min-idle: 1` 行尾有一个多余的字面量 `8907`，属既有脏数据；本任务**只改数值**，不清理该行 —— 见 CLAUDE.md §3「不要改进邻近代码」）

- [ ] **Step 6: 启动测试 + 提交**

Run: `mvn -pl order-service,voucher-service -am test -Dtest=PoolAndFeignConfigContractTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

```bash
git add order-service/src/main/resources/application.yaml \
        order-service/src/test/java/com/hmdp/order/config/PoolAndFeignConfigContractTest.java \
        voucher-service/src/main/resources/application.yaml
git commit -m "feat(seckill): P1-1 池化配置显式化（tomcat/hikari/lettuce）+ 配置契约测试"
```

---

## Task 2: P1-4 Feign 失败率观测（指标 + 监视器）

**Files:**
- Modify: `order-service/src/main/java/com/hmdp/order/metrics/SeckillMetrics.java`
- Create: `order-service/src/main/java/com/hmdp/order/metrics/FeignFailureRateMonitor.java`
- Create: `order-service/src/test/java/com/hmdp/order/metrics/FeignFailureRateMonitorTest.java`

**Interfaces:**
- Consumes: `SeckillMetrics`（既有 `@Component`）
- Produces:
  - `SeckillMetrics.incrementFeignCallSuccess()` / `SeckillMetrics.incrementFeignCallFail()` → `void`
  - `FeignFailureRateMonitor`（`@Component`，构造参数 `SeckillMetrics`）→ `void record(boolean success)`

- [ ] **Step 1: 写失败率监视器的失败测试**

创建 `order-service/src/test/java/com/hmdp/order/metrics/FeignFailureRateMonitorTest.java`：

```java
package com.hmdp.order.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Feign 失败率观测（SPEC-15 P1-4）
 *
 * <p>为什么必须单测：告警是纯副作用，没有返回值可断言；一旦阈值判断被写反，
 * 运行时唯一的表现在日志里，回归全绿也不会红 —— 等于不设防。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeignFailureRateMonitorTest {

    @Mock private SeckillMetrics seckillMetrics;

    private FeignFailureRateMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new FeignFailureRateMonitor(seckillMetrics);
    }

    @Test
    void 每次结果都计数到指标() {
        monitor.record(true);
        monitor.record(false);

        verify(seckillMetrics).incrementFeignCallSuccess();
        verify(seckillMetrics).incrementFeignCallFail();
    }

    @Test
    void 样本不足时不告警() {
        // 9 次失败 < MIN_SAMPLES(10)：失败率再高也不该告警
        for (int i = 0; i < 9; i++) {
            monitor.record(false);
        }

        assertFalse(monitor.isAlerted(), "样本不足时必须保持未告警");
    }

    @Test
    void 失败率达到阈值时告警() {
        for (int i = 0; i < 10; i++) {
            monitor.record(false);
        }

        assertTrue(monitor.isAlerted(), "10/10 失败率 100% ≥ 50%，必须告警");
    }

    @Test
    void 一次成功之后重新武装_持续故障会再次告警() {
        for (int i = 0; i < 10; i++) {
            monitor.record(false);
        }
        assertTrue(monitor.isAlerted());
        // 告警后应已解除武装（否则同一轮持续故障会每来一次失败就刷一条 WARN）
        assertFalse(monitor.isArmed(), "告警后必须解除武装，防止刷屏");

        // 恢复：一次成功应重新武装，从而下一轮持续故障能再次告警（否则一次告警后就永久静音）
        monitor.record(true);
        assertFalse(monitor.isAlerted());
        assertTrue(monitor.isArmed());
    }

    @Test
    void 告警后同一窗口内不重复告警() {
        for (int i = 0; i < 10; i++) {
            monitor.record(false);
        }
        assertTrue(monitor.isAlerted());
        assertEquals(1, monitor.alertCount(), "第 10 次失败应触发且仅触发一次告警");

        // 再失败 5 次：窗口仍是满的失败，但不该再次触发告警（只是保持告警中）
        for (int i = 0; i < 5; i++) {
            monitor.record(false);
        }

        // 用 alertCount 而不是 isArmed 来断言"节流"：
        // isArmed()==false 是恒真的（去掉实现的 !armed 早退后它照样是 false，因为告警分支会再置一次），
        // 断言它等于没断言。只有累计次数能证伪"每来一次失败就刷一条 WARN"。
        assertEquals(1, monitor.alertCount(), "同一告警态内不得重复告警，否则会刷屏");
        assertFalse(monitor.isArmed(), "未出现成功前不得重新武装");
    }

    @Test
    void 失败率低于阈值时不告警() {
        // 窗口内 20 次：6 失败 / 14 成功 = 30% < 50%
        for (int i = 0; i < 14; i++) {
            monitor.record(true);
        }
        for (int i = 0; i < 6; i++) {
            monitor.record(false);
        }

        assertFalse(monitor.isAlerted(), "失败率 30% 未达阈值，不得告警");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl order-service -am test -Dtest=FeignFailureRateMonitorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— `cannot find symbol: class FeignFailureRateMonitor`

- [ ] **Step 3: 给 SeckillMetrics 加 Feign 计数器**

在 `order-service/.../metrics/SeckillMetrics.java` 中：

字段区（`private Counter mqConsumeReleasedCounter;` 之后）追加：

```java
    private Counter feignCallSuccessCounter;
    private Counter feignCallFailCounter;
```

`init()` 内、`mqConsumeReleasedCounter` 注册之后追加：

```java
        // SPEC-15 P1-4：秒杀链路对下游（voucher-service）的调用质量。
        // 这是"依赖劣化"唯一能从监控上看见的入口——消费线程只会表现为重试变慢，
        // 不主动计数的话，第 3 个 5s 超时和第 300 个在指标上没有任何区别。
        feignCallSuccessCounter = Counter.builder("seckill.feign.call")
                .description("秒杀链路 Feign 调用结果计数（SPEC-15 P1-4）")
                .tag("result", "success")
                .register(meterRegistry);

        feignCallFailCounter = Counter.builder("seckill.feign.call")
                .description("秒杀链路 Feign 调用结果计数（SPEC-15 P1-4）")
                .tag("result", "fail")
                .register(meterRegistry);
```

文件末尾（`incrementMqConsumeReleased()` 之后、类的 `}` 之前）追加：

```java
    /** Feign 调用成功一次（SPEC-15 P1-4） */
    public void incrementFeignCallSuccess() {
        feignCallSuccessCounter.increment();
    }

    /** Feign 调用失败一次（SPEC-15 P1-4） */
    public void incrementFeignCallFail() {
        feignCallFailCounter.increment();
    }
```

- [ ] **Step 4: 实现 FeignFailureRateMonitor**

创建 `order-service/src/main/java/com/hmdp/order/metrics/FeignFailureRateMonitor.java`：

```java
package com.hmdp.order.metrics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Feign 失败率观测（SPEC-15 P1-4 §2.4 第 3 点）
 *
 * <p><b>它补的是哪个洞</b>：秒杀消费端对 {@code voucher-service} 的每次调用都带
 * 5s(readTimeout) 兜底，下游劣化时线程被逐个占满、吞吐崩塌，但**没有任何信号**
 * 能把"下游变慢"与"下游正常但重试变多"区分开。本类用最近 {@value #WINDOW_SIZE} 次
 * 调用的失败率做滑动窗口，跨过阈值时打一次 WARN 并落到指标。
 *
 * <p><b>为什么不用 Redisson / Sentinel</b>：SPEC-15 §3 D1 明确选 B（不引入 Sentinel），
 * 显式超时 + 既有 try/catch + 指标足以构成失败隔离。本类不含任何熔断动作，
 * 只做观测——真正的失败隔离由 `maxReconsumeTimes=3` + DLQ 承担。
 *
 * <p><b>告警节流</b>：进入告警态后不再重复告警，直到出现一次成功（{@code armed} 重新置位）。
 * 否则下游持续宕机的每一轮重试都会刷一条 WARN，日志反而失去信号价值。
 */
@Component
@Slf4j
public class FeignFailureRateMonitor {

    /** 滑动窗口大小：只看最近 N 次调用的结果 */
    static final int WINDOW_SIZE = 20;

    /** 触发告警所需的最小样本数：样本太少时失败率没有统计意义 */
    static final int MIN_SAMPLES = 10;

    /** 失败率告警阈值 */
    static final double FAILURE_RATE_THRESHOLD = 0.5d;

    private final SeckillMetrics seckillMetrics;

    private final Deque<Boolean> window = new ArrayDeque<>(WINDOW_SIZE);

    /** 是否处于「可以告警」状态：初始为真，告警后置假，出现一次成功后重新置真 */
    private boolean armed = true;

    /** 是否已进入告警态（仅用于测试断言） */
    private boolean alerted = false;

    /**
     * 累计告警次数（测试与排障用）。
     *
     * <p>它存在的唯一理由：让「同一窗口只告警一次」这条断言**可证伪**。
     * 只断言 {@code isArmed()==false} 是恒真的——即使去掉实现里的 {@code !armed} 早退，
     * 后续失败仍会重新进入告警分支把 armed 再置假，断言照样通过，节流是否真的生效无从判断。
     */
    private int alertCount = 0;

    public FeignFailureRateMonitor(SeckillMetrics seckillMetrics) {
        this.seckillMetrics = seckillMetrics;
    }

    /**
     * 记录一次 Feign 调用结果。
     *
     * <p>{@code synchronized}：消费端是多线程的，窗口与告警态必须作为一个整体读写。
     * 竞争极低（每单两次），不构成瓶颈。
     */
    public synchronized void record(boolean success) {
        if (success) {
            seckillMetrics.incrementFeignCallSuccess();
            armed = true;
            alerted = false;
        } else {
            seckillMetrics.incrementFeignCallFail();
        }

        if (window.size() == WINDOW_SIZE) {
            window.removeFirst();
        }
        window.addLast(success);

        if (success || !armed || window.size() < MIN_SAMPLES) {
            return;
        }

        long failures = window.stream().filter(ok -> !ok).count();
        double rate = (double) failures / window.size();
        if (rate >= FAILURE_RATE_THRESHOLD) {
            armed = false;
            alerted = true;
            alertCount++;
            log.warn("Feign 失败率超阈值: {}/{} = {}%（阈值 {}%）——voucher-service 可能已劣化，"
                            + "消费端将快速失败而非线性堆积。明细指标见 seckill.feign.call",
                    failures, window.size(), Math.round(rate * 100),
                    Math.round(FAILURE_RATE_THRESHOLD * 100));
        }
    }

    /** 当前是否处于告警态（测试与排障用） */
    public synchronized boolean isAlerted() {
        return alerted;
    }

    /** 是否仍可再次告警（测试用） */
    public synchronized boolean isArmed() {
        return armed;
    }

    /** 累计告警次数（测试用）：节流是否真的生效由它证明 */
    public synchronized int alertCount() {
        return alertCount;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -pl order-service -am test -Dtest="FeignFailureRateMonitorTest,SeckillMetricsTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（`SeckillMetricsTest` 一并跑，确认新增计数器没破坏既有 `init()`）

- [ ] **Step 6: 提交**

```bash
git add order-service/src/main/java/com/hmdp/order/metrics/SeckillMetrics.java \
        order-service/src/main/java/com/hmdp/order/metrics/FeignFailureRateMonitor.java \
        order-service/src/test/java/com/hmdp/order/metrics/FeignFailureRateMonitorTest.java
git commit -m "feat(seckill): P1-4 Feign 失败率滑动窗口观测 + 指标计数"
```

---

## Task 3: P1-4 Feign 超时收紧 + 消费端接线

**Files:**
- Modify: `order-service/src/main/resources/application.yaml`
- Modify: `order-service/src/main/java/com/hmdp/order/mq/SeckillOrderConsumer.java`
- Modify: `order-service/src/test/java/com/hmdp/order/mq/SeckillOrderConsumerTest.java`
- Modify: `order-service/src/test/java/com/hmdp/order/config/PoolAndFeignConfigContractTest.java`

**Interfaces:**
- Consumes: `FeignFailureRateMonitor.record(boolean)`
- Produces: 无新接口

- [ ] **Step 1: 给配置契约测试加 Feign 超时断言**

在 `PoolAndFeignConfigContractTest` 末尾追加：

```java
    @Test
    void Feign对voucher服务收紧超时_不影响default() {
        Properties p = loadApplicationYaml();
        // SPEC-15 P1-4：内部扣库存是单条 UPDATE，ms 级；5s 超时会让消费线程逐个被占住
        assertEquals("1000", p.getProperty("feign.client.config.voucher-service.connectTimeout"));
        assertEquals("2000", p.getProperty("feign.client.config.voucher-service.readTimeout"));
        // default 保持 5s：不收紧其它（未来新增的）Feign client，避免误伤
        assertEquals("5000", p.getProperty("feign.client.config.default.connectTimeout"));
        assertEquals("5000", p.getProperty("feign.client.config.default.readTimeout"));
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl order-service -am test -Dtest=PoolAndFeignConfigContractTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL —— `expected: <1000> but was: <null>`

- [ ] **Step 3: 改 application.yaml 的 feign 段**

把现有：

```yaml
# Feign配置
feign:
  client:
    config:
      default:
        connectTimeout: 5000
        readTimeout: 5000
```

改为：

```yaml
# Feign配置
feign:
  client:
    config:
      default:
        connectTimeout: 5000
        readTimeout: 5000
      # SPEC-15 P1-4：只收紧 voucher-service 这一个 client。
      # 依据——order-service 对它的调用全部是内部端点（扣库存是单条 UPDATE、
      # 查库存/查券是单条 SELECT），真实耗时 ms 级；5s 的超时意味着
      # voucher-service 劣化时消费线程会被**逐个占满 5 秒**，吞吐崩塌而不是快速失败。
      # 不用 default 收紧是因为 default 会影响未来新增的其它 client（如查用户），
      # 那些调用的耗时特征不同，不该被一起改了。
      voucher-service:
        connectTimeout: 1000
        readTimeout: 2000
```

- [ ] **Step 4: 运行配置测试确认通过**

Run: `mvn -pl order-service -am test -Dtest=PoolAndFeignConfigContractTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 5: 写消费端接线的失败测试**

在 `SeckillOrderConsumerTest` 中，`@Mock private SeckillMetrics seckillMetrics;` 之后追加：

```java
    @Mock private FeignFailureRateMonitor feignFailureRateMonitor;
```

（并在 import 区追加 `import com.hmdp.order.metrics.FeignFailureRateMonitor;`）

在类末尾追加两个用例：

```java
    @Test
    void 扣库存成功时记录Feign成功样本() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(voucherOrderMapper.selectById(9001L)).thenReturn(null);
        when(voucherOrderMapper.selectCount(any())).thenReturn(0L);
        when(voucherOrderMapper.insert(any())).thenReturn(1);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(voucherFeignClient.deductStock(1L, 9001L)).thenReturn(Result.ok());

        consumer.handleOrder(msg, 0);

        verify(feignFailureRateMonitor).record(true);
    }

    @Test
    void 扣库存异常时记录Feign失败样本() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(voucherOrderMapper.selectById(9001L)).thenReturn(null);
        when(voucherOrderMapper.selectCount(any())).thenReturn(0L);
        when(voucherFeignClient.deductStock(1L, 9001L))
                .thenThrow(new RuntimeException("voucher-service 不可达"));

        assertThrows(RuntimeException.class, () -> consumer.handleOrder(msg, 0));

        verify(feignFailureRateMonitor).record(false);
    }
```

- [ ] **Step 6: 运行确认失败**

Run: `mvn -pl order-service -am test -Dtest=SeckillOrderConsumerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL —— `Wanted but not invoked: feignFailureRateMonitor.record(true)`

- [ ] **Step 7: 在消费端接线**

在 `SeckillOrderConsumer` 中，`@Resource private SeckillMetrics seckillMetrics;` 之后追加：

```java
    @Resource
    private FeignFailureRateMonitor feignFailureRateMonitor;
```

（import 区追加 `import com.hmdp.order.metrics.FeignFailureRateMonitor;`）

把 `handleOrder` 里这段：

```java
                Result deductResult;
                try {
                    deductResult = voucherFeignClient.deductStock(voucherId, orderId);
                } catch (Exception e) {
                    // 内部端点不可达/网络异常：属于"应该重试"，不能当作业务失败丢弃（SPEC-03 §1.7）
                    log.error("调用库存扣减失败，触发重试: voucherId={}, orderId={}", voucherId, orderId, e);
                    throw new RuntimeException("库存服务调用失败", e);
                }
```

改为：

```java
                Result deductResult;
                try {
                    deductResult = voucherFeignClient.deductStock(voucherId, orderId);
                    // SPEC-15 P1-4：只做观测，不改变控制流——
                    // 下面 catch 里的 throw 仍然是"应该重试"的正确语义（SPEC-03 §1.7）
                    feignFailureRateMonitor.record(true);
                } catch (Exception e) {
                    // 内部端点不可达/网络异常：属于"应该重试"，不能当作业务失败丢弃（SPEC-03 §1.7）
                    feignFailureRateMonitor.record(false);
                    log.error("调用库存扣减失败，触发重试: voucherId={}, orderId={}", voucherId, orderId, e);
                    throw new RuntimeException("库存服务调用失败", e);
                }
```

- [ ] **Step 8: 运行测试确认通过**

Run: `mvn -pl order-service -am test -Dtest="SeckillOrderConsumerTest,PoolAndFeignConfigContractTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 9: 提交**

```bash
git add order-service/src/main/resources/application.yaml \
        order-service/src/main/java/com/hmdp/order/mq/SeckillOrderConsumer.java \
        order-service/src/test/java/com/hmdp/order/mq/SeckillOrderConsumerTest.java \
        order-service/src/test/java/com/hmdp/order/config/PoolAndFeignConfigContractTest.java
git commit -m "feat(seckill): P1-4 Feign 超时收紧至 1s/2s + 消费端失败率接线"
```

---

## Task 4: P1-2 回源互斥锁（可注入接缝 + SETNX 实现）

**Files:**
- Create: `common/src/main/java/com/hmdp/cache/CacheRebuildLock.java`
- Create: `common/src/main/java/com/hmdp/cache/RedisCacheRebuildLock.java`
- Create: `common/src/test/java/com/hmdp/cache/RedisCacheRebuildLockTest.java`

**Interfaces:**
- Produces:
  - `interface CacheRebuildLock { boolean tryLock(String key); void unlock(String key); }`
  - `class RedisCacheRebuildLock implements CacheRebuildLock`，构造 `RedisCacheRebuildLock(StringRedisTemplate)`
  - 包级常量 `RedisCacheRebuildLock.LOCK_KEY_PREFIX = "lock:cache:rebuild:"`

**为什么抽接口**：`MultiLevelCacheTest` 用 mock 的 `StringRedisTemplate`，mock 不出 `SETNX` 的原子语义；没有接缝的话「N 并发 → loader 只调用 1 次」这条 SPEC-15 验收 2 无法确定性回归。

- [ ] **Step 1: 写锁实现的失败测试**

创建 `common/src/test/java/com/hmdp/cache/RedisCacheRebuildLockTest.java`：

```java
package com.hmdp.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 缓存重建锁（SPEC-15 P1-2 §2.2）
 *
 * <p>关键边界是 fail-open：Redis 抖动时不能把"拿不到锁"变成"回源失败"，
 * 那会把一次缓存故障放大成接口 500。此分支无运行时可见行为，必须单测锁定。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisCacheRebuildLockTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private RedisCacheRebuildLock lock;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lock = new RedisCacheRebuildLock(redisTemplate);
    }

    @Test
    void SETNX成功即抢到锁_键带统一前缀() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        assertNotNull(lock.tryLock("cache:shop:1"), "SETNX 成功必须返回令牌");

        // TTL 断言写字面量 3 而不是 RedisCacheRebuildLock.LOCK_TTL_SECONDS：
        // 引常量等于"断言常量等于自己"，把 TTL 从 3 改成 300 用例照样绿，等于不设防
        // （同 MultiLevelCacheTest 对 1800/2100 的处理）。
        verify(valueOperations).setIfAbsent(eq("lock:cache:rebuild:cache:shop:1"),
                anyString(), eq(3L), eq(TimeUnit.SECONDS));
    }

    @Test
    void SETNX失败表示他人持锁() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);

        assertNull(lock.tryLock("cache:shop:1"), "未抢到锁必须返回 null，调用方据此等待重读");
    }

    @Test
    void Redis异常时fail_open放行回源() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertNotNull(lock.tryLock("cache:shop:1"),
                "Redis 抖动时必须 fail-open：退化为无锁回源（改动前的行为），而不是让回源失败");
    }

    @Test
    void 释放走Lua比对脚本而非裸DEL_且携带自己的令牌() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        String token = lock.tryLock("cache:shop:1");

        lock.unlock("cache:shop:1", token);

        // 必须用比对脚本而不是裸 DEL，且必须把**本次拿到的令牌**原样传下去：
        // 否则会把锁 TTL 到期后他人重新抢到的锁删掉（互斥被削到近似失效）。
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), eq(token));
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void 令牌为null时释放是空操作() {
        lock.unlock("cache:shop:1", null);

        verifyNoInteractions(redisTemplate);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl common -am test -Dtest=RedisCacheRebuildLockTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— `cannot find symbol: class RedisCacheRebuildLock`

- [ ] **Step 3: 实现接口**

创建 `common/src/main/java/com/hmdp/cache/CacheRebuildLock.java`：

```java
package com.hmdp.cache;

/**
 * 缓存回源互斥锁（SPEC-15 P1-2 §2.2）
 *
 * <p>存在意义只有一个：让 {@link MultiLevelCache} 的「N 并发同 key 回源 → loader 只调用 1 次」
 * 可以离线确定性回归。若直接用 {@code StringRedisTemplate} 内联实现，测试只能去 mock
 * {@code SETNX} 的原子语义（Mockito 做不到），这条验收项就只剩人工压测可验。
 */
public interface CacheRebuildLock {

    /**
     * 尝试获取 {@code key} 对应的重建锁。
     *
     * @return 非 null = 已获得许可，**必须**把该令牌原样回传给 {@link #unlock(String, String)}；
     *         null = 他人持有，调用方应短暂等待后重读缓存
     */
    String tryLock(String key);

    /**
     * 释放锁。
     *
     * <p>令牌必须由 {@link #tryLock} 返回并由**调用方**持有，而不是由锁实例内部按 key 记账。
     * 后者在"持锁者回源超过 TTL、锁已过期并被他人重抢"的时序下会让先前的持锁者删掉
     * **他人的锁**，把互斥削到近似失效 —— 见实现类的 @implNote。
     *
     * @param token {@link #tryLock} 的返回值；为 null 时为空操作
     */
    void unlock(String key, String token);
}
```

- [ ] **Step 4: 实现 SETNX 锁**

创建 `common/src/main/java/com/hmdp/cache/RedisCacheRebuildLock.java`：

```java
package com.hmdp.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis SETNX 的缓存重建锁（SPEC-15 P1-2 §2.2）
 *
 * <p><b>为什么不是 Redisson tryLock</b>：SPEC-15 §2.2 原文写的是 Redisson，
 * 但 {@code MultiLevelCache} 的构造链只有 {@code StringRedisTemplate}，
 * 而 {@code shop-service} 已经使用本组件却没有 {@code hmdp.redisson.enabled=true}——
 * 按字面做会让 shop / voucher 两个服务都被迫多装配一个 Redisson 连接池。
 * 回源互斥不需要可重入、不需要看门狗续期、不需要排队，SETNX + 比对释放已完全够用。
 *
 * <p><b>fail-open</b>：Redis 抖动时 {@link #tryLock} 返回 true，调用方退化为无锁回源
 * （即本改动之前的行为）。缓存组件故障不该把业务回源变成失败（设计文档 §7）。
 */
@Slf4j
public class RedisCacheRebuildLock implements CacheRebuildLock {

    /** 锁键前缀：与限流、订单锁隔离，排障时一眼可辨 */
    static final String LOCK_KEY_PREFIX = "lock:cache:rebuild:";

    /**
     * 锁 TTL（秒）。
     *
     * <p>必须显著大于一次 DB 回源耗时（否则锁在回源中途过期，互斥失效），
     * 又不能长到持锁者崩溃后长时间阻塞重建。
     */
    static final long LOCK_TTL_SECONDS = 3L;

    /**
     * 释放脚本：比对 value 再删，避免误删他人重新抢到的锁。
     *
     * <p>不能用裸 {@code DEL}：若本线程持锁期间回源超过 TTL，锁已自动过期并被他人抢到，
     * 裸 DEL 会把**别人的锁**删掉，互斥当场失效 —— 这正是"锁误删"的经典形态。
     */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT;

    static {
        RELEASE_SCRIPT = new DefaultRedisScript<>();
        RELEASE_SCRIPT.setScriptText(
                "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end");
        RELEASE_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;

    public RedisCacheRebuildLock(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * @implNote 令牌是**返回值**而不是实例字段。曾经写成
     * {@code Map<String,String> heldTokens}（按 key 索引），在多线程下有如下时序问题：
     * <pre>
     * T0   A: SETNX(L,tokA)=true  heldTokens[key]=tokA，开始回源
     * T0+  B: SETNX(L,tokB)=false → 未拿到，进入等待重读
     * T3   L 因 TTL 过期消失（A 回源超过 3s，DB 抖动时完全现实）
     * T3+  B: 重试 SETNX(L,tokB)=true → heldTokens[key]=tokB（覆盖 tokA）
     * T4   A: unlock(key) 取到的是 tokB → 比对通过 → 删掉了 B 的锁
     * </pre>
     * 后果是紧接着的 C 能立刻抢锁并与 B 并发回源：互斥被削弱到近似无锁，
     * 而"N 并发同 key → loader 只调用 1 次"（SPEC-15 验收 2）正是本类存在的全部意义。
     * 把令牌交给调用方持有后，A 的 unlock 携带 tokA、比对失败返回 0，不会误删 B 的锁。
     */
    @Override
    public String tryLock(String key) {
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(LOCK_KEY_PREFIX + key, token, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(acquired) ? token : null;
        } catch (Exception e) {
            // Redis 抖动：无法互斥时退化为"人人可回源"（本改动之前的行为），
            // 而不是让回源失败——缓存组件故障不该阻断业务（设计文档 §7）。
            //
            // 注意语义：这里返回的令牌代表「允许回源」而非「已持有互斥」，
            // 调用方**不能**把它当成互斥保证。
            log.warn("缓存重建锁获取异常，降级为无锁回源。key={}", key, e);
            return token;
        }
    }

    @Override
    public void unlock(String key, String token) {
        if (token == null) {
            return;
        }
        try {
            stringRedisTemplate.execute(RELEASE_SCRIPT, List.of(LOCK_KEY_PREFIX + key), token);
        } catch (Exception e) {
            // 释放失败不阻断读路径：TTL 会自动兜底
            log.warn("缓存重建锁释放失败（TTL 会兜底）。key={}", key, e);
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -pl common -am test -Dtest=RedisCacheRebuildLockTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add common/src/main/java/com/hmdp/cache/CacheRebuildLock.java \
        common/src/main/java/com/hmdp/cache/RedisCacheRebuildLock.java \
        common/src/test/java/com/hmdp/cache/RedisCacheRebuildLockTest.java
git commit -m "feat(cache): P1-2 新增缓存回源互斥锁（SETNX + 比对释放 + fail-open）"
```

---

## Task 5: P1-2 MultiLevelCache 回源加锁 + 并发单飞测试

**Files:**
- Modify: `common/src/main/java/com/hmdp/cache/MultiLevelCache.java`
- Modify: `common/src/main/java/com/hmdp/cache/MultiLevelCacheFactory.java`
- Modify: `common/src/test/java/com/hmdp/cache/MultiLevelCacheTest.java`

**Interfaces:**
- Consumes: `CacheRebuildLock.tryLock(String)` / `unlock(String)`
- Produces: `MultiLevelCache` 包级构造新增第 5 个参数 `CacheRebuildLock`（**紧邻 properties 之前**）
  `MultiLevelCache(String name, Type valueType, StringRedisTemplate stringRedisTemplate, CacheInvalidationPublisher publisher, CacheRebuildLock rebuildLock, MultiLevelCacheProperties properties)`
- **不变**：`MultiLevelCacheFactory.create(String, Type)` 签名不变

- [ ] **Step 1: 给既有测试加上锁桩，并写并发单飞测试**

在 `MultiLevelCacheTest.setUp()` 内、`when(redisTemplate.opsForValue()).thenReturn(valueOperations);` 之后追加：

```java
        // 既有用例走 factory.create(...)，内部装配的是 RedisCacheRebuildLock。
        // 不桩 SETNX 的话它会返回 null → 判定为"没抢到锁" → 每个用例白等 500ms 超时，
        // 且走的是降级分支而不是待测路径。桩成"总能抢到"，让既有用例维持改动前的语义。
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
```

在类末尾追加（import 区补齐 `java.util.ArrayList`、`java.util.Map`、`java.util.concurrent.ConcurrentHashMap`、`java.util.concurrent.CountDownLatch`、`java.util.concurrent.ExecutorService`、`java.util.concurrent.Executors`、`java.util.concurrent.Future`、`java.util.concurrent.atomic.AtomicBoolean`）：

```java
    /**
     * U12：N 并发同 key 回源 → loader 只调用 1 次（SPEC-15 §6 验收 2）。
     *
     * <p>实现方式：用真实语义的进程内 L2（ConcurrentHashMap）+ 一个原子布尔锁，
     * 因为 Mockito 无法复现 SETNX 的原子性，而这条验收的全部意义就在"原子性收敛"。
     */
    @Test
    void 并发同key回源时loader只调用一次() throws Exception {
        Map<String, String> redisStore = new ConcurrentHashMap<>();
        when(valueOperations.get(anyString())).thenAnswer(inv -> redisStore.get(inv.getArgument(0)));
        doAnswer(inv -> {
            redisStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        CacheRebuildLock singleFlight = new CacheRebuildLock() {
            private final AtomicBoolean held = new AtomicBoolean();

            @Override
            public String tryLock(String key) {
                return held.compareAndSet(false, true) ? "test-token" : null;
            }

            @Override
            public void unlock(String key, String token) {
                held.set(false);
            }
        };

        String key = CACHE_SHOP_KEY + 202L;
        MultiLevelCache<Shop> cache = new MultiLevelCache<>(
                "single-flight", Shop.class, redisTemplate, publisher, singleFlight, defaultProperties());

        AtomicInteger loads = new AtomicInteger();
        int concurrency = 10;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<Shop>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return cache.get(key, () -> {
                        loads.incrementAndGet();
                        // 持锁回源耗时 100ms：等待侧会在 50ms/100ms 两次重读 L2
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return new Shop().setId(202L).setName("并发回源");
                    });
                }));
            }
            start.countDown();

            for (Future<Shop> f : futures) {
                assertEquals("并发回源", f.get(10, TimeUnit.SECONDS).getName());
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, loads.get(),
                "并发同 key 回源必须收敛为 1 次 loader 调用，实际=" + loads.get());
    }

    /** U13：抢不到锁且等待超时 → 降级为无锁回源（保可用性，不把缓存失效放大成接口失败） */
    @Test
    void 等待锁超时后降级为无锁回源() {
        String key = CACHE_SHOP_KEY + 203L;
        when(valueOperations.get(key)).thenReturn(null);
        CacheRebuildLock neverAcquires = new CacheRebuildLock() {
            @Override
            public String tryLock(String k) {
                return null;
            }

            @Override
            public void unlock(String k, String token) {
            }
        };
        MultiLevelCache<Shop> cache = new MultiLevelCache<>(
                "degraded", Shop.class, redisTemplate, publisher, neverAcquires, defaultProperties());

        Shop shop = cache.get(key, () -> new Shop().setId(203L).setName("降级回源"));

        assertEquals("降级回源", shop.getName(), "持锁者异常时必须降级回源，而不是返回 null");
    }
```

- [ ] **Step 2: 运行确认编译失败**

Run: `mvn -pl common -am test -Dtest=MultiLevelCacheTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— `MultiLevelCache` 构造器参数不匹配

- [ ] **Step 3: 改造 MultiLevelCache**

把 `common/src/main/java/com/hmdp/cache/MultiLevelCache.java` 的字段区改为（在 `publisher` 之后插入）：

```java
    private final String name;
    private final Type valueType;
    private final StringRedisTemplate stringRedisTemplate;
    private final CacheInvalidationPublisher publisher;
    private final CacheRebuildLock rebuildLock;
    private final Cache<String, V> l1;

    /** 抢锁失败后的重读间隔（毫秒） */
    private static final long LOCK_WAIT_STEP_MILLIS = 50L;

    /** 抢锁失败后的最长等待（毫秒）：超时即降级为无锁回源 */
    private static final long MAX_LOCK_WAIT_MILLIS = 500L;
```

构造器改为：

```java
    MultiLevelCache(String name, Type valueType, StringRedisTemplate stringRedisTemplate,
                    CacheInvalidationPublisher publisher, CacheRebuildLock rebuildLock,
                    MultiLevelCacheProperties properties) {
        this.name = name;
        this.valueType = valueType;
        this.stringRedisTemplate = stringRedisTemplate;
        this.publisher = publisher;
        this.rebuildLock = rebuildLock;
        this.l1 = Caffeine.newBuilder()
                .maximumSize(properties.getL1MaxSize())
                .expireAfterWrite(properties.getL1Ttl())
                .recordStats()
                .build();
    }
```

把 `get` 方法整体替换为下面四个成员（`get` + `readL2` + `loadFromSource` + 内部 record）：

```java
    /**
     * 读：L1 → L2 → 【互斥】loader → 回填 L2 与 L1。
     *
     * <p><b>互斥的目的（SPEC-15 P1-2）</b>：L2 未命中时若所有线程一起回源，
     * 一个热点 key 过期瞬间就能把 DB 连接打满（缓存击穿）。同一 key 的并发回源
     * 收敛为 1 次。
     *
     * <p><b>两条降级路径</b>（都不改变"读不到就回源"的可用性）：
     * 抢不到锁 → 短暂重读 L2；等满 {@value #MAX_LOCK_WAIT_MILLIS}ms 仍未命中 → 无锁回源。
     * 后者覆盖"持锁者崩溃/回源极慢"的场景，宁可多打一次 DB 也不让接口失败。
     *
     * @return 值；loader 返回 null 时写空值标记并返回 null
     */
    public V get(String key, Supplier<V> loader) {
        V local = l1.getIfPresent(key);
        if (local != null) {
            return local;
        }

        L2Result<V> fromL2 = readL2(key);
        if (fromL2.present()) {
            return fromL2.value();
        }

        String rebuildToken = rebuildLock.tryLock(key);
        if (rebuildToken != null) {
            try {
                // 双检：等锁期间可能已有其它线程/实例完成了重建（含写入空值标记）
                L2Result<V> doubleChecked = readL2(key);
                if (doubleChecked.present()) {
                    return doubleChecked.value();
                }
                return loadFromSource(key, loader);
            } finally {
                // 令牌必须原样回传：锁实现靠它做"只删自己的锁"的比对
                rebuildLock.unlock(key, rebuildToken);
            }
        }

        for (long waited = 0; waited < MAX_LOCK_WAIT_MILLIS; waited += LOCK_WAIT_STEP_MILLIS) {
            sleepQuietly(LOCK_WAIT_STEP_MILLIS);
            L2Result<V> retried = readL2(key);
            if (retried.present()) {
                return retried.value();
            }
        }

        log.warn("缓存重建锁等待超时，降级为无锁回源。key={}", key);
        return loadFromSource(key, loader);
    }

    /** L2 读取结论：{@code present=true} 表示 L2 已有结论（命中空值标记时 {@code value=null}） */
    private record L2Result<V>(boolean present, V value) {
    }

    private L2Result<V> readL2(String key) {
        String json = redisGet(key);
        if (StrUtil.isNotBlank(json)) {
            V value = decode(json, key);
            if (value != null) {
                l1.put(key, value);
                return new L2Result<>(true, value);
            }
            // 反序列化失败：按未命中处理，落到回源分支
            return new L2Result<>(false, null);
        }
        if (json != null) {
            // 命中空值标记：DB 已确认不存在，直接返回；标记不进 L1（设计文档 §5.1）
            return new L2Result<>(true, null);
        }
        return new L2Result<>(false, null);
    }

    private V loadFromSource(String key, Supplier<V> loader) {
        V loaded = loader.get();
        if (loaded == null) {
            redisSet(key, "", CACHE_NULL_TTL);
            return null;
        }
        redisSet(key, JSONUtil.toJsonStr(loaded), cacheTtlSeconds());
        l1.put(key, loaded);
        return loaded;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
```

- [ ] **Step 4: 装配锁到工厂**

`common/src/main/java/com/hmdp/cache/MultiLevelCacheFactory.java`：字段区追加

```java
    private final CacheRebuildLock rebuildLock;
```

构造器追加赋值（在 `this.properties = properties;` 之前）：

```java
        // 锁实例在工厂内共享：令牌表按业务 key 索引，多个缓存共用一份不会串号
        this.rebuildLock = new RedisCacheRebuildLock(stringRedisTemplate);
```

`create` 方法改为：

```java
    public <V> MultiLevelCache<V> create(String name, Type valueType) {
        MultiLevelCache<V> cache =
                new MultiLevelCache<>(name, valueType, stringRedisTemplate, publisher, rebuildLock, properties);
        registry.register(cache);
        return cache;
    }
```

- [ ] **Step 5: 运行整个 common 缓存测试确认通过**

Run: `mvn -pl common -am test -Dtest="MultiLevelCacheTest,RedisCacheRebuildLockTest,MultiLevelCacheAutoConfigurationTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（U1–U13 全绿）

- [ ] **Step 6: 跨模块回归（shop-service 也在用该组件）**

Run: `mvn -pl shop-service -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS —— `ShopCacheService` 无改动，行为不变

- [ ] **Step 7: 提交**

```bash
git add common/src/main/java/com/hmdp/cache/MultiLevelCache.java \
        common/src/main/java/com/hmdp/cache/MultiLevelCacheFactory.java \
        common/src/test/java/com/hmdp/cache/MultiLevelCacheTest.java
git commit -m "feat(cache): P1-2 回源加互斥锁，并发同 key 收敛为单次 loader"
```

---

## Task 6: P1-2 券元信息接入二级缓存

**Files:**
- Modify: `common/src/main/java/com/hmdp/utils/RedisConstants.java`
- Create: `voucher-service/src/main/java/com/hmdp/voucher/service/VoucherCacheService.java`
- Modify: `voucher-service/src/main/java/com/hmdp/voucher/controller/VoucherController.java`
- Modify: `voucher-service/src/main/resources/application.yaml`
- Create: `voucher-service/src/test/java/com/hmdp/voucher/service/VoucherCacheServiceTest.java`

**Interfaces:**
- Consumes: `MultiLevelCacheFactory.create(String, Type)`、`MultiLevelCache.get(String, Supplier)`、`MultiLevelCache.evict(String)`
- Produces:
  - `RedisConstants.CACHE_VOUCHER_KEY = "cache:voucher:"`
  - `VoucherCacheService.getById(Long) → Voucher`（不存在返回 `null`）、`VoucherCacheService.evict(Long) → void`

- [ ] **Step 1: 加键常量**

在 `common/src/main/java/com/hmdp/utils/RedisConstants.java` 的 `CACHE_SHOP_KEY` 之后追加：

```java
    /** 券元信息缓存键前缀（SPEC-15 P1-2 C2）。与 {@link #CACHE_SHOP_KEY} 同风格，纯新增 */
    public static final String CACHE_VOUCHER_KEY = "cache:voucher:";
```

> 该常量属于**缓存命名空间**，不是 SPEC-04 §5.2 冻结的秒杀 key 契约，不触碰 `SeckillKeyContractTest`。

- [ ] **Step 2: 写缓存服务测试**

创建 `voucher-service/src/test/java/com/hmdp/voucher/service/VoucherCacheServiceTest.java`：

```java
package com.hmdp.voucher.service;

import com.hmdp.cache.MultiLevelCache;
import com.hmdp.cache.MultiLevelCacheFactory;
import com.hmdp.entity.Voucher;
import com.hmdp.voucher.mapper.VoucherMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static com.hmdp.utils.RedisConstants.CACHE_VOUCHER_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 券元信息二级缓存（SPEC-15 P1-2 C2）
 *
 * <p>断言的重点是"键拼法与 SecondLevelCache 契约一致"与"写路径会失效"——
 * 这两点出错时业务表现正常（只是缓存永不命中 / 读到旧值），无任何日志信号。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VoucherCacheServiceTest {

    @Mock private MultiLevelCacheFactory cacheFactory;
    @Mock private MultiLevelCache<Voucher> cache;
    @Mock private VoucherMapper voucherMapper;

    private VoucherCacheService service;

    @BeforeEach
    void setUp() {
        // 必须写类型见证 <Voucher>：create 是泛型方法 <V> MultiLevelCache<V> create(...)，
        // 不加见证时 Mockito 会把 V 推断成 Object，与 @Mock MultiLevelCache<Voucher> 不可赋值。
        when(cacheFactory.<Voucher>create(eq("voucher"), eq(Voucher.class))).thenReturn(cache);
        service = new VoucherCacheService(cacheFactory, voucherMapper);
    }

    @Test
    void getById按统一键前缀走缓存() {
        Voucher expected = new Voucher().setId(12L);
        when(cache.get(eq(CACHE_VOUCHER_KEY + 12L), any())).thenReturn(expected);

        assertSame(expected, service.getById(12L));
        verify(voucherMapper, never()).selectById(anyLong());
    }

    @Test
    void evict使用同一个键() {
        service.evict(12L);

        verify(cache).evict(CACHE_VOUCHER_KEY + 12L);
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `mvn -pl voucher-service -am test -Dtest=VoucherCacheServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— `cannot find symbol: class VoucherCacheService`

- [ ] **Step 4: 实现 VoucherCacheService**

创建 `voucher-service/src/main/java/com/hmdp/voucher/service/VoucherCacheService.java`：

```java
package com.hmdp.voucher.service;

import com.hmdp.cache.MultiLevelCache;
import com.hmdp.cache.MultiLevelCacheFactory;
import com.hmdp.entity.Voucher;
import com.hmdp.voucher.mapper.VoucherMapper;
import org.springframework.stereotype.Service;

import static com.hmdp.utils.RedisConstants.CACHE_VOUCHER_KEY;

/**
 * 券元信息缓存（SPEC-15 P1-2 C2；结构对齐 shop-service 的 {@code ShopCacheService}）
 *
 * <p>只缓存 {@link Voucher} **元信息**（标题、面额、使用规则），供
 * {@code GET /voucher/{id}} 与订单联查使用。
 *
 * <p><b>明确排除库存</b>：秒杀库存的读取路径是
 * {@code VoucherServiceImpl#getSeckillStock}（对账用的强一致读），
 * 本类与它没有任何交集 —— 给库存加缓存会直接破坏 SPEC-13 §2.2 的对账等式。
 */
@Service
public class VoucherCacheService {

    private final MultiLevelCache<Voucher> cache;
    private final VoucherMapper voucherMapper;

    public VoucherCacheService(MultiLevelCacheFactory cacheFactory, VoucherMapper voucherMapper) {
        this.cache = cacheFactory.create("voucher", Voucher.class);
        this.voucherMapper = voucherMapper;
    }

    /**
     * 读取券元信息，未命中则回源 DB 并回填缓存。
     *
     * @return 券实体；不存在时返回 {@code null}（同时留下空值标记防穿透）
     */
    public Voucher getById(Long id) {
        return cache.get(CACHE_VOUCHER_KEY + id, () -> voucherMapper.selectById(id));
    }

    /** 写路径失效：券新增/变更后调用，打断可能已存在的空值标记或旧值 */
    public void evict(Long id) {
        cache.evict(CACHE_VOUCHER_KEY + id);
    }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `mvn -pl voucher-service -am test -Dtest=VoucherCacheServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 6: 改 Controller 走缓存**

在 `voucher-service/.../controller/VoucherController.java` 中：

import 区追加 `import com.hmdp.voucher.service.VoucherCacheService;`

字段区、`private IVoucherService voucherService;` 之后追加：

```java
    @Resource
    private VoucherCacheService voucherCacheService;
```

三个方法分别改为（**只改这三处，`queryVoucherOfShop` / `queryVouchersByIds` 不动**）：

```java
    @PostMapping
    @SaCheckRole("admin")
    public Result addVoucher(@RequestBody Voucher voucher) {
        voucherService.save(voucher);
        // 写后失效（SPEC-15 P1-2 C2）：新券可能在创建前被 GET /voucher/{id} 查过，
        // 留下一枚 60 秒的空值标记，不失效会让新券在窗口内"查不到"
        voucherCacheService.evict(voucher.getId());
        return Result.ok(voucher.getId());
    }

    @PostMapping("seckill")
    @SaCheckRole("admin")
    public Result addSeckillVoucher(@RequestBody Voucher voucher) {
        voucherService.addSeckillVoucher(voucher);
        voucherCacheService.evict(voucher.getId());
        return Result.ok(voucher.getId());
    }

    @GetMapping("/{id}")
    public Result queryVoucherById(@PathVariable("id") Long id) {
        // SPEC-15 P1-2 C2：券元信息走 L1(Caffeine) + L2(Redis)，不再每次直查 DB
        Voucher voucher = voucherCacheService.getById(id);
        if (voucher == null) {
            return Result.fail("券不存在");
        }
        return Result.ok(voucher);
    }
```

- [ ] **Step 7: 打开 voucher-service 的缓存开关与池化**

在 `voucher-service/src/main/resources/application.yaml` 的 `hmdp:` 段追加：

```yaml
hmdp:
  internal-token: ${INTERNAL_TOKEN:}
  admin-user-ids: ${ADMIN_USER_IDS:}
  # 二级缓存（SPEC-15 P1-2 C2）：券元信息走 L1 Caffeine + L2 Redis + 失效广播。
  # 默认 false，不显式打开则 MultiLevelCacheFactory 无 Bean，启动直接失败。
  cache:
    enabled: true
```

> Task 1 Step 5 已顺带完成本文件的 tomcat/hikari/lettuce 池化改动。

- [ ] **Step 8: 运行 voucher-service 全量测试**

Run: `mvn -pl voucher-service -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 9: 提交**

```bash
git add common/src/main/java/com/hmdp/utils/RedisConstants.java \
        voucher-service/src/main/java/com/hmdp/voucher/service/VoucherCacheService.java \
        voucher-service/src/main/java/com/hmdp/voucher/controller/VoucherController.java \
        voucher-service/src/main/resources/application.yaml \
        voucher-service/src/test/java/com/hmdp/voucher/service/VoucherCacheServiceTest.java
git commit -m "feat(voucher): P1-2 券元信息接入 L1+L2 二级缓存，写路径失效"
```

---

## Task 7: P1-2 库存强一致读的契约（注释 + 契约测试）

**Files:**
- Modify: `order-service/src/main/java/com/hmdp/order/service/impl/SeckillConsistencyServiceImpl.java`
- Create: `voucher-service/src/test/java/com/hmdp/voucher/SeckillStockNoCacheContractTest.java`

**Interfaces:**
- Consumes: `VoucherServiceImpl`、`VoucherCacheService`、`MultiLevelCache`（均为既有类型）
- Produces: 无

- [ ] **Step 1: 写契约测试**

创建 `voucher-service/src/test/java/com/hmdp/voucher/SeckillStockNoCacheContractTest.java`：

```java
package com.hmdp.voucher;

import com.hmdp.cache.MultiLevelCache;
import com.hmdp.voucher.service.IVoucherService;
import com.hmdp.voucher.service.VoucherCacheService;
import com.hmdp.voucher.service.impl.VoucherServiceImpl;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 秒杀库存必须直查 DB（SPEC-15 §5.1 风险登记首条 / §5.2 契约测试 / §6 验收 4）
 *
 * <p><b>风险场景</b>：有人"顺手"把 {@code getSeckillStock} 也接上缓存。它是
 * SPEC-04 §5.5 对账的**权威读数**（Redis 库存与 DB 库存的比对基准），一旦被缓存，
 * 对账等式 {@code Redis := DB − 在途} 立刻失去意义，而且表现是"对账永远通过"——
 * 比没有对账更危险。
 *
 * <p>本测试用反射而非源码扫描：注解/字段是编译期事实，不会被格式改动打偏。
 */
class SeckillStockNoCacheContractTest {

    @Test
    void 券服务实现类不得持有任何缓存依赖() {
        for (Field field : VoucherServiceImpl.class.getDeclaredFields()) {
            Class<?> type = field.getType();
            assertFalse(MultiLevelCache.class.isAssignableFrom(type),
                    "VoucherServiceImpl 不得持有 MultiLevelCache 字段：" + field.getName()
                            + "（库存读会因此被缓存，破坏对账等式）");
            assertFalse(VoucherCacheService.class.isAssignableFrom(type),
                    "VoucherServiceImpl 不得注入 VoucherCacheService：" + field.getName());
        }
    }

    @Test
    void getSeckillStock方法上不得出现缓存类注解() throws Exception {
        assertNoCacheAnnotation(VoucherServiceImpl.class);
        // 必须**同时**查接口：Spring 解析缓存注解走 AnnotatedElementUtils 的合并查找，
        // 写在中 `IVoucherService#getSeckillStock` 上与写在实现类上同样生效；
        // 而 VoucherServiceImpl.class.getMethod(...).getAnnotations() 只返回该实现类自己的
        // 方法对象，看不到接口声明上的注解 —— 只查实现类会留下一个假绿缺口。
        assertNoCacheAnnotation(IVoucherService.class);
    }

    private static void assertNoCacheAnnotation(Class<?> declaringType) throws Exception {
        Method method = declaringType.getMethod("getSeckillStock", Long.class);
        for (Annotation annotation : method.getAnnotations()) {
            String name = annotation.annotationType().getSimpleName();
            assertFalse(name.contains("Cache"),
                    declaringType.getSimpleName() + "#getSeckillStock 上出现缓存注解 " + name
                            + "：库存读必须直查 DB");
        }
    }
}
```

- [ ] **Step 2: 运行确认通过（防回归测试，第 1 次运行即可绿）**

Run: `mvn -pl voucher-service -am test -Dtest=SeckillStockNoCacheContractTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

> 该测试锁定的是**当前正确状态**，不是驱动新功能。若它变红，说明有人给库存路径接了缓存 —— 这正是要拦的。

- [ ] **Step 3: 在 order-service 对账服务上加类注释**

在 `order-service/src/main/java/com/hmdp/order/service/impl/SeckillConsistencyServiceImpl.java` 的类 Javadoc 末尾（`<ol>` 列表之后、`*/` 之前）追加：

```java
 *
 * <p><b>【不得缓存】</b>本类所依赖的 {@code getSeckillStock}（内部端点
 * {@code GET /internal/voucher/seckill/{id}/stock}）是**强一致读**：它是
 * SPEC-04 §5.5 对账的权威基线，与 Redis 库存逐条比对。
 * 给它加任何缓存（{@code @Cacheable}、{@code MultiLevelCache} 等）会让比对基准
 * 变成快照，对账将**永远通过**——比没有对账更危险。
 * 该约束由 {@code voucher-service} 的
 * {@code SeckillStockNoCacheContractTest} 反射锁定（SPEC-15 P1-2 §2.2 第 3 点）。
```

- [ ] **Step 4: 确认注释改动不影响编译**

Run: `mvn -pl order-service -am test -Dtest=SeckillConsistencyServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add order-service/src/main/java/com/hmdp/order/service/impl/SeckillConsistencyServiceImpl.java \
        voucher-service/src/test/java/com/hmdp/voucher/SeckillStockNoCacheContractTest.java
git commit -m "test(voucher): P1-2 锁定秒杀库存直查 DB 的契约（无缓存字段/注解）"
```

---

## Task 8: P2-1 本地事件表 + 实体 + Mapper

**Files:**
- Create: `sql/spec15-seckill-outbox.sql`
- Create: `order-service/src/main/java/com/hmdp/order/entity/SeckillOutbox.java`
- Create: `order-service/src/main/java/com/hmdp/order/mapper/SeckillOutboxMapper.java`

**Interfaces:**
- Produces:
  - 表 `tb_seckill_outbox(id, user_id, voucher_id, status, retry_count, create_time, update_time)`
  - `class SeckillOutbox`（`@Accessors(chain = true)`；`id` 为 `IdType.INPUT`）
  - `interface SeckillOutboxMapper extends BaseMapper<SeckillOutbox>`
  - 状态常量由 Task 9/10 在使用方定义（本任务不定义）

- [ ] **Step 1: 建表 DDL**

创建 `sql/spec15-seckill-outbox.sql`：

```sql
-- =====================================================================
-- SPEC-15 P2-1：秒杀本地事件表（outbox）
--
-- 形态 D1-b（行内投递 + 定时补投）：
--   秒杀入口在调用 Lua 预扣成功之后、发送 MQ 之前，先把这一行落库。
--   落库成功即代表"这条订单一定会被投递"——即使进程随后崩溃，
--   SeckillOutboxDeliverer 也会在后续轮次把它补投出去。
--
-- 状态：0-待投递（status=0 且超时未变更的行才补投）  1-已投递（MQ 已确认）
--
-- 为什么订单号就是主键：orderId 由 RedisIdWorker 生成且全局唯一，
-- 天然充当幂等键 —— 重复落库会因主键冲突被拦下，无需额外唯一索引。
-- =====================================================================
USE hmdp;

CREATE TABLE IF NOT EXISTS tb_seckill_outbox
(
    id          BIGINT UNSIGNED NOT NULL COMMENT '订单ID（与 tb_voucher_order.id 同源，RedisIdWorker 生成）',
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    voucher_id  BIGINT UNSIGNED NOT NULL COMMENT '秒杀券ID',
    status      TINYINT         NOT NULL DEFAULT 0 COMMENT '0-待投递 1-已投递',
    retry_count INT             NOT NULL DEFAULT 0 COMMENT '补投器投递失败次数',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近变更时间（补投按它做退避）',
    PRIMARY KEY (id),
    KEY idx_status_update (status, update_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='秒杀本地事件表（事务消息 outbox，SPEC-15 P2-1）';
```

- [ ] **Step 2: 建实体**

创建 `order-service/src/main/java/com/hmdp/order/entity/SeckillOutbox.java`：

```java
package com.hmdp.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀本地事件行（SPEC-15 P2-1 方案 a）
 *
 * <p>语义：一行 = 一条"必须被投递到 MQ"的承诺。落库先于投递，所以
 * 「投递决策」的持久化边界是这条 INSERT，而不是 MQ 的 ack。
 *
 * <p><b>为什么 id 用 {@link IdType#INPUT}</b>：orderId 由 {@code RedisIdWorker} 生成，
 * 不是数据库自增。设成 AUTO 会让 MyBatis-Plus 忽略传入值、回填一个自增主键，
 * 与 {@code tb_voucher_order.id} 就此错位——两个表再也对不上。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_seckill_outbox")
public class SeckillOutbox implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long userId;

    private Long voucherId;

    /** 0-待投递；1-已投递 */
    private Integer status;

    /** 补投器投递失败次数；超过上限后停止自动补投并告警 */
    private Integer retryCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
```

- [ ] **Step 3: 建 Mapper**

创建 `order-service/src/main/java/com/hmdp/order/mapper/SeckillOutboxMapper.java`：

```java
package com.hmdp.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.order.entity.SeckillOutbox;

/**
 * 秒杀本地事件表 Mapper（SPEC-15 P2-1）
 *
 * <p>由 {@code @MapperScan("com.hmdp.order.mapper")} 扫描，无需额外注册
 * （与 {@code SeckillConsistencyAuditMapper} 同）。
 */
public interface SeckillOutboxMapper extends BaseMapper<SeckillOutbox> {
}
```

- [ ] **Step 4: 编译确认**

Run: `mvn -pl order-service -am test-compile -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS

- [ ] **Step 5: 手工建表（供后续实测使用）**

Run: `docker exec -i hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD" < sql/spec15-seckill-outbox.sql`
Expected: 无输出即成功；验证 `docker exec hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD" -e "SHOW TABLES FROM hmdp LIKE 'tb_seckill_outbox'"`

> 若 `hmdp-mysql` 容器名不同，先 `docker ps --format '{{.Names}}'` 确认。

- [ ] **Step 6: 提交**

```bash
git add sql/spec15-seckill-outbox.sql \
        order-service/src/main/java/com/hmdp/order/entity/SeckillOutbox.java \
        order-service/src/main/java/com/hmdp/order/mapper/SeckillOutboxMapper.java
git commit -m "feat(seckill): P2-1 新增本地事件表 tb_seckill_outbox（DDL + 实体 + Mapper）"
```

---

## Task 9: P2-1 热路径落库 + 投递标记

**Files:**
- Modify: `order-service/src/main/java/com/hmdp/order/service/impl/VoucherOrderServiceImpl.java`
- Modify: `order-service/src/test/java/com/hmdp/order/service/impl/SeckillVoucherServiceTest.java`

**Interfaces:**
- Consumes: `SeckillOutbox`、`SeckillOutboxMapper`
- Produces:
  - `VoucherOrderServiceImpl` 包级常量 `OUTBOX_STATUS_PENDING = 0` / `OUTBOX_STATUS_DELIVERED = 1`
  - `seckillVoucher(Long)` 签名与返回语义**不变**

**崩溃顺序论证（实现前必读）**：投递失败时，**必须先删事件行、再回滚预扣**。
- 先删后回滚：中途崩溃 → 行已删、预扣仍在（库存已 DECR、用户在 Set 里、明细还在）。在途补偿器会在 T+120s 重投该单，用户最终拿到订单。方向是**少卖**，安全。
- 先回滚后删：中途崩溃 → 预扣已释放（库存 INCR、用户移出 Set）但行仍待投递 → 补投器发出消息 → 消费端重新建单 → Redis 库存比 DB 多 1，即**超卖**方向。

- [ ] **Step 1: 写失败测试**

在 `SeckillVoucherServiceTest` 中，`@Mock private SeckillMetrics seckillMetrics;` 之后追加：

```java
    @Mock private SeckillOutboxMapper seckillOutboxMapper;
```

import 区追加：

```java
import com.hmdp.order.entity.SeckillOutbox;
import com.hmdp.order.mapper.SeckillOutboxMapper;
import org.mockito.InOrder;
```

在类末尾追加：

```java
    // ---------- SPEC-15 P2-1：本地事件表 ----------

    @Test
    void 成功路径先落待投递事件行再投递_且投递成功后标记已投递() {
        scriptReturns(0L);
        when(seckillOrderProducer.sendSeckillOrderMessage(any())).thenReturn(true);

        Result r = service.seckillVoucher(1L);

        assertTrue(r.getSuccess());

        ArgumentCaptor<SeckillOutbox> row = ArgumentCaptor.forClass(SeckillOutbox.class);
        verify(seckillOutboxMapper).insert(row.capture());
        assertEquals(9001L, row.getValue().getId());
        assertEquals(7L, row.getValue().getUserId());
        assertEquals(1L, row.getValue().getVoucherId());
        assertEquals(0, row.getValue().getStatus(), "落库时必须是待投递态");
        assertEquals(0, row.getValue().getRetryCount());

        // 落库必须先于投递：反过来的话"提交后崩溃"就丢了投递决策
        InOrder order = inOrder(seckillOutboxMapper, seckillOrderProducer);
        order.verify(seckillOutboxMapper).insert(any());
        order.verify(seckillOrderProducer).sendSeckillOrderMessage(any());

        verify(seckillOutboxMapper).update(isNull(), any());
    }

    @Test
    void 事件行落库失败时回滚预扣且返回失败_不投递() {
        scriptReturns(0L);
        when(seckillOutboxMapper.insert(any())).thenThrow(new RuntimeException("DB 不可用"));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);

        Result r = service.seckillVoucher(1L);

        assertFalse(r.getSuccess());
        verify(valueOperations).increment("seckill:stock:1");
        verify(setOperations).remove("seckill:order:1", "7");
        verify(hashOperations).delete("seckill:order:detail:1", "9001");
        verify(seckillOrderProducer, never()).sendSeckillOrderMessage(any());
        verify(seckillMetrics, never()).incrementSeckillSuccess();
    }

    @Test
    void 投递失败时先删事件行再回滚预扣() {
        scriptReturns(0L);
        when(seckillOrderProducer.sendSeckillOrderMessage(any())).thenReturn(false);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);

        Result r = service.seckillVoucher(1L);

        assertFalse(r.getSuccess());
        assertEquals("订单提交繁忙，请稍后重试", r.getErrorMsg());

        // 顺序即正确性（见本任务顶部的崩溃顺序论证）：
        // 先删行、后回滚，崩溃时停在"行已删 + 预扣仍在"的少卖侧；
        // 反过来会停在"预扣已释放 + 行仍待投递"，补投出去就是超卖。
        InOrder order = inOrder(seckillOutboxMapper, valueOperations);
        order.verify(seckillOutboxMapper).deleteById(9001L);
        order.verify(valueOperations).increment("seckill:stock:1");
    }

    @Test
    void 事件行删除失败时保留预扣不回滚() {
        scriptReturns(0L);
        when(seckillOrderProducer.sendSeckillOrderMessage(any())).thenReturn(false);
        when(seckillOutboxMapper.deleteById(9001L)).thenThrow(new RuntimeException("DB 不可用"));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);

        Result r = service.seckillVoucher(1L);

        assertFalse(r.getSuccess());
        // 行删不掉 → 该单仍会被补投器投递 → 预扣必须保留。
        // 若这里回滚了，补投出去的消息会在已释放的预扣上重新建单，Redis 库存比 DB 多 1 = 超卖。
        verify(valueOperations, never()).increment(anyString());
        verify(setOperations, never()).remove(anyString(), anyString());
        verify(hashOperations, never()).delete(anyString(), any());
        verify(seckillMetrics, never()).incrementSeckillSuccess();
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl order-service -am test -Dtest=SeckillVoucherServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL —— `Wanted but not invoked: seckillOutboxMapper.insert(...)`

- [ ] **Step 3: 改 VoucherOrderServiceImpl**

import 区追加：

```java
import com.hmdp.order.entity.SeckillOutbox;
import com.hmdp.order.mapper.SeckillOutboxMapper;
```

字段区、`private SeckillMetrics seckillMetrics;` 之后追加：

```java
    @Resource
    private SeckillOutboxMapper seckillOutboxMapper;
```

类字段区（`private static final DefaultRedisScript<Long> SECKILL_SCRIPT;` 之前）追加：

```java
    /** 本地事件表状态：待投递 */
    static final int OUTBOX_STATUS_PENDING = 0;
    /** 本地事件表状态：已投递（MQ 已确认） */
    static final int OUTBOX_STATUS_DELIVERED = 1;
```

把 `seckillVoucher` 方法里从 `SeckillOrderMessage message = ...` 到 `return Result.ok(orderId);` 的整段替换为：

```java
            // 本地事件表（SPEC-15 P2-1 方案 a / 形态 D1-b）：先落库、再投递。
            //
            // 【为什么这样就够】落库成功即代表"这条订单一定会被投递"：即使本进程在
            // 紧接着的一行崩溃，SeckillOutboxDeliverer 也会扫到 status=0 的行补投。
            // 丢失窗口 = 0，不再依赖 P0-2 的 T+120s 事后补偿。
            //
            // 【诚实说明】本项目生产端此前没有任何 DB 写入（库存在 Redis 扣、订单由消费者落库），
            // 因此"投递与本地状态同事务"在这里**等价于**"INSERT 自身提交后再投递"——
            // 没有第二个 DB 写入可以与之原子化。不要把它理解成两阶段提交。
            // 也正因为如此，本方法**没有**加 @Transactional：那只会把 Redis 预扣和
            // MQ 同步发送（网络调用）一起圈进一个 DB 事务，长事务持有连接且毫无收益。
            SeckillOutbox outbox = new SeckillOutbox()
                    .setId(orderId)
                    .setUserId(userId)
                    .setVoucherId(voucherId)
                    .setStatus(OUTBOX_STATUS_PENDING)
                    .setRetryCount(0);
            try {
                seckillOutboxMapper.insert(outbox);
            } catch (Exception e) {
                // 落库失败 = 无法对投递做持久承诺，必须回滚预扣并明确返回失败
                rollbackSeckillReservation(voucherId, userId, orderId);
                seckillMetrics.incrementSeckillFail();
                log.error("秒杀事件行落库失败，已回滚Redis预扣: orderId={}, userId={}, voucherId={}",
                        orderId, userId, voucherId, e);
                return Result.fail(SeckillFailMessages.MQ_SEND_FAILED);
            }

            SeckillOrderMessage message = new SeckillOrderMessage(orderId, userId, voucherId);

            // 同步发送（SPEC-03 §5.2 方案 A）：asyncSend 的返回值只代表"提交成功"，
            // 真正的失败被吞在回调里，用户会拿到一个永不兑现的 orderId
            if (!seckillOrderProducer.sendSeckillOrderMessage(message)) {
                // 【顺序即正确性】必须先删事件行、再回滚预扣。理由见本段上方注释与
                // SeckillVoucherServiceTest#投递失败时先删事件行再回滚预扣 的论证：
                // 反过来会在"回滚完成但行未删"的崩溃点上留下一条待投递记录，
                // 补投出去会在已释放的预扣上重新建单 —— 超卖方向。
                if (deleteOutboxRow(orderId)) {
                    rollbackSeckillReservation(voucherId, userId, orderId);
                } else {
                    // 行没删掉 → 该单仍会被补投器投递 → 预扣**必须保留**。
                    // 若此处照常回滚（INCR 库存 + 移出用户 + 删明细），补投出去的消息会在
                    // 一份已释放的预扣上重新建单：Redis 库存比 DB 多 1，即超卖方向。
                    // 这是"先删后回滚"想防的同一类风险，只是触发方式从进程崩溃换成了删除抛异常。
                    // 取舍：宁可让用户先看到一次失败、稍后真的拿到订单（延迟/少卖），也不能超卖。
                    log.error("[需人工核对] 事件行删除失败，已保留预扣不回滚: orderId={}, userId={}, voucherId={}",
                            orderId, userId, voucherId);
                }
                seckillMetrics.incrementMqSendFail();
                seckillMetrics.incrementSeckillFail();
                log.error("秒杀订单消息发送失败: orderId={}, userId={}, voucherId={}",
                        orderId, userId, voucherId);
                return Result.fail(SeckillFailMessages.MQ_SEND_FAILED);
            }

            // 标记已投递。失败只记 warn、不影响返回：补投器下一轮会再投一次，
            // 消费端以 orderId 为主键幂等，重复投递不会重复建单。
            markOutboxDelivered(orderId);

            seckillMetrics.incrementMqSendSuccess();
            seckillMetrics.incrementSeckillSuccess();
            log.info("秒杀资格校验通过，订单异步处理中: orderId={}, userId={}, voucherId={}",
                    orderId, userId, voucherId);
            return Result.ok(orderId);
```

在 `rollbackSeckillReservation` 方法之后追加两个私有方法：

```java
    /**
     * 标记事件行已投递。
     *
     * <p>条件更新（{@code status = 待投递}）：补投器可能已经抢先投递并置位，
     * 无条件的 UPDATE 会把它的结果覆盖掉，语义上没错但会掩盖真实投递方。
     */
    private void markOutboxDelivered(Long orderId) {
        try {
            seckillOutboxMapper.update(null, Wrappers.<SeckillOutbox>lambdaUpdate()
                    .eq(SeckillOutbox::getId, orderId)
                    .eq(SeckillOutbox::getStatus, OUTBOX_STATUS_PENDING)
                    .set(SeckillOutbox::getStatus, OUTBOX_STATUS_DELIVERED));
        } catch (Exception e) {
            log.warn("秒杀事件行标记已投递失败，补投器会重投（消费端幂等）: orderId={}", orderId, e);
        }
    }

    /**
     * 删除事件行。
     *
     * <p>刻意**不**检查 {@code deleteById} 的返回值：返回 0 只代表该行本就不存在
     * （没有可投递的记录），与"删除成功"在业务上等价，都允许回滚预扣。
     *
     * @return true = 行已不存在（可以安全回滚预扣）；false = 删除抛异常
     *         （调用方**不得**回滚预扣，否则补投出去就是超卖）
     */
    private boolean deleteOutboxRow(Long orderId) {
        try {
            seckillOutboxMapper.deleteById(orderId);
            return true;
        } catch (Exception e) {
            log.error("秒杀事件行删除失败: orderId={}", orderId, e);
            return false;
        }
    }
```

> `Wrappers` 已在文件中 import（`com.baomidou.mybatisplus.core.toolkit.Wrappers`）。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl order-service -am test -Dtest=SeckillVoucherServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（含既有 16 例 + 新增 3 例）

- [ ] **Step 5: 提交**

```bash
git add order-service/src/main/java/com/hmdp/order/service/impl/VoucherOrderServiceImpl.java \
        order-service/src/test/java/com/hmdp/order/service/impl/SeckillVoucherServiceTest.java
git commit -m "feat(seckill): P2-1 秒杀热路径落本地事件表，投递标记与崩溃安全顺序"
```

---

## Task 10: P2-1 定时补投器

**Files:**
- Create: `order-service/src/main/java/com/hmdp/order/service/impl/SeckillOutboxDeliverer.java`
- Create: `order-service/src/test/java/com/hmdp/order/service/impl/SeckillOutboxDelivererTest.java`
- Modify: `order-service/src/main/java/com/hmdp/order/metrics/SeckillMetrics.java`
- Modify: `order-service/src/test/java/com/hmdp/order/SeckillSchedulingContractTest.java`

**Interfaces:**
- Consumes: `SeckillOutboxMapper`、`SeckillOrderProducer.sendSeckillOrderMessage(SeckillOrderMessage)`、`SeckillMetrics`
- Produces:
  - `SeckillOutboxDeliverer.deliverPending()` → `void`（`@Scheduled(fixedDelayString = "${hmdp.seckill.outbox.deliver-interval-ms:3000}")`）
  - `SeckillMetrics.incrementOutboxRedelivered()` / `incrementOutboxRedeliverFail()` / `incrementOutboxExhausted()` → `void`
  - 常量 `SeckillOutboxDeliverer.REDELIVER_AFTER_SECONDS = 30L` / `BATCH_SIZE = 100` / `MAX_RETRY = 5`

- [ ] **Step 1: 写补投器失败测试**

创建 `order-service/src/test/java/com/hmdp/order/service/impl/SeckillOutboxDelivererTest.java`：

```java
package com.hmdp.order.service.impl;

import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.order.entity.SeckillOutbox;
import com.hmdp.order.mapper.SeckillOutboxMapper;
import com.hmdp.order.metrics.SeckillMetrics;
import com.hmdp.order.mq.SeckillOrderProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 秒杀事件表补投器（SPEC-15 P2-1 形态 D1-b 的"定时补投"半边）
 *
 * <p>它兜住的是"落库成功但投递没完成"的崩溃窗口——投递本身没有返回值可断言，
 * 因此这里断言的是**状态机推进**：成功→置已投递，失败→retry_count 自增且仍待投递。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeckillOutboxDelivererTest {

    @Mock private SeckillOutboxMapper seckillOutboxMapper;
    @Mock private SeckillOrderProducer seckillOrderProducer;
    @Mock private SeckillMetrics seckillMetrics;

    @InjectMocks private SeckillOutboxDeliverer deliverer;

    private static SeckillOutbox pendingRow(long orderId, int retryCount) {
        return new SeckillOutbox()
                .setId(orderId).setUserId(7L).setVoucherId(1L)
                .setStatus(0).setRetryCount(retryCount);
    }

    @BeforeEach
    void setUp() {
        when(seckillOutboxMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void 无待投递行时不调用生产者() {
        deliverer.deliverPending();

        verify(seckillOrderProducer, never()).sendSeckillOrderMessage(any());
    }

    @Test
    void 补投成功置为已投递并计数() {
        when(seckillOutboxMapper.selectList(any())).thenReturn(List.of(pendingRow(9001L, 0)));
        when(seckillOrderProducer.sendSeckillOrderMessage(any())).thenReturn(true);
        // 条件更新真的改到了行（affected=1）——计数以影响行数为准，故必须桩返回 1
        when(seckillOutboxMapper.update(any(), any())).thenReturn(1);

        deliverer.deliverPending();

        // 必须用 argThat 而不是 eq(new SeckillOrderMessage(...))：
        // SeckillOrderMessage 是三参构造会把 timestamp 设为 System.currentTimeMillis()，
        // 且类上是 @Data —— equals 含 timestamp，两次构造永远不相等，eq 必然失败。
        // 既有 SeckillInFlightCompensatorTest:99 已用同样写法。
        verify(seckillOrderProducer).sendSeckillOrderMessage(argThat(m ->
                m.getOrderId().equals(9001L) && m.getUserId().equals(7L) && m.getVoucherId().equals(1L)));
        verify(seckillOutboxMapper).update(isNull(), any());
        verify(seckillMetrics).incrementOutboxRedelivered();
        verify(seckillMetrics, never()).incrementOutboxRedeliverFail();
    }

    @Test
    void 重复投递时不计入补投成功数() {
        when(seckillOutboxMapper.selectList(any())).thenReturn(List.of(pendingRow(9004L, 0)));
        when(seckillOrderProducer.sendSeckillOrderMessage(any())).thenReturn(true);
        // 条件更新影响 0 行：该行已被入口侧或另一实例置为已投递
        when(seckillOutboxMapper.update(any(), any())).thenReturn(0);

        deliverer.deliverPending();

        // 无条件下计数会把一笔补投算成两笔，告警口径失真
        verify(seckillMetrics, never()).incrementOutboxRedelivered();
        verify(seckillMetrics, never()).incrementOutboxRedeliverFail();
    }

    @Test
    void 补投失败时retry_count自增且保持待投递() {
        when(seckillOutboxMapper.selectList(any())).thenReturn(List.of(pendingRow(9002L, 1)));
        when(seckillOrderProducer.sendSeckillOrderMessage(any())).thenReturn(false);

        deliverer.deliverPending();

        verify(seckillOutboxMapper).update(isNull(), any());
        verify(seckillMetrics).incrementOutboxRedeliverFail();
        verify(seckillMetrics, never()).incrementOutboxRedelivered();
        // 未达上限（1+1=2 < 5）：不告警
        verify(seckillMetrics, never()).incrementOutboxExhausted();
    }

    @Test
    void 补投次数达上限时额外计数告警() {
        when(seckillOutboxMapper.selectList(any()))
                .thenReturn(List.of(pendingRow(9003L, SeckillOutboxDeliverer.MAX_RETRY - 1)));
        when(seckillOrderProducer.sendSeckillOrderMessage(any())).thenReturn(false);

        deliverer.deliverPending();

        verify(seckillMetrics).incrementOutboxExhausted();
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl order-service -am test -Dtest=SeckillOutboxDelivererTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— `cannot find symbol: class SeckillOutboxDeliverer`

- [ ] **Step 3: 给 SeckillMetrics 加 outbox 计数**

在 `SeckillMetrics` 字段区（`feignCallFailCounter` 之后）追加：

```java
    private Counter outboxRedeliveredCounter;
    private Counter outboxRedeliverFailCounter;
    private Counter outboxExhaustedCounter;
```

`init()` 内（`feignCallFailCounter` 注册之后）追加：

```java
        // SPEC-15 P2-1：本地事件表的补投质量。
        // redelivered > 0 说明"落库后崩溃"确实发生过、补投机制真的在干活；
        // exhausted > 0 说明有订单连补投都投不出去，需要人工介入。
        outboxRedeliveredCounter = Counter.builder("seckill.outbox.redelivered")
                .description("事件表补投成功数（SPEC-15 P2-1）")
                .tag("type", "outbox_redelivered")
                .register(meterRegistry);

        outboxRedeliverFailCounter = Counter.builder("seckill.outbox.redeliver.fail")
                .description("事件表补投失败数（SPEC-15 P2-1）")
                .tag("type", "outbox_redeliver_fail")
                .register(meterRegistry);

        outboxExhaustedCounter = Counter.builder("seckill.outbox.exhausted")
                .description("事件表补投次数耗尽数（需人工核对；稳态应为 0）")
                .tag("type", "outbox_exhausted")
                .register(meterRegistry);
```

方法区（`incrementFeignCallFail()` 之后）追加：

```java
    /** 事件表补投成功一次（SPEC-15 P2-1） */
    public void incrementOutboxRedelivered() {
        outboxRedeliveredCounter.increment();
    }

    /** 事件表补投失败一次（SPEC-15 P2-1） */
    public void incrementOutboxRedeliverFail() {
        outboxRedeliverFailCounter.increment();
    }

    /** 事件表补投次数耗尽（SPEC-15 P2-1）：转人工，必须告警 */
    public void incrementOutboxExhausted() {
        outboxExhaustedCounter.increment();
    }
```

- [ ] **Step 4: 实现补投器**

创建 `order-service/src/main/java/com/hmdp/order/service/impl/SeckillOutboxDeliverer.java`：

```java
package com.hmdp.order.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.order.entity.SeckillOutbox;
import com.hmdp.order.mapper.SeckillOutboxMapper;
import com.hmdp.order.metrics.SeckillMetrics;
import com.hmdp.order.mq.SeckillOrderProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀事件表补投器（SPEC-15 P2-1 方案 a / 形态 D1-b 的"定时补投"半边）
 *
 * <p><b>它补的是哪个洞</b>：入口侧的投递是同步的（{@code syncSend}），但"落库成功 →
 * 投递完成"之间存在崩溃窗口。窗口内挂掉的订单在 DB 里留着一条 {@code status=0} 的行，
 * 本类在后续轮次把它补投出去。消费端以 orderId 为主键幂等，重复投递不会重复建单。
 *
 * <p><b>与 P0-2 在途补偿器的分工</b>：补偿器扫的是 Redis 明细 Hash，覆盖"Lua 预扣后
 * 还没落库就崩"的窗口；本类扫的是 DB 事件表，覆盖"落库后还没投递就崩"的窗口。
 * 两者窗口不重叠，都保留。
 *
 * <p><b>退避</b>：只扫 {@code update_time} 早于 {@value #REDELIVER_AFTER_SECONDS} 秒的行。
 * 一是给入口侧的同步投递留出完成时间，避免每条正常订单都被重复投一次；
 * 二是投递失败时会刷新 {@code update_time}（列上带 {@code ON UPDATE CURRENT_TIMESTAMP}），
 * 天然形成固定间隔重试，不会对持续失败的行热循环。
 *
 * <p><b>为什么刻意不加 Redisson 锁</b>（与同目录的 {@code SeckillInFlightCompensator} 不同）：
 * 补偿器的锁保护的是**不可逆**动作 —— 安全释放会 INCR 库存、把用户移出 Set，两个实例同时做就是
 * 双倍回补（超卖）。本类的动作只是"发一条 MQ 消息"，消费端以 orderId 为主键幂等，
 * 重复投递不产生任何数据后果；重复的那次也会因为条件更新影响 0 行而不计入补投成功数。
 * 反过来，加锁会引入一个新的停摆模式：Redisson 故障时补投整体不执行，而它本是"崩溃兜底"，
 * 不该再多一个依赖。取舍明确：用"可能重复投递（幂等）"换"不依赖额外组件"。
 */
@Component
@Slf4j
public class SeckillOutboxDeliverer {

    /** 事件行状态：待投递（与 {@code VoucherOrderServiceImpl.OUTBOX_STATUS_PENDING} 同义） */
    private static final int STATUS_PENDING = 0;

    /** 事件行状态：已投递 */
    private static final int STATUS_DELIVERED = 1;

    /** 刚写入/刚失败的行留多久才允许补投（秒） */
    static final long REDELIVER_AFTER_SECONDS = 30L;

    /** 单轮扫描上限：一次故障积压后不至于单轮扫爆内存 */
    static final int BATCH_SIZE = 100;

    /** 单条事件行的补投次数上限；达到后停止自动补投并告警 */
    static final int MAX_RETRY = 5;

    @Resource
    private SeckillOutboxMapper seckillOutboxMapper;

    @Resource
    private SeckillOrderProducer seckillOrderProducer;

    @Resource
    private SeckillMetrics seckillMetrics;

    /**
     * 周期性补投。
     *
     * <p>fixedDelay 而非 fixedRate：本轮补投的耗时不应与下一轮重叠，
     * 否则大量积压时会有两轮同时扫同一批行（与 {@code SeckillInFlightCompensator} 同口径）。
     */
    @Scheduled(fixedDelayString = "${hmdp.seckill.outbox.deliver-interval-ms:3000}")
    public void deliverPending() {
        List<SeckillOutbox> pending;
        try {
            pending = seckillOutboxMapper.selectList(Wrappers.<SeckillOutbox>lambdaQuery()
                    .eq(SeckillOutbox::getStatus, STATUS_PENDING)
                    .lt(SeckillOutbox::getRetryCount, MAX_RETRY)
                    .le(SeckillOutbox::getUpdateTime,
                            LocalDateTime.now().minusSeconds(REDELIVER_AFTER_SECONDS))
                    .orderByAsc(SeckillOutbox::getUpdateTime)
                    // 项目未配置 MP 分页插件，沿用既有手写 LIMIT 的做法
                    .last("LIMIT " + BATCH_SIZE));
        } catch (Exception e) {
            log.error("事件表补投扫描失败", e);
            return;
        }

        if (pending.isEmpty()) {
            return;
        }

        int delivered = 0;
        int failed = 0;
        for (SeckillOutbox row : pending) {
            if (deliverOne(row)) {
                delivered++;
            } else {
                failed++;
            }
        }
        log.warn("事件表补投完成: 扫描={}, 补投成功={}, 失败={}", pending.size(), delivered, failed);
    }

    private boolean deliverOne(SeckillOutbox row) {
        boolean sent = seckillOrderProducer.sendSeckillOrderMessage(
                new SeckillOrderMessage(row.getId(), row.getUserId(), row.getVoucherId()));
        if (sent) {
            // 条件更新：入口侧可能已抢先置位，只有仍是待投递才改写。
            //
            // 【为什么按影响行数计数而不是无条件自增】多实例同 tick 会扫到同一批行、
            // 各自投递一次（本类刻意不加 Redisson 锁，理由见类注释）。消费端以 orderId
            // 为主键幂等，重复投递不产生数据问题；但若无条件自增，
            // seckill.outbox.redelivered 会把一笔补投算成两笔，告警口径失真。
            // 条件更新天然给出了"谁真正改了行"的答案：只有一个实例会拿到 affected=1。
            int affected = seckillOutboxMapper.update(null, Wrappers.<SeckillOutbox>lambdaUpdate()
                    .eq(SeckillOutbox::getId, row.getId())
                    .eq(SeckillOutbox::getStatus, STATUS_PENDING)
                    .set(SeckillOutbox::getStatus, STATUS_DELIVERED));
            if (affected > 0) {
                seckillMetrics.incrementOutboxRedelivered();
                log.warn("事件表补投成功: orderId={}, retryCount={}", row.getId(), row.getRetryCount());
            } else {
                // 影响 0 行：该行已被入口侧或另一实例置为已投递，本次属重复投递（消费端幂等）
                log.info("事件表补投命中已投递行（重复投递，消费端幂等）: orderId={}", row.getId());
            }
            return true;
        }

        int nextRetry = (row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1;
        seckillOutboxMapper.update(null, Wrappers.<SeckillOutbox>lambdaUpdate()
                .eq(SeckillOutbox::getId, row.getId())
                .setSql("retry_count = retry_count + 1"));
        seckillMetrics.incrementOutboxRedeliverFail();
        if (nextRetry >= MAX_RETRY) {
            seckillMetrics.incrementOutboxExhausted();
            log.error("[事件表补投耗尽] 该订单需人工核对: orderId={}, userId={}, voucherId={}, retryCount={}",
                    row.getId(), row.getUserId(), row.getVoucherId(), nextRetry);
        } else {
            log.warn("事件表补投失败，下轮重试: orderId={}, retryCount={}", row.getId(), nextRetry);
        }
        return false;
    }
}
```

- [ ] **Step 5: 运行补投器测试确认通过**

Run: `mvn -pl order-service -am test -Dtest="SeckillOutboxDelivererTest,SeckillMetricsTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 6: 补调度注册契约测试**

在 `order-service/src/test/java/com/hmdp/order/SeckillSchedulingContractTest.java` 的 import 区追加：

```java
import com.hmdp.order.service.impl.SeckillOutboxDeliverer;
```

在类末尾追加：

```java
    /**
     * SPEC-15 P2-1：补投器必须真的被注册。
     *
     * <p>与既有 {@code scheduledConsistencyCheck} 同一个坑——{@code @Scheduled} 写了
     * 但启动类没有 {@code @EnableScheduling} 时，方法**从未被执行**，日志行数为 0，
     * 而"有定时补投"看起来是成立的。这条断言把这个坑钉死。
     */
    @Test
    void 事件表补投器必须是已注册的定时任务() throws Exception {
        Method m = SeckillOutboxDeliverer.class.getMethod("deliverPending");

        Scheduled scheduled = m.getAnnotation(Scheduled.class);
        assertNotNull(scheduled, "deliverPending 必须标 @Scheduled，否则事件表永不补投");
        assertEquals("${hmdp.seckill.outbox.deliver-interval-ms:3000}", scheduled.fixedDelayString(),
                "补投间隔必须可配（默认 3s），且用 fixedDelay 防止两轮扫描重叠");
    }
```

- [ ] **Step 7: 运行调度契约测试**

Run: `mvn -pl order-service -am test -Dtest=SeckillSchedulingContractTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 8: 提交**

```bash
git add order-service/src/main/java/com/hmdp/order/service/impl/SeckillOutboxDeliverer.java \
        order-service/src/main/java/com/hmdp/order/metrics/SeckillMetrics.java \
        order-service/src/test/java/com/hmdp/order/service/impl/SeckillOutboxDelivererTest.java \
        order-service/src/test/java/com/hmdp/order/SeckillSchedulingContractTest.java
git commit -m "feat(seckill): P2-1 事件表定时补投器 + 调度注册契约"
```

---

## Task 11: P2-2 网关风控黑名单

**Files:**
- Modify: `common/src/main/java/com/hmdp/utils/RedisConstants.java`
- Create: `gateway-service/src/main/java/com/hmdp/gateway/config/RiskBlacklistProperties.java`
- Create: `gateway-service/src/main/java/com/hmdp/gateway/filter/RiskBlacklistFilter.java`
- Modify: `gateway-service/src/main/resources/application.yaml`
- Create: `gateway-service/src/test/java/com/hmdp/gateway/filter/RiskBlacklistFilterTest.java`

**Interfaces:**
- Consumes: `StringRedisTemplate.opsForSet().isMember(String, Object)`、`StpUtil.getLoginIdByToken(String)`
- Produces:
  - `RedisConstants.RISK_BLACKLIST_USER_KEY = "risk:blacklist:user"` / `RISK_BLACKLIST_IP_KEY = "risk:blacklist:ip"`
  - `RiskBlacklistProperties`（`@ConfigurationProperties("hmdp.risk.blacklist")`）：`List<String> pathPrefixes`、`String message`
  - `RiskBlacklistFilter`（`GlobalFilter`，`getOrder() == -20`）

- [ ] **Step 1: 加键常量**

在 `common/src/main/java/com/hmdp/utils/RedisConstants.java` 的 `CACHE_VOUCHER_KEY` 之后追加：

```java
    /**
     * 风控黑名单 · 用户维度（Set，成员为 loginId 字符串）——SPEC-15 P2-2。
     *
     * <p>先由运维手工维护（{@code SADD risk:blacklist:user 123}），后续可对接风控规则。
     * 放在 RedisConstants 而非就地硬编码：网关与运维脚本必须看到同一份键名。
     */
    public static final String RISK_BLACKLIST_USER_KEY = "risk:blacklist:user";

    /** 风控黑名单 · IP 维度（Set，成员为点分十进制 IP）——SPEC-15 P2-2 */
    public static final String RISK_BLACKLIST_IP_KEY = "risk:blacklist:ip";
```

- [ ] **Step 2: 写过滤器失败测试**

创建 `gateway-service/src/test/java/com/hmdp/gateway/filter/RiskBlacklistFilterTest.java`：

```java
package com.hmdp.gateway.filter;

import com.hmdp.gateway.config.RiskBlacklistProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 网关风控黑名单（SPEC-15 P2-2）
 *
 * <p>纯单元测试：不启 Spring、不连 Redis（SetOperations 为 mock），可入 CI。
 * loginId 维度依赖静态 {@code StpUtil}，故用"无 token"覆盖"身份不可得"分支。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RiskBlacklistFilterTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private SetOperations<String, String> setOperations;
    @Mock private MeterRegistry meterRegistry;
    @Mock private Counter counter;
    @Mock private GatewayFilterChain chain;

    private RiskBlacklistProperties properties;
    private RiskBlacklistFilter filter;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);
        when(chain.filter(any())).thenReturn(Mono.empty());

        properties = new RiskBlacklistProperties();
        properties.setPathPrefixes(List.of("/voucher-order/seckill/"));
        properties.setMessage("该账号已被风控拦截");
        filter = new RiskBlacklistFilter(stringRedisTemplate, properties, meterRegistry);
    }

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path)
                .remoteAddress(new InetSocketAddress("10.0.0.9", 51234)));
    }

    @Test
    void 非保护路径直接放行_不查Redis() {
        filter.filter(exchange("/shop/1"), chain).block();

        verifyNoInteractions(stringRedisTemplate);
        verify(chain).filter(any());
    }

    @Test
    void IP命中黑名单时返回403并计数() {
        when(setOperations.isMember("risk:blacklist:ip", "10.0.0.9")).thenReturn(true);

        MockServerWebExchange ex = exchange("/voucher-order/seckill/1");
        filter.filter(ex, chain).block();

        assertEquals(HttpStatus.FORBIDDEN, ex.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
        verify(counter).increment();
    }

    @Test
    void 未命中黑名单时直通() {
        // 必须写 any(Object.class) 而不是裸 any()：spring-data-redis 的 isMember 有
        // (K,Object)→Boolean 与 (K,Object...)→Map 两个重载，裸 any() 会选中可变参那个，
        // thenReturn(false) 类型不符、编译不过。
        when(setOperations.isMember(anyString(), any(Object.class))).thenReturn(false);

        filter.filter(exchange("/voucher-order/seckill/1"), chain).block();

        verify(chain).filter(any());
        verify(counter, never()).increment();
    }

    @Test
    void Redis异常时fail_open放行() {
        when(stringRedisTemplate.opsForSet())
                .thenThrow(new RedisConnectionFailureException("redis down"));

        MockServerWebExchange ex = exchange("/voucher-order/seckill/1");
        assertDoesNotThrow(() -> filter.filter(ex, chain).block());

        assertNull(ex.getResponse().getStatusCode(), "风控组件故障不得阻断下单");
        verify(chain).filter(any());
    }

    @Test
    void 过滤器顺序必须先于限流器() {
        assertEquals(-20, filter.getOrder(),
                "SPEC-15 §2.6 明确要求在限流判定**之前**做黑名单判定");
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `mvn -pl gateway-service -am test -Dtest=RiskBlacklistFilterTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— `cannot find symbol: class RiskBlacklistProperties`

- [ ] **Step 4: 实现配置类**

创建 `gateway-service/src/main/java/com/hmdp/gateway/config/RiskBlacklistProperties.java`：

```java
package com.hmdp.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关风控黑名单配置（SPEC-15 P2-2）。
 *
 * <p>与 {@code RateLimitProperties} 同风格：路径由配置驱动，运维加一条 yaml 即可扩大范围。
 * 黑名单**数据**在 Redis Set 里（运维用 {@code SADD} 维护），配置只声明"哪些路径受保护"。
 */
@Component
@ConfigurationProperties(prefix = "hmdp.risk.blacklist")
@Data
public class RiskBlacklistProperties {

    /** 受黑名单保护的路由前缀；为空则过滤器整体不生效 */
    private List<String> pathPrefixes = new ArrayList<>();

    /** 命中黑名单时返回给调用方的文案 */
    private String message = "当前账号或网络环境存在风险，已被限制访问";
}
```

- [ ] **Step 5: 实现过滤器**

创建 `gateway-service/src/main/java/com/hmdp/gateway/filter/RiskBlacklistFilter.java`：

```java
package com.hmdp.gateway.filter;

import cn.dev33.satoken.stp.StpUtil;
import com.hmdp.gateway.config.RiskBlacklistProperties;
import com.hmdp.utils.RedisConstants;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 网关风控黑名单（SPEC-15 P2-2）
 *
 * <p>在**限流判定之前**（{@code order = -20}，早于 {@code PathRateLimitFilter} 的 -10）
 * 做一次黑名单 {@code SISMEMBER}：黑名单是"已知恶意"的确定性结论，
 * 让它先于概率性的令牌桶生效，被拦截的原因在日志里才不含糊。
 *
 * <p>维度：IP 与登录用户。IP 维度不依赖 token，因此未登录的恶意流量也能被拦下
 * （这是限流器做不到的——它身份不可得时有意放行）。
 *
 * <p><b>fail-open</b>：Redis 查询异常一律放行（与 P0-1 限流同口径，SPEC-14 §6 验收 7）。
 * 风控是加固手段，不是主链路闸门；它的故障不得演变成秒杀入口 403 雪崩。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RiskBlacklistFilter implements GlobalFilter, Ordered {

    private final StringRedisTemplate stringRedisTemplate;
    private final RiskBlacklistProperties properties;
    private final MeterRegistry meterRegistry;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String matchedPrefix = matchPrefix(path);
        if (matchedPrefix == null) {
            return chain.filter(exchange);
        }

        try {
            String ip = resolveIp(exchange);
            if (ip != null && isBlacklisted(RedisConstants.RISK_BLACKLIST_IP_KEY, ip)) {
                return reject(exchange, matchedPrefix, "ip", ip);
            }
            String loginId = resolveLoginId(exchange);
            if (loginId != null && isBlacklisted(RedisConstants.RISK_BLACKLIST_USER_KEY, loginId)) {
                return reject(exchange, matchedPrefix, "user", loginId);
            }
        } catch (Exception e) {
            log.warn("风控黑名单查询异常，fail-open 放行: path={}", path, e);
        }
        return chain.filter(exchange);
    }

    /** @return 命中的受保护前缀；未命中返回 null */
    private String matchPrefix(String path) {
        List<String> prefixes = properties.getPathPrefixes();
        if (prefixes == null) {
            return null;
        }
        for (String prefix : prefixes) {
            if (prefix != null && !prefix.isBlank() && path.startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    private boolean isBlacklisted(String key, String identity) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(key, identity));
    }

    private String resolveIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return null;
        }
        return remote.getAddress().getHostAddress();
    }

    /**
     * 从 Authorization 解析 loginId。
     *
     * <p>与 {@code PathRateLimitFilter#resolveIdentity} 同口径：身份不可得返回 null，
     * 由 {@code SaTokenGatewayConfig} 在更后面对未登录流量统一处理，此处不重复鉴权。
     */
    private String resolveLoginId(ServerWebExchange exchange) {
        String token = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (token == null || token.isBlank()) {
            return null;
        }
        Object loginId = StpUtil.getLoginIdByToken(token);
        return loginId == null ? null : loginId.toString();
    }

    private Mono<Void> reject(ServerWebExchange exchange, String prefix, String dimension, String identity) {
        meterRegistry.counter("gateway.risk.blacklist.blocked",
                "path", prefix, "dimension", dimension).increment();
        log.warn("风控黑名单拦截: path={}, dimension={}, identity={}",
                exchange.getRequest().getPath().value(), dimension, identity);

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        String body = "{\"success\":false,\"errorMsg\":\"" + properties.getMessage() + "\",\"code\":403}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -20;
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn -pl gateway-service -am test -Dtest=RiskBlacklistFilterTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 7: 加配置**

在 `gateway-service/src/main/resources/application.yaml` 的 `hmdp:` 段末尾（`rate-limit.rules` 之后）追加：

```yaml
  # 风控黑名单（SPEC-15 P2-2）：命中即 403，判定先于限流。
  # 黑名单数据在 Redis Set 中，运维维护：
  #   redis-cli SADD risk:blacklist:user <loginId>
  #   redis-cli SADD risk:blacklist:ip   <ip>
  risk:
    blacklist:
      path-prefixes:
        - /voucher-order/seckill/
      message: 当前账号或网络环境存在风险，已被限制访问
```

- [ ] **Step 8: 运行 gateway 全量测试**

Run: `mvn -pl gateway-service -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 9: 提交**

```bash
git add common/src/main/java/com/hmdp/utils/RedisConstants.java \
        gateway-service/src/main/java/com/hmdp/gateway/config/RiskBlacklistProperties.java \
        gateway-service/src/main/java/com/hmdp/gateway/filter/RiskBlacklistFilter.java \
        gateway-service/src/main/resources/application.yaml \
        gateway-service/src/test/java/com/hmdp/gateway/filter/RiskBlacklistFilterTest.java
git commit -m "feat(gateway): P2-2 秒杀入口风控黑名单（IP/用户双维度，先于限流，fail-open）"
```

---

## Task 12: P1-1 压测手册 + JMeter 计划

**Files:**
- Create: `docs/loadtest/seckill-baseline.jmx`
- Create: `docs/loadtest/SPEC-15-P1-1-秒杀压测手册.md`

**Interfaces:**
- Consumes: 全部前序任务的产物
- Produces: 无代码接口

- [ ] **Step 1: 写 JMeter 计划**

创建 `docs/loadtest/seckill-baseline.jmx`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3">
  <hashTree>
    <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="SPEC-15 秒杀阶梯压测" enabled="true">
      <elementProp name="TestPlan.user_defined_variables" elementType="Arguments" guiclass="ArgumentsPanel" testclass="Arguments" testname="全局参数" enabled="true">
        <collectionProp name="Arguments.arguments">
          <elementProp name="HOST" elementType="Argument">
            <stringProp name="Argument.name">HOST</stringProp>
            <stringProp name="Argument.value">${__P(host,127.0.0.1)}</stringProp>
          </elementProp>
          <elementProp name="PORT" elementType="Argument">
            <stringProp name="Argument.name">PORT</stringProp>
            <stringProp name="Argument.value">${__P(port,8081)}</stringProp>
          </elementProp>
          <elementProp name="VOUCHER_ID" elementType="Argument">
            <stringProp name="Argument.name">VOUCHER_ID</stringProp>
            <stringProp name="Argument.value">${__P(voucherId,999999)}</stringProp>
          </elementProp>
          <elementProp name="THREADS" elementType="Argument">
            <stringProp name="Argument.name">THREADS</stringProp>
            <stringProp name="Argument.value">${__P(threads,100)}</stringProp>
          </elementProp>
          <elementProp name="TOKEN_POOL" elementType="Argument">
            <stringProp name="Argument.name">TOKEN_POOL</stringProp>
            <stringProp name="Argument.value">${__P(tokenPool,tokens.csv)}</stringProp>
          </elementProp>
        </collectionProp>
      </elementProp>
      <boolProp name="TestPlan.functional_mode">false</boolProp>
      <boolProp name="TestPlan.serialize_threadgroups">false</boolProp>
    </TestPlan>
    <hashTree>
      <CSVDataSet guiclass="TestBeanGUI" testclass="CSVDataSet" testname="令牌池" enabled="true">
        <stringProp name="filename">${TOKEN_POOL}</stringProp>
        <stringProp name="variableNames">TOKEN</stringProp>
        <boolProp name="recycle">true</boolProp>
        <boolProp name="stopThread">false</boolProp>
        <stringProp name="delimiter">,</stringProp>
      </CSVDataSet>
      <hashTree/>
      <ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="秒杀并发" enabled="true">
        <stringProp name="ThreadGroup.num_threads">${THREADS}</stringProp>
        <stringProp name="ThreadGroup.ramp_time">5</stringProp>
        <boolProp name="ThreadGroup.scheduler">true</boolProp>
        <stringProp name="ThreadGroup.duration">30</stringProp>
        <stringProp name="ThreadGroup.on_sample_error">continue</stringProp>
        <elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController" testname="循环" enabled="true">
          <boolProp name="LoopController.continue_forever">true</boolProp>
          <stringProp name="LoopController.loops">-1</stringProp>
        </elementProp>
      </ThreadGroup>
      <hashTree>
        <HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="请求头" enabled="true">
          <collectionProp name="HeaderManager.headers">
            <elementProp name="" elementType="Header">
              <stringProp name="Header.name">Authorization</stringProp>
              <stringProp name="Header.value">${TOKEN}</stringProp>
            </elementProp>
            <elementProp name="" elementType="Header">
              <stringProp name="Header.name">Content-Type</stringProp>
              <stringProp name="Header.value">application/json</stringProp>
            </elementProp>
          </collectionProp>
        </HeaderManager>
        <hashTree/>
        <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="POST /voucher-order/seckill/{id}" enabled="true">
          <stringProp name="HTTPSampler.domain">${HOST}</stringProp>
          <stringProp name="HTTPSampler.port">${PORT}</stringProp>
          <stringProp name="HTTPSampler.protocol">http</stringProp>
          <stringProp name="HTTPSampler.method">POST</stringProp>
          <stringProp name="HTTPSampler.path">/voucher-order/seckill/${VOUCHER_ID}</stringProp>
          <boolProp name="HTTPSampler.follow_redirects">true</boolProp>
          <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
        </HTTPSamplerProxy>
        <hashTree/>
        <ResultCollector guiclass="SummaryReport" testclass="ResultCollector" testname="汇总报告" enabled="true">
          <objProp>
            <name>saveConfig</name>
            <value class="SampleSaveConfiguration">
              <time>true</time><latency>true</latency><timestamp>true</timestamp>
              <success>true</success><label>true</label><code>true</code>
              <message>true</message><threadName>false</threadName>
              <dataType>false</dataType><encoding>false</encoding>
              <assertions>false</assertions><subresults>false</subresults>
              <responseData>false</responseData><samplerData>false</samplerData>
              <xml>false</xml><fieldNames>true</fieldNames>
            </value>
          </objProp>
          <stringProp name="filename">${__P(resultFile,seckill-baseline-result.jtl)}</stringProp>
        </ResultCollector>
        <hashTree/>
      </hashTree>
    </hashTree>
  </hashTree>
</jmeterTestPlan>
```

- [ ] **Step 2: 写压测手册**

创建 `docs/loadtest/SPEC-15-P1-1-秒杀压测手册.md`，内容必须包含以下小节（逐条给出可直接复制的命令）：

````markdown
# SPEC-15 P1-1 · 秒杀接口压测手册

> 对应 SPEC-15 §2.1 / §5.2 E4 / §6 验收 1。**必须实测**，不接受仅单元测试通过。

## 0. 前置检查

```bash
# 凭据：本手册所有 redis/mysql 命令都读 $REDIS_PASSWORD / $MYSQL_PASSWORD，
# 值在仓库根目录的 .env 里。不 source 的话它们为空，redis-cli 会 NOAUTH、mysql 会语法错。
set -a; source .env; set +a

# 中间件
docker ps --format '{{.Names}}' | grep -E 'hmdp-(mysql|redis|nacos|rocketmq)'
# 6 个 Java 服务（网关 8081 / voucher 8083 / order 8084）
curl -s http://127.0.0.1:8081/actuator/health | head -c 200

# 黑名单必须为空（SPEC-15 P2-2）：网关在限流**之前**查 risk:blacklist:ip / risk:blacklist:user，
# 命中直接 403。若压测机 IP 或任一测试账号在黑名单里，整轮压测会被 403 挡掉，
# 而操作者极易把它误读成"秒杀链路故障"。
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" SMEMBERS risk:blacklist:ip
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" SMEMBERS risk:blacklist:user
# 两者都应返回空集合；非空则先 SREM 移除压测机 IP / 测试用户
```

**⚠️ Nacos 覆盖检查（必做）**：`order-service/src/main/resources/bootstrap.yaml` 中有
`spring.config.import: optional:nacos:order-service.yaml`。若 Nacos 上存在同名 data-id
且其内容含 `datasource` / `redis` 配置，**它会覆盖本地的池化参数**，Task 1 的改动不会生效。

```bash
curl -s "http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=order-service.yaml&group=DEFAULT_GROUP" | head -40
curl -s "http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=voucher-service.yaml&group=DEFAULT_GROUP" | head -40
```
若返回非空且含池化键，先在 Nacos 上同步成 Task 1 的值，再继续。

**生效验证**（本地改动真的到了运行时）：
```bash
curl -s http://127.0.0.1:8084/actuator/configprops | grep -o '"maximum-pool-size":[0-9]*'
curl -s http://127.0.0.1:8084/actuator/configprops | grep -o '"max-active":[0-9]*'
curl -s http://127.0.0.1:8084/actuator/configprops | grep -o '"threads.max":[0-9]*'
```

## 1. 隔离测试券（禁止打真实券）

```sql
-- 独立券，与业务券完全隔离
INSERT INTO tb_voucher (shop_id, title, sub_title, rules, pay_value, actual_value, type, status)
VALUES (1, 'SPEC-15 压测专用券', '压测', '压测', 1, 10, 1, 1);
-- 记下自增 id，下称 <VID>
INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time)
VALUES (<VID>, 100000, NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 1 DAY);
-- 预热 Redis 库存与时间窗
```

```bash
VID=<VID>
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" SET "seckill:stock:$VID" 100000
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" DEL "seckill:order:$VID" "seckill:order:detail:$VID"

# 活动时间窗（SPEC-14 P0-3）：入口在调用 Lua **之前**会读 seckill:window:{vid} 判定
# begin <= now <= end。用裸 SQL 造券绕开了 VoucherServiceImpl.writeSeckillWindow()，
# 这个 key 不会被自动写入 —— 必须手工铺，否则入口按"无窗口"放行（能跑，但没测到时间窗路径）。
NOW_MS=$(($(date +%s) * 1000))
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" HSET "seckill:window:$VID" \
  begin $((NOW_MS - 3600000)) end $((NOW_MS + 86400000))
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" EXPIRE "seckill:window:$VID" 172800
```

**令牌池** `docs/loadtest/tokens.csv`：每行一个已登录用户的 `Authorization` token，
行数 **≥ 并发数**（限流按 loginId 维度 5 QPS，同一 token 会被限流器挡住 —— 这是预期行为，
故压测必须用多用户池，否则测的是限流器而不是秒杀链路）：

```bash
# 生成 N 个测试用户并登录，导出 token。
#
# 【为什么不走 POST /user/code】sendCode 有 **IP 维度频控：24 小时 20 次**
# （UserServiceImpl 的 CODE_IP_MAX_PER_DAY=20）。本机循环 1000 次全部来自同一个 IP，
# 第 21 个号码起 sendCode 直接返回"验证码发送过于频繁"，验证码从未写入 Redis →
# 后续登录全部失败。更阴险的是失败是**静默**的：每个号码仍会往 csv 追加一个空行，
# 1000 个号码照样得到 1000 行，看行数以为成功了，实际只有 20 个有效 token，
# 而"每 loginId 5 QPS"的限流会把结果染成限流假象。
#
# 所以直接**播种测试夹具**：自己往 Redis 写验证码，跳过 sendCode。
# 这与本手册其它地方用 redis-cli 铺 seckill:stock / seckill:window 是同一手法。
# 登录接口本身不校验"是否发送过"，只比对 Redis 里的值。
FIXED_CODE=123456
rm -f docs/loadtest/tokens.csv
for i in $(seq 1 1000); do
  PHONE="13$(printf '%09d' $i)"
  docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" \
    SETEX "login:code:$PHONE" 300 "$FIXED_CODE" > /dev/null
  curl -s -X POST "http://127.0.0.1:8081/user/login" \
       -H 'Content-Type: application/json' \
       -d "{\"phone\":\"$PHONE\",\"code\":\"$FIXED_CODE\"}" \
    | grep -o '"data":"[^"]*"' | cut -d'"' -f4 >> docs/loadtest/tokens.csv
done

# 校验：必须数**非空行**。数 `wc -l` 是错的 —— 失败时每行都是空行，行数照样是 1000，
# 校验形同虚设（这正是上一版脚本的缺陷）。
TOTAL=$(grep -c '[^[:space:]]' docs/loadtest/tokens.csv)
echo "有效 token 数 = $TOTAL（必须 >= 并发数 1000/500/100）"
[ "$TOTAL" -ge 1000 ] || echo "!! token 池不足，先排查登录失败原因再压测"
```

> `login:code:` 的键前缀见 `RedisConstants.LOGIN_CODE_KEY`；TTL 给 300 秒足够覆盖整段建池时间。
> 这也意味着**不要**用 `/user/code` 接口来生成令牌池 —— 它是产品功能，带频控；
> 夹具铺设走 redis-cli 才是确定性的。

## 2. 阶梯压测

```bash
# VID 与 tokens.csv 必须在**同一个 shell** 里有效：下面 `cd docs/loadtest` 之后
# `-JvoucherId=$VID` 依赖它仍在环境中。若分块执行，请先 `export VID=<VID>`。
cd docs/loadtest
for T in 100 500 1000; do
  echo "=== 并发 $T ==="
  "$JMETER_HOME/bin/jmeter" -n -t seckill-baseline.jmx \
    -Jhost=127.0.0.1 -Jport=8081 -JvoucherId=$VID -Jthreads=$T \
    -JtokenPool=tokens.csv -JresultFile=result-$T.jtl \
    -l jmeter-$T.log
  # 汇总：QPS / p95 / p99 / 错误率。
  # 刻意**不用** gawk 的 asort()（非 POSIX，mawk/busybox awk 会报 function not defined），
  # 改成管道 + sort -n，任何 awk 都能跑。
  awk -F, 'NR>1{t++; if($8!="true")e++; s+=$2; print $2}' result-$T.jtl \
    | sort -n > /tmp/spec15-elapsed-$T.txt
  N=$(wc -l < /tmp/spec15-elapsed-$T.txt)
  P95=$(sed -n "$((N*95/100))p" /tmp/spec15-elapsed-$T.txt)
  P99=$(sed -n "$((N*99/100))p" /tmp/spec15-elapsed-$T.txt)
  awk -F, -v n="$N" -v p95="$P95" -v p99="$P99" \
    'NR>1{t++; if($8!="true")e++; s+=$2}
     END{printf "样本=%d 错误=%d 错误率=%.3f%% avg=%.1fms p95=%.1fms p99=%.1fms\n",
     t,e,e*100/t,s/t,p95,p99}' result-$T.jtl
done
```

**采集服务端指标（每档压测前后各一次）**：
```bash
# 只有 order-service(8084) 暴露 prometheus —— 网关(8081) 暴露 health,info,gateway，
# voucher-service(8083) 暴露 seata,health,info，两者都没有 prometheus 端点。
# 秒杀的全部计数都在 order-service 侧，采它一个就够。
curl -s http://127.0.0.1:8084/actuator/prometheus \
  | grep -E '^seckill_(request|success|fail|stock_insufficient|duplicate_order|mq_send_fail|mq_consume_success|dlq_consumed|feign_call|outbox)|^hikaricp_connections_' \
  | head -40

# 网关的限流/风控拦截数在网关侧，但它没有 prometheus 端点，只能看日志：
#   grep -E '网关限流触发|风控黑名单拦截' <gateway-service 日志>
```

## 3. 压测后必做：对账等式断言（超卖数必须为 0）

```bash
# 触发一次对账。**必须带 admin 身份**：SeckillConsistencyController 类上是
# @SaCheckRole("admin")，不带 Authorization 只会拿到 401/403，判定段无法执行。
#
# admin 是谁由 hmdp.admin-user-ids 决定（本仓库 .env 里是 1 = loginId 1）。
# tb_user 没有 seed 行、登录时按手机号自动建号，所以要**反查**这个 id 对应的手机号再登录：
ADMIN_PHONE=$(docker exec hmdp-mysql mysql -N -B -uroot -p"$MYSQL_PASSWORD" \
  -e "SELECT phone FROM hmdp.tb_user WHERE id IN (${ADMIN_USER_IDS:-1}) LIMIT 1")
if [ -z "$ADMIN_PHONE" ]; then
  echo "!! ids=${ADMIN_USER_IDS:-1} 在 tb_user 中不存在；先手动指定一个已存在的用户并把其 id 加入 ADMIN_USER_IDS"
fi
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" \
  SETEX "login:code:$ADMIN_PHONE" 300 123456 > /dev/null
ADMIN_TOKEN=$(curl -s -X POST "http://127.0.0.1:8081/user/login" \
  -H 'Content-Type: application/json' \
  -d "{\"phone\":\"$ADMIN_PHONE\",\"code\":\"123456\"}" \
  | grep -o '"data":"[^"]*"' | cut -d'"' -f4)
echo "ADMIN_TOKEN 长度 = ${#ADMIN_TOKEN}（为 0 说明没拿到 token）"

curl -s -X POST "http://127.0.0.1:8081/seckill/consistency/stock/sync/$VID" \
     -H "Authorization: $ADMIN_TOKEN" | head -c 500
```
期望：响应 `success=true`，日志出现「库存同步成功」且**分歧为 0**；Redis 库存 = DB 库存 − 在途订单数。

```bash
# 三方读数
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" GET "seckill:stock:$VID"
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" HLEN "seckill:order:detail:$VID"
docker exec hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD" \
  -e "SELECT stock FROM hmdp.tb_seckill_voucher WHERE voucher_id=$VID"
docker exec hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD" \
  -e "SELECT COUNT(*) FROM hmdp.tb_voucher_order WHERE voucher_id=$VID"
```
**判定（两条同时成立才算通过）**：
1. `Redis库存 == DB库存 − 明细Hash条数`（对账等式，这是主判据）；
2. `tb_voucher_order` 行数 ≤ 100000 且**等于** `100000 − Redis库存`（真正判超卖要看"卖出的份数
   与库存减少量一致"，而不是只比一个上界 —— 只卖出 8 万份时 `行数 <= 100000` 也成立，
   那个写法查不出超卖也查不出少卖）。

> 注意 `明细Hash条数 == 0` 只在**没有死信**时成立：正常消费会 HDEL 掉明细，
> 但"重投耗尽 → 安全释放"与 DLQ 回写会留下条目。若对账后 `明细Hash条数 > 0`，
> 先去 `seckill:order:pending` 与 `tb_seckill_outbox` 查有没有需人工处置的单，
> **不要**直接判定为链路故障。

## 4. 清理（必须执行，避免污染真实库存）

```bash
VID=<VID>
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" DEL \
  "seckill:stock:$VID" "seckill:order:$VID" "seckill:order:detail:$VID" \
  "seckill:window:$VID" "seckill:deduct:$VID" "seckill:order:pending"
docker exec hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD" -e "
  DELETE FROM hmdp.tb_voucher_order WHERE voucher_id=$VID;
  DELETE FROM hmdp.tb_seckill_outbox WHERE voucher_id=$VID;
  DELETE FROM hmdp.tb_seckill_voucher WHERE voucher_id=$VID;
  DELETE FROM hmdp.tb_voucher WHERE id=$VID;"

# 风控黑名单：若你在第 0 节的检查里 SREM 过、或验证 P2-2 时 SADD 过，这里必须还原，
# 否则残留条目会持续 403 污染后续任何走 /voucher-order/seckill/ 的操作。
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" DEL risk:blacklist:user risk:blacklist:ip
# 释放墓碑（键是 orderId 不是 voucherId，无法按 VID 批量删）：24h TTL 会自动收敛，
# 不做处理；此处仅说明为何清理后仍能看到 seckill:released:* 的残留键。
```

## 5. 归因方法（拐点在哪）

| 现象 | 首要嫌疑 | 看哪里 |
|---|---|---|
| QPS 到某点后不涨、p99 陡增 | Hikari 池耗尽 | `hikaricp_connections_pending > 0` |
| connectTimeout 类错误 | Lettuce 池耗尽 | `lettuce` 连接数指标 / Tomcat 忙线程数 |
| 429 大幅上升 | 网关限流（**不是**秒杀瓶颈） | 网关日志 `网关限流触发`（网关无 prometheus 端点） |
| **403 大幅上升** | **风控黑名单命中**（SPEC-15 P2-2，**不是**秒杀故障） | 网关日志 `风控黑名单拦截`；核对第 0 节的两个 `SMEMBERS` |
| 库存不足占比高 | 券卖完了 | `seckill.stock.insufficient` |
| 成功数 > 库存 | 超卖 | 立即停止压测并跑第 3 节对账 |
````

- [ ] **Step 3: 验证 jmx 可被 JMeter 解析**

Run: `"$JMETER_HOME/bin/jmeter" -n -t docs/loadtest/seckill-baseline.jmx -Jthreads=1 -JvoucherId=1 -JtokenPool=docs/loadtest/tokens.csv -l /tmp/spec15-smoke.jtl 2>&1 | tail -20`
Expected: 能启动并输出 summary（若服务未起会报连接失败，那是预期的；关键是没有 XML 解析错误）

> 若 `JMETER_HOME` 未设置，用 `command -v jmeter` 找到的绝对路径。

- [ ] **Step 4: 提交**

```bash
git add docs/loadtest/seckill-baseline.jmx docs/loadtest/SPEC-15-P1-1-秒杀压测手册.md
git commit -m "docs(seckill): P1-1 阶梯压测手册与 JMeter 计划模板"
```

---

## Task 13: 端到端验证手册 + 全量回归

**Files:**
- Create: `docs/loadtest/SPEC-15-验证手册.md`

**Interfaces:**
- Consumes: 全部前序任务
- Produces: 无

- [ ] **Step 1: 写验证手册**

创建 `docs/loadtest/SPEC-15-验证手册.md`：

````markdown
# SPEC-15 端到端验证手册

> 覆盖 SPEC-15 §5.2 中标注「必须实测」的 E4 / E6 / E7 / E8 / E9。
> 每条给出**操作 → 期望 → 反例（怎么知道它没生效）**。启动配方见
> `.e2e/` 既有脚本；网关鉴权在路由前短路，**不带 token 的请求一律假绿**，
> 所有 curl 必须带 `Authorization`。

## 通用准备

```bash
BASE=http://127.0.0.1:8081
TOKEN=<登录后拿到的 token>
VID=<测试券 id>
```

## E4 · 阶梯压测 100/500/1000 并发

见 `SPEC-15-P1-1-秒杀压测手册.md` 全篇。
**期望**：输出 QPS/p95/p99/错误率表；超卖数 = 0；对账等式成立。

## E6 · 停 voucher-service 后发起秒杀

```bash
# 1) 记录基线
curl -s http://127.0.0.1:8084/actuator/prometheus | grep '^seckill_feign_call'
# 2) 停掉 voucher-service
kill "$(jps -l | grep voucher-service | awk '{print $1}')"
# 3) 连续发起 20 次秒杀（每次会走到消费者的 deductStock）
for i in $(seq 1 20); do
  curl -s -X POST "$BASE/voucher-order/seckill/$VID" -H "Authorization: $TOKEN" -o /dev/null
done
# 4) 观察
curl -s http://127.0.0.1:8084/actuator/prometheus | grep -E '^seckill_(feign_call|mq_consume_fail)'
tail -100 <order-service 日志> | grep -E 'Feign 失败率超阈值|库存服务调用失败'
```
**期望**：每次失败耗时 ≈ `readTimeout(2s)` 而不是 5s；`seckill.feign.call{result="fail"}` 增长；
出现「Feign 失败率超阈值」WARN（连续 ≥10 次失败且失败率 ≥50%）。
**反例**：若日志里每次耗时仍是 ~5000ms，说明 Task 3 的 Feign 超时没生效
（回去查 Nacos 是否覆盖了 `feign.client.config`）。
**恢复**：重新启动 voucher-service。

## E7 · 热点券 key 过期瞬间并发查询

```bash
# 1) 灌入少量券，让 key 处于"有缓存"状态
curl -s "$BASE/voucher/$VID" -H "Authorization: $TOKEN" -o /dev/null
# 2) 删掉 L2，制造"缓存刚过期"的瞬间
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" DEL "cache:voucher:$VID"
# 3) 100 并发同时查
seq 1 100 | xargs -P 100 -I{} curl -s "$BASE/voucher/$VID" -H "Authorization: $TOKEN" -o /dev/null
# 4) 看 DB 回源次数（MySQL 侧）
docker exec hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD" \
  -e "SHOW GLOBAL STATUS LIKE 'Com_select'"
# 5) 看 Hikari 峰值
curl -s http://127.0.0.1:8083/actuator/prometheus | grep '^hikaricp_connections_(active|pending)'
```
**期望**：`Com_select` 增量 ≈ 1（而不是 100）；`hikaricp_connections_active` 无明显尖峰。
**反例**：增量接近 100 → 互斥锁没生效（查 `MultiLevelCache` 的 `rebuildLock` 是否被装配）。
**注意**：本项测的是**券元信息**（`cache:voucher:`）。`seckill:stock` **不在缓存内**，
若看到它参与命中率统计，说明有人给强一致读接了缓存 → 立即跑 Task 7 的契约测试。

## E8 · kill 秒杀生产端后重启（P2-1 形态 D1-b）

验证"落库成功但投递未完成"的窗口由补投器兜住。

```bash
# 1) 让 MQ 暂时不可达，制造"落库成功但投递失败并回滚"以外的场景：
#    更准确的做法是直接制造"落库成功、进程立刻崩溃"——用 kill -9 打断在
#    sendSeckillOrderMessage 与 markOutboxDelivered 之间。
#    实操：在 order-service 日志里盯住 "秒杀资格校验通过"，随后立即 kill -9
OID=$(curl -s -X POST "$BASE/voucher-order/seckill/$VID" -H "Authorization: $TOKEN" | grep -o '[0-9]\+')
kill -9 "$(jps -l | grep order-service | awk '{print $1}')"

# 2) 确认事件行停在待投递
docker exec hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD" \
  -e "SELECT id,status,retry_count FROM hmdp.tb_seckill_outbox WHERE id=$OID"
#   期望：status=0（若为 1，说明投递已完成，换一次重试）

# 3) 重启 order-service，等 ≥ 补投间隔(3s) + 退避(30s)
# 4) 观察补投
docker exec hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD" \
  -e "SELECT id,status,retry_count FROM hmdp.tb_seckill_outbox WHERE id=$OID"
docker exec hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD" \
  -e "SELECT id,user_id,voucher_id,status FROM hmdp.tb_voucher_order WHERE id=$OID"
curl -s http://127.0.0.1:8084/actuator/prometheus | grep '^seckill_outbox'
```
**期望**：事件行 `status` 变为 1、`tb_voucher_order` 出现该 orderId、
`seckill.outbox.redelivered` ≥ 1、`seckill.outbox.exhausted` = 0。
**反例**：行长期停在 `status=0` 且 `retry_count` 不涨 → 补投器没被注册
（跑 `SeckillSchedulingContractTest`）。
**写入文档的诚实口径**：丢失窗口 = 0（消息不会丢），但补投**延迟** ≈
扫描间隔(3s) + 行内退避(30s)，不是"零延迟"。与 P0-2 的 T+120s 相比是收敛，不是消除等待。

## E9 · 黑名单用户被拦截且零误杀

```bash
# 1) 基线：正常用户可下单
curl -s -X POST "$BASE/voucher-order/seckill/$VID" -H "Authorization: $TOKEN" | head -c 200

# 2) 加入黑名单（运维手工维护）
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" SADD risk:blacklist:user <loginId>

# 3) 同一用户再发起 → 期望 403
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE/voucher-order/seckill/$VID" -H "Authorization: $TOKEN"
#   期望输出：403
curl -s http://127.0.0.1:8081/actuator/prometheus | grep 'gateway_risk_blacklist_blocked'

# 4) 误杀检查：另一个未在名单的用户必须仍能正常请求（200/业务错误码，但**不是** 403）
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE/voucher-order/seckill/$VID" -H "Authorization: $OTHER_TOKEN"

# 5) IP 维度
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" SADD risk:blacklist:ip 127.0.0.1

# 6) 清理（必须）
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" SREM risk:blacklist:user <loginId>
docker exec hmdp-redis redis-cli -a "$REDIS_PASSWORD" SREM risk:blacklist:ip 127.0.0.1
```
**期望**：黑名单用户 403 且指标 +1；未在黑名单的用户不受影响（零误杀）。
**反例**：请求带 token 通过网关返回 200 而 Redis 里确实 SADD 了 → 过滤器顺序或路径前缀配错
（`hmdp.risk.blacklist.path-prefixes` 必须覆盖 `/voucher-order/seckill/`）。
**注意**：黑名单判定用了 loginId，需保证 Sa-Token 会话在 Redis 可用；token 失效时
`resolveLoginId` 返回 null 会跳过 user 维度 —— 这是有意设计（未登录流量由 SaToken 拒绝）。

## 验收对照

| SPEC-15 §6 验收 | 类型 | 本手册对应 |
|---|---|---|
| 1 QPS/p95/p99/错误率基线，超卖数 = 0 | 必须实测 | E4 |
| 2 N 并发同 key → loader 1 次 | 单测 | `MultiLevelCacheTest#并发同key回源时loader只调用一次` |
| 3 券元信息命中率可观测，回源 DB QPS 下降 | 必须实测 | E7 |
| 4 getSeckillStock 无缓存注解 | 契约测试 | `SeckillStockNoCacheContractTest` |
| 5 voucher 下线时快速失败，无线性堆积 | 必须实测 | E6 |
| 6（P1-3）Cluster 无 CROSSSLOT | 不适用 | **本批跳过 P1-3**（SPEC-15 §3 D4 选 B） |
| 7 kill 消费端后重启，消息不丢 | 必须实测 | E8 |
| 8 黑名单拦截 + 零误杀 | 必须实测 | E9 |
````

- [ ] **Step 2: 全量回归**

Run:
```bash
mvn -pl common,order-service,voucher-service,gateway-service,shop-service -am test \
  -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: BUILD SUCCESS，全部测试通过

- [ ] **Step 3: 确认没有误伤无关模块**

Run: `git status --short`
Expected: 变更文件全部落在本计划的文件清单内（加上会话开始前就存在的未提交改动）；**没有** `seckill.lua`、`SeckillKeyContractTest.java`、任何既有表 DDL

- [ ] **Step 4: 提交**

```bash
git add docs/loadtest/SPEC-15-验证手册.md
git commit -m "docs(seckill): SPEC-15 端到端验证手册（E4/E6/E7/E8/E9）"
```

- [ ] **Step 5: 把实测结果回填进手册**

按 E4/E6/E7/E8/E9 顺序实测，把每条的**实测值**（QPS、p99、失败延迟、Com_select 增量、
补投时间、拦截状态码）追加到手册对应小节末尾，标注执行日期与环境。
若某项未执行，**明确写"未执行"并说明原因**，不要留空 —— 留空会被读成"已通过"。

---

## Self-Review

**1. Spec coverage**

| SPEC-15 条目 | 计划位置 |
|---|---|
| P1-1 池化配置 | Task 1 |
| P1-1 压测基线（工具在仓库外） | Task 12 |
| P1-1 指标（已有 21 个计数器，无需补） | Task 1 说明；Task 2/10 只增不重复 |
| P1-2 互斥重建 | Task 4、Task 5 |
| P1-2 券元信息接入 | Task 6 |
| P1-2 库存不走缓存（类注释 + 契约测试） | Task 7 |
| P1-3 Cluster 兼容 | **明确不做**（spec §3 D4 选 B），Global Constraints 锁定 |
| P1-4 Feign 超时收紧 | Task 3 |
| P1-4 消费端重试语义保持 | Task 3 Step 7（只加观测，不动 throw） |
| P1-4 失败率告警 | Task 2、Task 3 |
| P2-1 本地事件表 | Task 8、Task 9、Task 10 |
| P2-2 风控黑名单 | Task 11 |
| §5.2 单测：缓存并发 / Feign 告警 / 黑名单 / 事件表 | Task 5 U12 / Task 2 / Task 11 / Task 9 |
| §5.2 契约测试：库存无缓存 | Task 7 |
| §5.2 E4/E6/E7/E8/E9 | Task 12、Task 13 |
| §6 验收 1–8 | Task 13 验收对照表逐条映射 |

**2. Placeholder scan**：无 TBD / TODO / "稍后补充" / "类似 Task N"。所有代码块为可直接粘贴的完整实现。

**3. Type consistency**

- `CacheRebuildLock.tryLock/unlock` —— Task 4 定义，Task 5 消费，签名一致。
- `MultiLevelCache` 构造签名 —— Task 5 Step 3 定义 6 参（name, valueType, redisTemplate, publisher, rebuildLock, properties），Task 5 Step 1 的 U12/U13 与 Step 4 的工厂调用均按此顺序。
- `SeckillMetrics.incrementFeignCallSuccess/Fail` —— Task 2 定义，Task 3 经 `FeignFailureRateMonitor` 消费。
- `SeckillMetrics.incrementOutboxRedelivered/RedeliverFail/Exhausted` —— Task 10 Step 3 定义，Task 10 Step 4 消费，测试同名。
- `SeckillOutboxDeliverer.MAX_RETRY` —— Task 10 Step 4 定义为 `static final int = 5`，Step 1 测试引用 `SeckillOutboxDeliverer.MAX_RETRY`，包级可见 + 同包测试，可访问。
- `VoucherCacheService.getById/evict` —— Task 6 定义，Task 6 Step 6 的 Controller 消费。
- `RiskBlacklistProperties.pathPrefixes/message` —— Task 11 Step 4 定义，Step 5 过滤器消费，Step 2 测试 `setPathPrefixes/setMessage`（Lombok `@Data`）。
- `OUTBOX_STATUS_PENDING/DELIVERED` vs `SeckillOutboxDeliverer.STATUS_PENDING/DELIVERED` —— 两个类各自私有/包级常量，值同为 0/1，无符号冲突；Task 10 注释已说明同义关系。

**4. 已识别的张力（如实记录，未隐藏）**

- P2-1 声明为"投递与本地状态同事务"，但项目生产端只有这一处 DB 写入，实际语义是"先落库再投递"。Task 9 的代码注释与 Task 13 的 E8 口径均已写明，**不宣称两阶段提交**。
- 补投延迟 ≈ 33s（3s 扫描 + 30s 行内退避），不是 0 延迟。E8 已写明。
- `voucher-service/application.yaml` 中 `min-idle: 1` 行尾的既有脏字符 `8907` 属本任务无关的既有问题，**不清理**（CLAUDE.md §3），仅在此记录。
