# 秒杀链路接入 RocketMQ 事务消息 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把秒杀入口的投递从 `syncSend` 换成 `sendMessageInTransaction`，让「本地事件行落库」与「消息可投递」由 broker 的 commit/rollback 绑定，并保留补投器作为「事务状态未知」时的快速兜底。

**Architecture:** half message 先落 broker → broker 回调 `executeLocalTransaction`，在里面 INSERT `tb_seckill_outbox` 并返回 COMMIT/ROLLBACK → broker 据此投递或丢弃。崩溃导致 broker 收不到状态时由 broker 回查 `checkLocalTransaction`（查行存在性）；回查默认要 6s+60s，故 30s 判龄的补投器仍是更快的兜底。**不新增任何 bean 或配置**——现有 `rocketMQTemplate` 本就是 `TransactionMQProducer`。

**Tech Stack:** Java 21 · Spring Boot 3.1.12 · rocketmq-spring-boot-starter 2.2.3 · rocketmq-client 4.9.4 · MyBatis-Plus 3.5.6 · JUnit 5 + Mockito

**Spec:** `docs/specs/SPEC-16-秒杀事务消息.md`（v1.1）

## Global Constraints

- 分支 `spec16-seckill-tx`，基线 `master@bfa6ddc`。
- **禁止**为事务消息新建第二个 producer 或第二个 `RocketMQTemplate`。SPEC-16 F1：现有 `rocketMQTemplate` 已是 `TransactionMQProducer`；F1b：同一 JVM 内两个 producer 共用组会在启动时抛 `MQClientException("... has been created before, specify another name please.")`。
- **禁止**新增任何配置项（SPEC-16 §2.5；依据 SPEC-15 双轴评审约束「新增配置项须对应 spec 功能点」）。
- 失败路径的顺序不可调换：**先删事件行，再回滚 Redis 预扣**。
- 测试全量命令：`mvn -pl order-service -am test`。DB 集成测试用 `-Dtest=` 指定。
- 所有新增/修改的注释用中文，与仓库既有风格一致。

---

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `order-service/src/main/java/com/hmdp/order/mq/SeckillOrderProducer.java` | 修改 | 新增事务发送方法（普通 `syncSend` 保留给补投器） |
| `order-service/src/main/java/com/hmdp/order/mq/SeckillOrderTransactionListener.java` | 新建 | 本地事务（INSERT 事件行）与回查判定 |
| `order-service/src/main/java/com/hmdp/order/metrics/SeckillMetrics.java` | 修改 | 新增回查结果计数器 |
| `order-service/src/main/java/com/hmdp/order/service/impl/VoucherOrderServiceImpl.java` | 修改 | 入口改为事务消息；失败分支按 `localTransactionState` 分档 |
| `order-service/src/test/java/com/hmdp/order/mq/SeckillOrderProducerTest.java` | 新建 | 事务发送方法单测 |
| `order-service/src/test/java/com/hmdp/order/mq/SeckillOrderTransactionListenerTest.java` | 新建 | 本地事务 / 回查判定单测（真字节 payload） |
| `order-service/src/test/java/com/hmdp/order/service/impl/SeckillVoucherServiceTest.java` | 修改 | mock 点由 `boolean` 改为 `TransactionSendResult` |
| `.e2e/rocketmq/broker.conf` | 修改 | 调小回查参数，使回查在秒级可观察（仅 E2E 环境） |

---

### Task 1: 事务消息发送方法

**Files:**
- Modify: `order-service/src/main/java/com/hmdp/order/mq/SeckillOrderProducer.java`
- Test: `order-service/src/test/java/com/hmdp/order/mq/SeckillOrderProducerTest.java`（新建）

**Interfaces:**
- Consumes: 无
- Produces: `SeckillOrderProducer#sendSeckillOrderMessageInTransaction(SeckillOrderMessage) → TransactionSendResult`；主题常量沿用 `SeckillOrderProducer.TOPIC_SECKILL_ORDER = "seckill-order-topic"`

- [ ] **Step 1: 写失败的测试**

新建 `order-service/src/test/java/com/hmdp/order/mq/SeckillOrderProducerTest.java`：

