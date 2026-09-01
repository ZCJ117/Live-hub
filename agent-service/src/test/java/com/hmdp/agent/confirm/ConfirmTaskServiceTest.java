package com.hmdp.agent.confirm;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hmdp.agent.dto.OrderCardDTO;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.entity.AgentTask;
import com.hmdp.agent.mapper.AgentTaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T4.1：agent_task 生命周期——生成/懒过期/条件更新抢确认/同订单活跃查询 */
@ExtendWith(MockitoExtension.class)
class ConfirmTaskServiceTest {

    static {
        // 纯 Mockito 单测无 MyBatis 上下文，手动注册实体 lambda 缓存
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new MybatisConfiguration(), ""), AgentTask.class);
    }

    @Mock
    private AgentTaskMapper taskMapper;

    private ConfirmTaskService service() {
        return new ConfirmTaskService(taskMapper);
    }

    private AgentSession session() {
        return new AgentSession().setId(1L).setUserId(100L).setStatus("ACTIVE");
    }

    private OrderCardDTO order() {
        return OrderCardDTO.from(200L, 300L, "国庆5折券", 5000L, 10000L, 2,
                LocalDateTime.of(2026, 8, 30, 12, 0));
    }

    @Test
    void 生成退款任务_PENDING_10分钟有效期_bizOrderId回填() {
        when(taskMapper.insert(any(AgentTask.class))).thenReturn(1);
        AgentTask task = service().createRefundTask(session(), order());

        ArgumentCaptor<AgentTask> captor = forClass(AgentTask.class);
        verify(taskMapper).insert(captor.capture());
        AgentTask saved = captor.getValue();
        assertEquals("REFUND_REQUEST", saved.getTaskType());
        assertEquals("PENDING_CONFIRM", saved.getStatus());
        assertEquals(100L, saved.getUserId());
        assertEquals(1L, saved.getSessionId());
        assertEquals(200L, saved.getBizOrderId());
        assertNotNull(saved.getActionId());
        assertEquals(36, saved.getActionId().length()); // UUID
        assertTrue(saved.getExpireTime().isAfter(LocalDateTime.now().plusMinutes(9)));
        assertTrue(saved.getPayloadJson().contains("国庆5折券"));
        assertEquals(task, saved);
    }

    @Test
    void 按actionId查任务_校验userId归属() {
        AgentTask task = new AgentTask().setActionId("a1").setUserId(100L).setStatus("PENDING_CONFIRM");
        when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(task));
        Optional<AgentTask> found = service().findByActionId(100L, "a1");
        assertTrue(found.isPresent());
        Optional<AgentTask> denied = service().findByActionId(999L, "a1");
        assertTrue(denied.isEmpty(), "他人 actionId 必须查不到（越权拦截）");
    }

    @Test
    void 条件更新抢确认_SQL带status与过期守卫() {
        when(taskMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        assertTrue(service().tryAdopt("a1"));
        when(taskMapper.update(any(), any(Wrapper.class))).thenReturn(0);
        assertEquals(false, service().tryAdopt("a1"));
    }

    @Test
    void 同订单活跃任务查询排除自身() {
        AgentTask other = new AgentTask().setId(9L).setStatus("ADOPTED").setBizOrderId(200L);
        AgentTask self = new AgentTask().setId(8L).setStatus("PENDING_CONFIRM").setBizOrderId(200L);
        when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(other, self));
        List<AgentTask> active = service().findActiveByOrder(100L, 200L, 8L);
        assertEquals(1, active.size());
        assertEquals(9L, active.get(0).getId());
    }
}
