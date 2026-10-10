package com.hmdp.order.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hmdp.order.entity.SeckillOutbox;
import com.hmdp.order.mapper.SeckillOutboxMapper;
import com.hmdp.order.metrics.SeckillMetrics;
import com.hmdp.order.mq.SeckillOrderProducer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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
import static org.mockito.ArgumentMatchers.isNull;
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

    static {
        // 纯 Mockito 单测无 MyBatis 上下文：补投器里的 lambdaQuery/lambdaUpdate 在首次
        // 解析方法引用列名时会抛 "can not find lambda cache for this entity"，
        // 必须在解析前手动注册实体 TableInfo（同 ConfirmTaskServiceTest:36 做法）。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                SeckillOutbox.class);
    }

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
