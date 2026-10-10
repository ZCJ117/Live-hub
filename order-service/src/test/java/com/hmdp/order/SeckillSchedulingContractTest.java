package com.hmdp.order;

import com.hmdp.order.service.impl.SeckillConsistencyServiceImpl;
import com.hmdp.order.service.impl.SeckillOutboxDeliverer;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 定时对账必须真的被注册（SPEC-04 §9 A1）
 *
 * <p><b>背景</b>：{@code SeckillConsistencyServiceImpl} 上写着 {@code @Scheduled(fixedRate = 300000)}，
 * 但 {@code OrderApplication} 没有 {@code @EnableScheduling}——Spring 根本不会扫描 {@code @Scheduled}，
 * 该方法**从未被注册**，日志里「开始执行定时一致性检查...」出现次数恒为 0。
 * 据此设计的自动对账能力实际为零，却看起来"有定时任务"。
 *
 * <p>这是纯注解契约测试：不启 Spring、不连中间件。运行时的对应证据是启动 order-service 后
 * 日志中出现该行（见 §8.1）。
 */
class SeckillSchedulingContractTest {

    @Test
    void 启动类必须开启定时任务() {
        assertNotNull(AnnotatedElementUtils.findMergedAnnotation(OrderApplication.class, EnableScheduling.class),
                "OrderApplication 缺 @EnableScheduling：@Scheduled 方法不会被注册，定时对账永不执行 (SPEC-04 §1.1)");
    }

    @Test
    void 对账方法必须是每5分钟执行一次的定时任务() throws Exception {
        Method m = SeckillConsistencyServiceImpl.class.getMethod("scheduledConsistencyCheck");

        Scheduled scheduled = m.getAnnotation(Scheduled.class);
        assertNotNull(scheduled, "scheduledConsistencyCheck 必须标 @Scheduled");
        assertEquals(300000L, scheduled.fixedRate(), "SPEC-04 §9 A1 要求每 5 分钟执行一次");
    }

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
}
