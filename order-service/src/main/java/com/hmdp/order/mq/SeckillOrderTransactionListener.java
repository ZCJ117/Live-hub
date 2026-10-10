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
