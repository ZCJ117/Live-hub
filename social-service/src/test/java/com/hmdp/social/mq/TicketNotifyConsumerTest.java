package com.hmdp.social.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.social.service.NotificationService;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SPEC-08 §8.3（A3 消费者幂等）、§8.4（A4 重试上限告警）、A7（文案不含 null 字面量）
 *
 * <p>纯 Mockito 单测，不启 Spring、不连 RocketMQ。
 *
 * <p><b>BUG-05 回归锁</b>：{@link TicketNotifyConsumer} 必须实现 {@code RocketMQListener<MessageExt>}，
 * 否则 msgId / reconsumeTimes 拿不到，§5.4 幂等与 §5.5 重试上限在 social 侧无处挂载。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TicketNotifyConsumerTest {

    @Mock private NotificationService notificationService;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private TicketNotifyConsumer consumer;

    @BeforeEach
    void setUp() {
        // 默认放行去重：单条投递场景下 setIfAbsent 视为首次（个别用例另行覆写为 thenReturn(true, false)）
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        consumer = new TicketNotifyConsumer(notificationService, stringRedisTemplate);
    }

    private static Map<String, Object> ticketMessage() {
        Map<String, Object> m = new HashMap<>();
        m.put("ticketId", 9001L);
        m.put("ticketNo", "T-2024-0001");
        m.put("userId", 7L);
        m.put("priority", "P1");
        m.put("expectedSla", "2h");
        return m;
    }

    private static MessageExt message(String msgId, Map<String, Object> body) throws Exception {
        MessageExt raw = new MessageExt();
        raw.setMsgId(msgId);
        raw.setBody(new ObjectMapper().writeValueAsBytes(body));
        return raw;
    }

    // ---------- A7：文案不含 null 字面量 ----------

    @Test
    void expectedSla缺失时站内信不出现null字面量() throws Exception {
        Map<String, Object> msg = ticketMessage();
        msg.remove("expectedSla");

        consumer.onMessage(message("m-sla", msg));

        String body = capturedBody();
        assertFalse(body.contains("null"),
                "expectedSla 缺失时 String.valueOf(null) 写入字面量 \"null\"，实际文案=" + body);
        assertTrue(body.contains("未指定"), "缺失字段应兜底为「未指定」，实际文案=" + body);
    }

    @Test
    void priority缺失时站内信不出现null字面量() throws Exception {
        Map<String, Object> msg = ticketMessage();
        msg.remove("priority");

        consumer.onMessage(message("m-prio", msg));

        String body = capturedBody();
        assertFalse(body.contains("null"),
                "priority 缺失时 String.valueOf(null) 写入字面量 \"null\"，实际文案=" + body);
        assertTrue(body.contains("未指定"), "缺失字段应兜底为「未指定」，实际文案=" + body);
    }

    // ---------- 缺关键字段应丢弃，不落库 ----------

    @Test
    void 缺ticketNo时丢弃且不落库() throws Exception {
        Map<String, Object> msg = ticketMessage();
        msg.remove("ticketNo");

        consumer.onMessage(message("m-no-ticket", msg));

        verify(notificationService, never())
                .saveNotification(anyLong(), anyLong(), anyString(), anyString());
    }

    // ---------- BUG-05 回归锁：onMessage 必须能拿到 MessageExt ----------

    @Test
    void onMessage形参必须是MessageExt() {
        boolean hasMessageExtParam = false;
        for (Method m : TicketNotifyConsumer.class.getMethods()) {
            if (!"onMessage".equals(m.getName())) {
                continue;
            }
            for (Class<?> p : m.getParameterTypes()) {
                if (MessageExt.class.isAssignableFrom(p)) {
                    hasMessageExtParam = true;
                }
            }
        }
        assertTrue(hasMessageExtParam,
                "SPEC-08 §5.4/§5.5：msgId 与 reconsumeTimes 只在 MessageExt 上，"
                        + "onMessage 形参必须含 MessageExt，否则幂等与重试上限告警无法实现");
    }

    // ---------- A3：幂等（同 msgId 去重 / 不同 msgId 各自落库） ----------

    @Test
    void 同一msgId重复投递只落1条站内信() throws Exception {
        // 第一次 setIfAbsent=true（首次），第二次=false（重复投递）
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true, false);
        MessageExt raw = message("m-dup", ticketMessage());

        consumer.onMessage(raw);
        consumer.onMessage(raw);

        verify(notificationService, times(1))
                .saveNotification(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void 不同msgId的两条消息各自落一条站内信() throws Exception {
        consumer.onMessage(message("m-a", ticketMessage()));
        consumer.onMessage(message("m-b", ticketMessage()));

        verify(notificationService, times(2))
                .saveNotification(anyLong(), anyLong(), anyString(), anyString());
    }

    // ---------- 正向：兜底不得吃掉真实值 ----------

    @Test
    void 文案正常时包含真实的ticketNo与priority与expectedSla() throws Exception {
        consumer.onMessage(message("m-ok", ticketMessage()));

        String body = capturedBody();
        assertTrue(body.contains("T-2024-0001"), "文案应含真实 ticketNo，实际=" + body);
        assertTrue(body.contains("P1"), "文案应含真实 priority，实际=" + body);
        assertTrue(body.contains("2h"), "文案应含真实 expectedSla，实际=" + body);
        assertFalse(body.contains("未指定"), "字段齐全时不应出现兜底值，实际=" + body);
    }

    private String capturedBody() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).saveNotification(anyLong(), anyLong(), anyString(), body.capture());
        return body.getValue();
    }
}
