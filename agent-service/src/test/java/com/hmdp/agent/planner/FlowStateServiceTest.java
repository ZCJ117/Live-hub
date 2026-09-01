package com.hmdp.agent.planner;

import com.hmdp.agent.service.AgentSessionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * 流程状态服务测试（T3.3/T3.5）：澄清计数、冲突现场存取
 * 直连本地 Redis；不可达时跳过（基线单测不依赖中间件）
 */
class FlowStateServiceTest {

    private static final Long SID = System.nanoTime();
    private static RedissonClient redisson;
    private static FlowStateService service;

    @BeforeAll
    static void setUp() {
        try {
            Config config = new Config();
            var server = config.useSingleServer()
                    .setAddress("redis://127.0.0.1:6379")
                    .setConnectTimeout(500)
                    .setTimeout(1000)
                    .setRetryAttempts(1);
            String pwd = System.getenv().getOrDefault("REDIS_PASSWORD", "520117");
            if (!pwd.isBlank()) {
                server.setPassword(pwd);
            }
            redisson = Redisson.create(config);
            redisson.getBucket("agent:test:ping").set("1");
            service = new FlowStateService(redisson, mock(AgentSessionService.class));
        } catch (Exception e) {
            redisson = null;
        }
        assumeTrue(redisson != null, "Redis 不可达，跳过流程状态测试");
    }

    @Test
    void clarify_counter_increments_and_resets() {
        assertEquals(1, service.incrClarify(SID));
        assertEquals(2, service.incrClarify(SID));
        assertEquals(2, service.getClarify(SID));
        service.resetClarify(SID);
        assertEquals(0, service.getClarify(SID));
    }

    @Test
    void pending_flow_save_and_pop() {
        assertNull(service.popPendingFlow(SID));
        service.savePendingFlow(SID, "退款申请");
        assertEquals("退款申请", service.popPendingFlow(SID));
        assertNull(service.popPendingFlow(SID)); // pop 后清空
    }
}