```java
package com.hmdp.order.mq;

import com.hmdp.dto.SeckillOrderMessage;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.Message;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeckillOrderProducerTest {

    @Mock private RocketMQTemplate rocketMQTemplate;
    @InjectMocks private SeckillOrderProducer producer;

    @Test
    void 事务发送走秒杀主题且arg传null_本地事务靠消息体取参不带外参数() {
        TransactionSendResult expected = new TransactionSendResult();
        expected.setLocalTransactionState(LocalTransactionState.COMMIT_MESSAGE);
        when(rocketMQTemplate.sendMessageInTransaction(eq("seckill-order-topic"), any(), isNull()))
                .thenReturn(expected);

        TransactionSendResult actual =
                producer.sendSeckillOrderMessageInTransaction(new SeckillOrderMessage(9001L, 7L, 1L));

        assertSame(expected, actual, "必须把 template 返回的事务结果原样交给调用方，入口据此分档");

        ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).sendMessageInTransaction(
                eq("seckill-order-topic"), captor.capture(), isNull());
        // 回查路径拿不到 arg（broker 只回传消息体），故本地事务也必须能从消息体取参。
        // 这里锁定 arg 为 null，防止后续"顺手"把 orderId 挪进 arg 导致回查判定失效。
        assertInstanceOf(SeckillOrderMessage.class, captor.getValue().getPayload());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl order-service -am test -Dtest=SeckillOrderProducerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败，`找不到符号: 方法 sendSeckillOrderMessageInTransaction(...)`

- [ ] **Step 3: 实现**

在 `SeckillOrderProducer.java` 的 `sendSeckillOrderMessageAsync` 方法之前插入：

```java
    /**
     * 事务消息发送秒杀订单消息（SPEC-16）。
     *
     * <p><b>与 {@link #sendSeckillOrderMessage} 的区别</b>：本方法先把 half message 落到 broker，
     * broker 回调 {@code SeckillOrderTransactionListener#executeLocalTransaction} 写本地事件行，
     * 再按返回的 COMMIT/ROLLBACK 决定投递或丢弃。因此本地写入与"消息可投递"由 broker 绑定，
     * 而 syncSend 的"落库后崩溃"窗口要靠补投器兜。
     *
     * <p><b>arg 恒为 null</b>：broker 回查时只回传消息体、不回传 arg，若把 orderId 放进 arg，
     * 回查侧就拿不到它。两个回调统一从消息体解析（{@code convertToSpringMessage} 的 payload
     * 是原始 byte[]），传 null 是为了让"唯一数据来源是消息体"这件事在调用点上显而易见。
     *
     * @return broker 返回的事务结果；调用方按 {@code sendStatus} 与 {@code localTransactionState} 分档
     * @throws org.springframework.messaging.MessagingException half message 发送失败
     */
    public TransactionSendResult sendSeckillOrderMessageInTransaction(SeckillOrderMessage message) {
        return rocketMQTemplate.sendMessageInTransaction(
                TOPIC_SECKILL_ORDER,
                MessageBuilder.withPayload(message).build(),
                null
        );
    }
```

新增 import：

```java
import org.apache.rocketmq.client.producer.TransactionSendResult;
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl order-service -am test -Dtest=SeckillOrderProducerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS，1 test

- [ ] **Step 5: 提交**

```bash
git add order-service/src/main/java/com/hmdp/order/mq/SeckillOrderProducer.java \
        order-service/src/test/java/com/hmdp/order/mq/SeckillOrderProducerTest.java
git commit -m "feat(seckill): 新增事务消息发送方法（SPEC-16 T1）"
```

---

### Task 2: 事务监听器（本地事务 + 回查）

**Files:**
- Create: `order-service/src/main/java/com/hmdp/order/mq/SeckillOrderTransactionListener.java`
- Modify: `order-service/src/main/java/com/hmdp/order/metrics/SeckillMetrics.java`
- Test: `order-service/src/test/java/com/hmdp/order/mq/SeckillOrderTransactionListenerTest.java`（新建）

**Interfaces:**
- Consumes: `SeckillOutboxMapper`（已存在）、`SeckillMetrics`（已存在）、`ObjectMapper`（Spring bean）
- Produces: `SeckillMetrics#incrementTxCheckCommit()` / `SeckillMetrics#incrementTxCheckRollback()`

- [ ] **Step 1: 写失败的测试**

新建 `order-service/src/test/java/com/hmdp/order/mq/SeckillOrderTransactionListenerTest.java`：

