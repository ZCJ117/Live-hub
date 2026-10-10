package com.hmdp.agent.snapshot;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.entity.AgentSessionSnapshot;
import com.hmdp.agent.mapper.AgentSessionMapper;
import com.hmdp.agent.mapper.AgentSessionSnapshotMapper;
import com.hmdp.agent.memory.ChatMemoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FR-13 T5.3：回放快照——固化内容与实时会话一致率 100%（写入→读回 diff）、失败不阻断关闭 */
@ExtendWith(MockitoExtension.class)
class SessionSnapshotServiceTest {

    static {
        // 纯 Mockito 单测无 MyBatis 上下文，手动注册实体 lambda 缓存
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new MybatisConfiguration(), ""), AgentSession.class);
    }

    @Mock private AgentSessionSnapshotMapper snapshotMapper;
    @Mock private ChatMemoryService memoryService;
    @Mock private RedissonClient redisson;
    @Mock private AgentSessionMapper sessionMapper;
    @Mock private RList<String> cardList;

    private SessionSnapshotService service() {
        return new SessionSnapshotService(snapshotMapper, memoryService, redisson, sessionMapper);
    }

    private AgentSession session() {
        return new AgentSession().setId(7L).setUserId(100L).setSummary("订单已受理摘要").setMsgCount(4);
    }

    @Test
    void 快照固化_读回一致率百分之百() {
        when(memoryService.loadHistory(7L)).thenReturn(List.of(
                new ChatMemoryService.LlmTypesMsg("user", "我的订单怎么还没到"),
                new ChatMemoryService.LlmTypesMsg("assistant", "订单已支付，预计24h内到账")));
        when(redisson.<String>getList("agent:session:7:cards")).thenReturn(cardList);
        when(cardList.readAll()).thenReturn(List.of(
                "{\"cardType\":\"REFUND_CONFIRM\",\"payload\":{\"orderId\":9001}}"));
        ArgumentCaptor<AgentSessionSnapshot> captor = ArgumentCaptor.forClass(AgentSessionSnapshot.class);
        when(snapshotMapper.insert(any())).thenReturn(1);

        assertTrue(service().saveSnapshot(session()));

        verify(snapshotMapper).insert(captor.capture());
        String json = captor.getValue().getSnapshotJson();
        assertEquals(7L, captor.getValue().getSessionId());

        // 读回（回放接口路径）并逐项 diff
        when(snapshotMapper.selectOne(any())).thenReturn(new AgentSessionSnapshot()
                .setSessionId(7L).setSnapshotJson(json));
        Map<String, Object> out = service().loadSnapshot(7L);

        List<?> messages = (List<?>) out.get("messages");
        assertEquals(2, messages.size());
        assertEquals("user", ((Map<?, ?>) messages.get(0)).get("role"));
        assertEquals("我的订单怎么还没到", ((Map<?, ?>) messages.get(0)).get("content"));
        assertEquals("订单已支付，预计24h内到账", ((Map<?, ?>) messages.get(1)).get("content"));

        List<?> cardsOut = (List<?>) out.get("cards");
        assertEquals(1, cardsOut.size());
        assertEquals("REFUND_CONFIRM", ((Map<?, ?>) cardsOut.get(0)).get("cardType"));

        assertEquals("订单已受理摘要", out.get("summary"));
        assertEquals(4, out.get("msgCount"));
    }

    @Test
    void 快照写入失败_返回false不阻断关闭() {
        when(memoryService.loadHistory(7L)).thenReturn(List.of());
        when(redisson.<String>getList(anyString())).thenReturn(cardList);
        when(cardList.readAll()).thenReturn(List.of());
        when(snapshotMapper.insert(any())).thenThrow(new RuntimeException("db down"));

        assertFalse(service().saveSnapshot(session()));
    }

    @Test
    void 无快照_回放返回null() {
        when(snapshotMapper.selectOne(any())).thenReturn(null);
        assertNull(service().loadSnapshot(9L));
    }
}
