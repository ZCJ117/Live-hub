package com.hmdp.agent.memory;

import com.hmdp.agent.config.AgentProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 短期记忆集成测试（FR-02 / P2-R4 配套：TTL 管理、裁剪降级、会话关闭清理）
 * 直连本地 Redis；不可达时跳过（基线单测不依赖中间件）
 */
class ChatMemoryServiceTest {

    private static final Long SID = System.nanoTime();
    private static final AgentProperties PROPS = new AgentProperties();

    private static RedissonClient redisson;
    private static ChatMemoryService memoryService;

    @BeforeAll
    static void setUp() {
        try {
            Config config = new Config();
            var server = config.useSingleServer()
                    .setAddress("redis://127.0.0.1:6379")
                    .setConnectTimeout(500)
                    .setTimeout(1000)
                    .setRetryAttempts(1);
            String pwd = System.getenv("REDIS_PASSWORD");
            org.junit.jupiter.api.Assumptions.assumeTrue(pwd != null && !pwd.isBlank(),
                    "未设置 REDIS_PASSWORD，跳过：请先 `set -a; source .env; set +a`");
            if (!pwd.isBlank()) {
                server.setPassword(pwd);
            }
            redisson = Redisson.create(config);
            redisson.getBucket("agent:test:ping").set("1"); // 探活：失败则跳过全部用例
            memoryService = new ChatMemoryService(redisson, PROPS);
        } catch (Exception e) {
            redisson = null;
        }
        assumeTrue(redisson != null, "Redis 不可达，跳过记忆集成测试");
    }

    @AfterEach
    void cleanKeys() {
        if (redisson != null) {
            redisson.getKeys().deleteByPattern("agent:session:" + SID + ":*");
        }
    }

    @AfterAll
    static void tearDown() {
        if (redisson != null) {
            redisson.shutdown();
        }
    }

    @Test
    void append_and_load_roundtrip_in_order() {
        memoryService.append(SID, "user", "我上周抢的券怎么还没到");
        memoryService.append(SID, "assistant", "正在为您查询订单…");
        memoryService.append(SID, "user", "就是订单 1001");

        List<ChatMemoryService.LlmTypesMsg> history = memoryService.loadHistory(SID);
        assertEquals(3, history.size());
        assertEquals("user", history.get(0).role());
        assertEquals("我上周抢的券怎么还没到", history.get(0).content());
        assertEquals("assistant", history.get(1).role());
        assertEquals("user", history.get(2).role());
    }

    @Test
    void append_sliding_renews_ttl() {
        memoryService.append(SID, "user", "hello");
        long ttl = redisson.getList("agent:session:" + SID + ":history").remainTimeToLive();
        assertTrue(ttl > 0 && ttl <= PROPS.getSession().getMemoryTtlMinutes() * 60_000L,
                "TTL 应为 0 < ttl ≤ " + PROPS.getSession().getMemoryTtlMinutes() + " 分钟，实际: " + ttl);
    }

    @Test
    void trimKeepLast_keeps_last_n_entries() {
        for (int i = 1; i <= 8; i++) {
            memoryService.append(SID, "user", "msg-" + i);
        }
        memoryService.trimKeepLast(SID, 4);

        List<ChatMemoryService.LlmTypesMsg> history = memoryService.loadHistory(SID);
        assertEquals(4, history.size());
        assertEquals("msg-5", history.get(0).content());
        assertEquals("msg-8", history.get(3).content());
    }

    @Test
    void evict_clears_history_and_focus() {
        memoryService.append(SID, "user", "hello");
        memoryService.setFocusOrder(SID, 1001L);
        assertEquals(1001L, memoryService.getFocusOrder(SID));

        memoryService.evict(SID);
        assertEquals(0, memoryService.size(SID));
        assertNull(memoryService.getFocusOrder(SID));
    }

    @Test
    void focusOrder_roundtrip_and_absent_is_null() {
        assertNull(memoryService.getFocusOrder(SID));
        memoryService.setFocusOrder(SID, 42L);
        assertEquals(42L, memoryService.getFocusOrder(SID));
    }
}