```java
package com.hmdp.order.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.order.entity.SeckillOutbox;
import com.hmdp.order.mapper.SeckillOutboxMapper;
import com.hmdp.order.metrics.SeckillMetrics;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeckillOrderTransactionListenerTest {

    @Mock private SeckillOutboxMapper seckillOutboxMapper;
    @Mock private SeckillMetrics seckillMetrics;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private SeckillOrderTransactionListener listener;

    /**
     * 必须喂**真字节** payload：rocketmq-spring 的 RocketMQUtil.convertToSpringMessage
     * 用 msg.getBody() 作为 payload，即原始 byte[]。塞一个 mock 对象进去会绕过反序列化链路，
     * 让"回查取不到 orderId"这类真故障在单测里隐形。
     */
    private Message<byte[]> messageOf(Long orderId) throws IOException {
        byte[] body = objectMapper.writeValueAsBytes(new SeckillOrderMessage(orderId, 7L, 1L));
        return MessageBuilder.withPayload(body).build();
    }

    @Test
    void 本地事务成功_落一条待投递事件行并提交() throws Exception {
        RocketMQLocalTransactionState state = listener.executeLocalTransaction(messageOf(9001L), null);

        assertEquals(RocketMQLocalTransactionState.COMMIT, state);

        ArgumentCaptor<SeckillOutbox> row = ArgumentCaptor.forClass(SeckillOutbox.class);
        verify(seckillOutboxMapper).insert(row.capture());
        assertEquals(9001L, row.getValue().getId(), "orderId 必须来自消息体，不能来自 arg");
        assertEquals(7L, row.getValue().getUserId());
        assertEquals(1L, row.getValue().getVoucherId());
        assertEquals(SeckillOutbox.STATUS_PENDING, row.getValue().getStatus());
        assertEquals(0, row.getValue().getRetryCount());
    }

    @Test
    void 本地事务落库失败_返回回滚而非提交() throws Exception {
        when(seckillOutboxMapper.insert(any())).thenThrow(new RuntimeException("DB 不可用"));

        assertEquals(RocketMQLocalTransactionState.ROLLBACK,
                listener.executeLocalTransaction(messageOf(9001L), null));
    }

    @Test
    void 回查_事件行存在_判为已提交并计入commit指标() throws Exception {
        when(seckillOutboxMapper.selectById(9001L)).thenReturn(
                new SeckillOutbox().setId(9001L).setStatus(SeckillOutbox.STATUS_PENDING));

        assertEquals(RocketMQLocalTransactionState.COMMIT, listener.checkLocalTransaction(messageOf(9001L)));
        verify(seckillMetrics).incrementTxCheckCommit();
        verify(seckillMetrics, never()).incrementTxCheckRollback();
    }

    @Test
    void 回查_事件行不存在_判为回滚并计入rollback指标() throws Exception {
        when(seckillOutboxMapper.selectById(9001L)).thenReturn(null);

        assertEquals(RocketMQLocalTransactionState.ROLLBACK, listener.checkLocalTransaction(messageOf(9001L)));
        verify(seckillMetrics).incrementTxCheckRollback();
        verify(seckillMetrics, never()).incrementTxCheckCommit();
    }

    @Test
    void 回查_消息体不可解析_返回UNKNOWN交由broker下一轮再问() {
        Message<byte[]> broken = MessageBuilder.withPayload("not-json".getBytes()).build();

        // 解析不出来时**绝不能**猜 ROLLBACK：那会把一个可能已提交的本地事务永久丢弃（少卖），
        // 而 UNKNOWN 只是让 broker 下一轮再问一次。
        assertEquals(RocketMQLocalTransactionState.UNKNOWN, listener.checkLocalTransaction(broken));
        verify(seckillMetrics, never()).incrementTxCheckCommit();
        verify(seckillMetrics, never()).incrementTxCheckRollback();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl order-service -am test -Dtest=SeckillOrderTransactionListenerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败，`找不到符号: 类 SeckillOrderTransactionListener`；`找不到符号: 方法 incrementTxCheckCommit()`

- [ ] **Step 3: 给 SeckillMetrics 加回查计数器**

在 `SeckillMetrics.java` 的字段区（`outboxExhaustedCounter` 之后）加：

```java
    private Counter txCheckCommitCounter;
    private Counter txCheckRollbackCounter;
```

在 `init()` 里 `outboxExhaustedCounter` 注册块之后加：

```java
        // SPEC-16：broker 回查的判定结果。这是"事务消息真的在工作"唯一能从监控上看见的信号
        // —— 稳态应为 0（回查只在进程于 executeLocalTransaction 返回前挂掉时才发生）；
        // 非 0 说明确实发生过崩溃，且 broker 的回查机制把它兜住了。
        txCheckCommitCounter = Counter.builder("seckill.tx.check")
                .description("broker 事务回查判定为已提交次数（SPEC-16）")
                .tag("result", "commit")
                .register(meterRegistry);

        txCheckRollbackCounter = Counter.builder("seckill.tx.check")
                .description("broker 事务回查判定为回滚次数（SPEC-16）")
                .tag("result", "rollback")
                .register(meterRegistry);
```

在类末尾 `incrementOutboxExhausted()` 之后加：

```java
    /** broker 回查判定本地事务已提交（SPEC-16） */
    public void incrementTxCheckCommit() {
        txCheckCommitCounter.increment();
    }

    /** broker 回查判定本地事务已回滚（SPEC-16） */
    public void incrementTxCheckRollback() {
        txCheckRollbackCounter.increment();
    }
```

- [ ] **Step 4: 实现监听器**

新建 `order-service/src/main/java/com/hmdp/order/mq/SeckillOrderTransactionListener.java`：

```java
package com.hmdp.order.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.order.entity.SeckillOutbox;
import com.hmdp.order.mapper.SeckillOutboxMapper;
import com.hmdp.order.metrics.SeckillMetrics;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 秒杀订单事务消息的本地事务与回查处理器（SPEC-16）
 *
 * <p><b>它解决什么</b>：入口侧原本是"先 INSERT 事件行、再 syncSend"，两者之间有一个崩溃窗口；
 * 现在由 broker 的 half message 机制把二者绑定 —— 本地 INSERT 提交 ⟺ half message 被提交投递。
 *
 * <p><b>为什么用默认的 rocketMQTemplate</b>：{@code RocketMQUtil.createDefaultMQProducer} 造出的
 * 本来就是 {@code TransactionMQProducer}，默认 template 直接支持事务消息，不需要第二个 producer。
 * 反过来，同一个 producer group 挂第二个 producer 会让启动直接失败
 * （{@code MQClientException: ... has been created before}）。
 *
 * <p><b>线程池必须显式配置</b>：注解默认 {@code corePoolSize=1 / maximumPoolSize=1}，
 * 而 {@link #executeLocalTransaction} 里有一条 INSERT —— 用默认值等于给秒杀入口的 DB 写入
 * 加了一道单线程串行闸门。此处与 Hikari 的 {@code maximum-pool-size: 20} 对齐。
 */
@Component
@Slf4j
@RocketMQTransactionListener(corePoolSize = 20, maximumPoolSize = 20, blockingQueueSize = 2000)
public class SeckillOrderTransactionListener implements RocketMQLocalTransactionListener {

    @Resource
    private SeckillOutboxMapper seckillOutboxMapper;

    @Resource
    private SeckillMetrics seckillMetrics;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 本地事务：写一条待投递事件行。
     *
     * <p>返回 COMMIT 才会让 broker 投递 half message；抛异常时 RocketMQ 客户端会写死改成
     * ROLLBACK（{@code DefaultMQProducerImpl} 偏移 335），所以这里显式 catch 并返回 ROLLBACK，
     * 让行为和日志都掌握在自己手里。
     *
     * @param arg 恒为 null —— 数据只从消息体取，见 {@link SeckillOrderProducer#sendSeckillOrderMessageInTransaction}
     */
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        try {
            SeckillOrderMessage payload = parsePayload(msg);
            SeckillOutbox outbox = new SeckillOutbox()
                    .setId(payload.getOrderId())
                    .setUserId(payload.getUserId())
                    .setVoucherId(payload.getVoucherId())
                    .setStatus(SeckillOutbox.STATUS_PENDING)
                    .setRetryCount(0);
            seckillOutboxMapper.insert(outbox);
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            log.error("秒杀本地事务执行失败，请求 broker 回滚 half message: error={}", e.getMessage(), e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    /**
     * 回查：行在 ⟺ 本地事务已提交。
     *
     * <p>判据与失败分支严格一致 —— 投递失败时入口会先删行再回滚预扣，所以"行不存在"恰好等于
     * "本地事务已回滚"，不存在判据与业务动作打架的中间态。
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        Long orderId;
        try {
            orderId = parsePayload(msg).getOrderId();
        } catch (Exception e) {
            // 解析不出来时绝不能猜 ROLLBACK —— 那会把一个可能已提交的本地事务永久丢弃（少卖方向）。
            // UNKNOWN 让 broker 下一轮再问；到 transactionCheckMax 仍无解时 broker 才自行处置。
            log.error("回查消息体不可解析，返回 UNKNOWN 交给下一轮: error={}", e.getMessage(), e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }

        boolean committed = seckillOutboxMapper.selectById(orderId) != null;
        if (committed) {
            seckillMetrics.incrementTxCheckCommit();
        } else {
            seckillMetrics.incrementTxCheckRollback();
        }
        log.warn("收到 broker 事务回查: orderId={}, 判定={}", orderId, committed ? "COMMIT" : "ROLLBACK");
        return committed ? RocketMQLocalTransactionState.COMMIT : RocketMQLocalTransactionState.ROLLBACK;
    }

    /**
     * 从消息体取业务参数。
     *
     * <p>rocketmq-spring 的 {@code RocketMQUtil.convertToSpringMessage} 对
     * {@code Message} 与 {@code MessageExt} 两个重载都把 {@code getBody()} 原始字节
     * 作为 payload，所以这里拿到的是 byte[] 而不是已反序列化的对象。
     */
    private SeckillOrderMessage parsePayload(Message<?> msg) throws Exception {
        return objectMapper.readValue((byte[]) msg.getPayload(), SeckillOrderMessage.class);
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -pl order-service -am test -Dtest=SeckillOrderTransactionListenerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS，5 tests

- [ ] **Step 6: 提交**

```bash
git add order-service/src/main/java/com/hmdp/order/mq/SeckillOrderTransactionListener.java \
        order-service/src/main/java/com/hmdp/order/metrics/SeckillMetrics.java \
        order-service/src/test/java/com/hmdp/order/mq/SeckillOrderTransactionListenerTest.java
git commit -m "feat(seckill): 新增事务监听器与回查指标（SPEC-16 T2）"
```

---

### Task 3: 入口改造为事务消息

**Files:**
- Modify: `order-service/src/main/java/com/hmdp/order/service/impl/VoucherOrderServiceImpl.java:148-211`
- Test: `order-service/src/test/java/com/hmdp/order/service/impl/SeckillVoucherServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 `sendSeckillOrderMessageInTransaction`；`SendStatus` / `LocalTransactionState`（rocketmq-client）
- Produces: 无（本任务为链路末端）

- [ ] **Step 1: 改写测试（先改后跑，确认失败）**

在 `SeckillVoucherServiceTest.java` 顶部 import 区加：

```java
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.TransactionSendResult;
```

在 `scriptReturns` 辅助方法下方加三个构造辅助：

```java
    private static TransactionSendResult txResult(SendStatus sendStatus, LocalTransactionState state) {
        TransactionSendResult r = new TransactionSendResult();
        r.setSendStatus(sendStatus);
        r.setLocalTransactionState(state);
        return r;
    }

    private static TransactionSendResult committed() {
        return txResult(SendStatus.SEND_OK, LocalTransactionState.COMMIT_MESSAGE);
    }

    private static TransactionSendResult rolledBack() {
        return txResult(SendStatus.SEND_OK, LocalTransactionState.ROLLBACK_MESSAGE);
    }
```

**删除**这两个用例（其断言已迁往 `SeckillOrderTransactionListenerTest`）：
`成功路径先落待投递事件行再投递_且投递成功后标记已投递`、`事件行落库失败时回滚预扣且返回失败_不投递`。

**替换** `脚本返回0_消息发送成功_返回订单号且计成功`：

```java
    @Test
    void 事务提交_返回订单号_标记已投递且不回滚预扣() {
        scriptReturns(0L);
        when(seckillOrderProducer.sendSeckillOrderMessageInTransaction(any())).thenReturn(committed());
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        Result r = service.seckillVoucher(1L);

        assertTrue(r.getSuccess());
        assertEquals(9001L, r.getData());
        // evaluate：
        // 1. 事件行不再由入口 INSERT —— 它已移入 executeLocalTransaction，由 broker 回调触发。
        //    入口若还 INSERT，half message 与本地写入之间就没有任何绑定，是伪事务消息。
        verify(seckillOutboxMapper, never()).insert(any());
        // 2. 提交后要置为已投递，否则补投器 30s 后会把正常单再投一次（消费端幂等，无害但脏）
        verify(seckillOutboxMapper).update(any(), any());
        verify(valueOperations, never()).increment(anyString());
        verify(seckillMetrics).incrementSeckillSuccess();
        verify(seckillMetrics).incrementMqSendSuccess();
    }
```

**替换** `脚本返回0_消息发送失败_返回失败且回滚预扣_不计成功`：

```java
    @Test
    void 事务回滚_返回失败且已回滚预扣_不计成功() {
        scriptReturns(0L);
        when(seckillOrderProducer.sendSeckillOrderMessageInTransaction(any())).thenReturn(rolledBack());
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);

        Result r = service.seckillVoucher(1L);

        assertFalse(r.getSuccess());
        // 回滚三件事：库存 +1、移除用户标记、删除明细。
        // 只断言"取过 handles"不足以证明回滚发生——取了不用照样能通过，
        // 而静默失败会让用户被永久标记"已购买"且库存凭空少 1，故必须锁定具体键与参数。
        verify(valueOperations).increment("seckill:stock:1");
        verify(setOperations).remove("seckill:order:1", "7");
        verify(hashOperations).delete("seckill:order:detail:1", "9001");
        verify(seckillMetrics).incrementMqSendFail();
        verify(seckillMetrics, never()).incrementSeckillSuccess();
    }
```

**替换** `投递失败时先删事件行再回滚预扣`：

```java
    @Test
    void 事务回滚时先删事件行再回滚预扣() {
        scriptReturns(0L);
        when(seckillOrderProducer.sendSeckillOrderMessageInTransaction(any())).thenReturn(rolledBack());
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);

        Result r = service.seckillVoucher(1L);

        assertFalse(r.getSuccess());
        assertEquals("订单提交繁忙，请稍后重试", r.getErrorMsg());

        // 顺序即正确性：先删行、后回滚，崩溃时停在"行已删 + 预扣仍在"的少卖侧；
        // 反过来会停在"预扣已释放 + 行仍待投递"，补投出去就是超卖。
        InOrder order = inOrder(seckillOutboxMapper, valueOperations);
        order.verify(seckillOutboxMapper).deleteById(9001L);
        order.verify(valueOperations).increment("seckill:stock:1");
    }
```

**替换** `事件行删除失败时保留预扣不回滚` 的桩，其余断言不变：

```java
        when(seckillOrderProducer.sendSeckillOrderMessageInTransaction(any())).thenReturn(rolledBack());
```

新增三个用例：

```java
    @Test
    void 事务消息发送抛异常_回滚预扣且不删事件行() {
        scriptReturns(0L);
        when(seckillOrderProducer.sendSeckillOrderMessageInTransaction(any()))
                .thenThrow(new org.springframework.messaging.MessagingException("broker 不可达"));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);

        Result r = service.seckillVoucher(1L);

        assertFalse(r.getSuccess());
        verify(valueOperations).increment("seckill:stock:1");
        // 抛异常 ⇒ half message 未落盘 ⇒ executeLocalTransaction 根本没跑 ⇒ 不可能有事件行。
        // 这里若调 deleteById 会掩盖"异常发生在发送阶段"这个诊断信息。
        verify(seckillOutboxMapper, never()).deleteById(any());
        verify(seckillMetrics).incrementMqSendFail();
    }

    @Test
    void half_message未落盘_sendStatus非OK_按失败处理并回滚() {
        scriptReturns(0L);
        when(seckillOrderProducer.sendSeckillOrderMessageInTransaction(any()))
                .thenReturn(txResult(SendStatus.FLUSH_DISK_TIMEOUT, LocalTransactionState.COMMIT_MESSAGE));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);

        Result r = service.seckillVoucher(1L);

        assertFalse(r.getSuccess(),
                "sendStatus 非 SEND_OK 时 half message 未确认落盘，即便本地状态是 COMMIT 也不能算成功");
        verify(valueOperations).increment("seckill:stock:1");
        verify(seckillMetrics, never()).incrementSeckillSuccess();
    }

    @Test
    void 事务发送返回null_按失败处理不抛NPE() {
        scriptReturns(0L);
        when(seckillOrderProducer.sendSeckillOrderMessageInTransaction(any())).thenReturn(null);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);

        Result r = service.seckillVoucher(1L);

        assertFalse(r.getSuccess());
        verify(seckillMetrics, never()).incrementSeckillSuccess();
    }
```

**最后**，把两个时间窗用例里的

```java
        verify(seckillOrderProducer, never()).sendSeckillOrderMessage(any());
```

改为

```java
        verify(seckillOrderProducer, never()).sendSeckillOrderMessageInTransaction(any());
```

（共 2 处：`活动开始前被拒_文案可区分且计入专门指标`、`活动结束后被拒_文案可区分且计入专门指标`。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl order-service -am test -Dtest=SeckillVoucherServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— `必填 未找到方法 sendSeckillOrderMessageInTransaction`（调用方实现尚未改）

- [ ] **Step 3: 改入口实现**

把 `VoucherOrderServiceImpl.java` 中**从 `// 本地事件表（SPEC-15 P2-1 方案 a / 形态 D1-b）：先落库、再投递。` 这一行起，
到 `return Result.ok(orderId);` 之前**的整段（约 148-208 行）替换为：

```java
            // 事务消息（SPEC-16）：half message 先落 broker，broker 回调 executeLocalTransaction
            // 写本地事件行，行落库 ⟺ broker 提交投递，二者由 broker 的 commit/rollback 绑定。
            // outbox 表因此从"投递任务表"升级为"本地事务凭据表"——broker 回查问的就是它。
            //
            // 【为什么不再在这里 INSERT】INSERT 移入 executeLocalTransaction 才有原子性。
            // 若仍留在这里，"先独立提交 INSERT、再发事务消息"的 half message 与本地写入之间
            // 不存在任何绑定，是伪事务消息（SPEC-16 §3）。
            SeckillOrderMessage message = new SeckillOrderMessage(orderId, userId, voucherId);

            TransactionSendResult txResult;
            try {
                txResult = seckillOrderProducer.sendSeckillOrderMessageInTransaction(message);
            } catch (Exception e) {
                // 发送阶段抛异常 ⇒ half message 未落盘 ⇒ executeLocalTransaction 根本没跑 ⇒ 无事件行。
                // 因此这里直接回滚预扣，**不**做删行（对比下面那个分支）。
                rollbackSeckillReservation(voucherId, userId, orderId);
                seckillMetrics.incrementMqSendFail();
                seckillMetrics.incrementSeckillFail();
                log.error("秒杀事务消息发送异常，已回滚Redis预扣: orderId={}, userId={}, voucherId={}",
                        orderId, userId, voucherId, e);
                return Result.fail(SeckillFailMessages.MQ_SEND_FAILED);
            }

            // 只有 broker 确认 half message 落盘（SEND_OK）**且**本地事务提交（COMMIT）才算成功。
            // 其余一律走失败路径；不单列 UNKNOW 档 —— executeLocalTransaction 抛异常时 RocketMQ
            // 客户端写死转 ROLLBACK，框架不会替我们产生 UNKNOW。
            boolean committed = txResult != null
                    && SendStatus.SEND_OK == txResult.getSendStatus()
                    && LocalTransactionState.COMMIT_MESSAGE == txResult.getLocalTransactionState();

            if (!committed) {
                // 【顺序即正确性】必须先删事件行、再回滚预扣。理由见本段上方注释与
                // SeckillVoucherServiceTest#事务回滚时先删事件行再回滚预扣 的论证：
                // 反过来会在"回滚完成但行未删"的崩溃点上留下一条待投递记录，
                // 补投出去会在已释放的预扣上重新建单 —— 超卖方向。
                if (deleteOutboxRow(orderId)) {
                    rollbackSeckillReservation(voucherId, userId, orderId);
                } else {
                    // 行没删掉 → 该单仍会被补投器投递 → 预扣**必须保留**。
                    // 若此处照常回滚（INCR 库存 + 移出用户 + 删明细），补投出去的消息会在
                    // 一份已释放的预扣上重新建单：Redis 库存比 DB 多 1，即超卖方向。
                    // 取舍：宁可让用户先看到一次失败、稍后真的拿到订单（延迟/少卖），也不能超卖。
                    log.error("[需人工核对] 事件行删除失败，已保留预扣不回滚: orderId={}, userId={}, voucherId={}",
                            orderId, userId, voucherId);
                }
                seckillMetrics.incrementMqSendFail();
                seckillMetrics.incrementSeckillFail();
                log.error("秒杀本地事务未提交，已按失败处理: orderId={}, localTxState={}, sendStatus={}",
                        orderId,
                        txResult == null ? null : txResult.getLocalTransactionState(),
                        txResult == null ? null : txResult.getSendStatus());
                return Result.fail(SeckillFailMessages.MQ_SEND_FAILED);
            }

            // broker 已确认提交，投递由 broker 负责；标记后补投器不再重复捞这一行。
            // 标记失败只记 warn、不影响返回：补投器下一轮会再投一次，消费端以 orderId 为主键幂等。
            markOutboxDelivered(orderId);
```

新增 import：

```java
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.TransactionSendResult;
```

同时删除 `markOutboxDelivered` 上方已有的那段"标记已投递。失败只记 warn……"注释与
`markOutboxDelivered(orderId);` 调用（已被上面新代码的最后两行取代）——**保留 `markOutboxDelivered`
方法本身**，它仍被调用。

> 注意：`VoucherOrderServiceImpl` 里已有一个未使用的 `import com.hmdp.order.entity.SeckillOutbox;`。
> `markOutboxDelivered` 的 lambda 里用了 `SeckillOutbox::getId`，**该 import 仍然需要，不要删**。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl order-service -am test -Dtest=SeckillVoucherServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 5: 跑 order-service 全量回归**

Run: `mvn -pl order-service -am test`
Expected: BUILD SUCCESS，0 failures。`SeckillOutboxDelivererTest` 等既有用例全绿（补投器未改动）

- [ ] **Step 6: 提交**

```bash
git add order-service/src/main/java/com/hmdp/order/service/impl/VoucherOrderServiceImpl.java \
        order-service/src/test/java/com/hmdp/order/service/impl/SeckillVoucherServiceTest.java
git commit -m "refactor(seckill): 秒杀入口投递改为事务消息，失败分支按事务状态分档（SPEC-16 T3）"
```

---

### Task 4: E2E 回查实测

**Files:**
- Modify: `.e2e/rocketmq/broker.conf`

**Interfaces:**
- Consumes: Task 1–3 的全部实现；运行中的中间件（Nacos / MySQL / Redis / RocketMQ）
- Produces: E10 的实测证据（broker 回查日志 + 应用日志 + `seckill.tx.check` 指标）

- [ ] **Step 1: 调小回查参数**

在 `.e2e/rocketmq/broker.conf` 末尾追加：

```
# SPEC-16 E2E：让 broker 回查在秒级可观察（默认 transactionTimeOut=6000 / transactionCheckInterval=60000）。
# 这两个值是**验证用**的，生产环境应保持默认 —— 调小只是把"进程卡住到回查"的等待时间缩短。
transactionTimeOut = 2000
transactionCheckInterval = 3000
```

- [ ] **Step 2: 重启 broker 使配置生效**

```bash
docker compose up -d --force-recreate rocketmq-broker
docker logs --tail 20 hmdp-rocketmq-broker
```

Expected: 日志出现 `The broker ... boot success`，无 `transactionCheckInterval` 解析报错。

- [ ] **Step 3: 起应用，确认监听器注册成功（验收 #1）**

按 `.e2e` 既有配方启动 order-service（网关 + 依赖服务 + 中间件），然后：

Run: `grep -iE "TransactionListener|has been created before|RocketMQTransactionConfiguration" <order-service 启动日志>`
Expected: 无 `does not exist TransactionListener`、无 `already exists RocketMQLocalTransactionListener`、无 `has been created before`。

- [ ] **Step 4: 制造被阻塞的本地事务（E10 前置）**

另开一个 MySQL 会话并**保持事务不提交**：

```bash
docker exec -it hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD" hmdp
```

```sql
BEGIN;
SELECT * FROM tb_seckill_outbox FOR UPDATE;
-- 不要 COMMIT，保持这个会话开着
```

`FOR UPDATE` 全表扫描在可重复读隔离级别下会加 next-key 锁，新 INSERT 落到被锁间隙时会被阻塞，
从而让 `executeLocalTransaction` 卡在 INSERT 上、迟迟不问 broker 要 COMMIT。

- [ ] **Step 5: 发起一次秒杀下单**

通过网关调用秒杀接口（需要登录态；沿用 SPEC-15 验证手册里的取 token 方式）。

Expected: 请求**挂住**不返回（本地事务被行锁阻塞）。这正是我们要的中间态。

- [ ] **Step 6: 观察回查（E10 核心证据）**

```bash
docker exec -it hmdp-rocketmq-broker sh -c 'tail -50 /home/rocketmq/logs/rocketmqlogs/transaction.log'
```

Expected: 出现对该事务的 `check` 记录（约在 t+3s 起，每 `transactionCheckInterval` 一次）。

同时应用侧日志应出现：

```
收到 broker 事务回查: orderId=..., 判定=ROLLBACK
```

（行尚未提交，`selectById` 查不到 → ROLLBACK。）

- [ ] **Step 7: 释放锁，验证补投器兜底**

上一步的 MySQL 会话执行：

```sql
COMMIT;
```

此时：客户端补发 COMMIT，但 broker 已把该 half message 回滚丢弃 → **这条订单的消息确实丢了**。
入口请求会返回失败并回滚预扣；若行未被删掉，或释放与提交交错，则由补投器接手。

预期最终收敛：`SeckillOutboxDeliverer` 在 30s 判龄 + 3s 扫描后把 `status=0` 的行补投，
消费端建单。用以下命令取证：

```bash
docker exec -it hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD" hmdp \
  -e "SELECT id,status,retry_count,update_time FROM tb_seckill_outbox WHERE id=<orderId>;"
docker exec -it hmdp-mysql mysql -uroot -p"$MYSQL_PASSWORD" hmdp \
  -e "SELECT id,user_id,voucher_id FROM tb_voucher_order WHERE id=<orderId>;"
```

Expected: 事件行 `status=1`（补投器已置位），且 `tb_voucher_order` 出现同 id 的订单 ——
证明「回查判定 + 补投器兜底」两层都真的在工作。

指标侧：`curl -s localhost:8084/actuator/prometheus | grep seckill_tx_check`
Expected: `seckill_tx_check{result="rollback"}` ≥ 1。

- [ ] **Step 8: 跑一次正常链路，确认无回归（验收 #8）**

不加行锁直接秒杀下单，Expected: 立刻返回 `orderId`，`seckill.outbox` 中出现 `status=1` 的行，
消费端建单。同时确认 `seckill_tx_check` **没有增长**（正常路径不触发回查）。

- [ ] **Step 9: 如实记录结论并提交**

把 E10 的实际输出（命令 + 原始日志片段）追加到 SPEC-16 §5.2 的 E10 条目下方。
**若某一步未按预期发生，照实写**，不要只记录成功路径 —— SPEC 的既有约定是「报告要真实证据」。

```bash
git add .e2e/rocketmq/broker.conf docs/specs/SPEC-16-秒杀事务消息.md
git commit -m "test(seckill): SPEC-16 E2E 回查实测（E10）+ broker 回查参数调整"
```

---

## 自检记录

- **Spec 覆盖**：SPEC-16 §2.2 数据流 → Task 1/2/3；§2.3 回查判据 → Task 2 Step 4；§2.4 失败分支四行 → Task 3 Step 1 的四个用例 + Step 3 的两段实现；§2.5 线程池 → Task 2 Step 4 注解；§2.5 broker.conf → Task 4 Step 1；§5.2 单测 1–6 → Task 1/2/3；E10 → Task 4。§4 批次 T 的 T1–T5 与 Task 1–4 + 回归一一对应。
- **与 SPEC-16 的两处措辞不一致（已确认按计划执行，spec 措辞待后续同步）**：
  1. SPEC-16 §2.4 写「我们自身也不返回 UNKNOW」，但 Task 2 的 `checkLocalTransaction` 在**消息体不可解析**时返回 UNKNOWN。二者不冲突：前者说的是 `executeLocalTransaction`；后者的理由是「猜 ROLLBACK 会永久丢弃可能已提交的本地事务」。计划按后者实现。
  2. SPEC-16 §5.2 的「已知坑」写监听器测试需要注册 `TableInfo` —— 实际不需要：监听器只用 `insert` / `selectById`，不经过 lambda 列名解析。需要该 helper 的是 `SeckillVoucherServiceTest`，它本来就有。
- **类型一致性**：`sendSeckillOrderMessageInTransaction` 返回 `TransactionSendResult`（Task 1 定义 / Task 3 消费）；`incrementTxCheckCommit` / `incrementTxCheckRollback`（Task 2 同时定义与消费）；`SeckillOutbox.STATUS_PENDING`（既有常量，Task 2 消费）。
