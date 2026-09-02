package com.hmdp.agent.service;

import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.exception.BusinessException;
import com.hmdp.agent.metrics.TrackEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** FR-12 T5.1/T5.2：评价——满意/不满意语义、标签白名单、每会话仅一次、归属强绑定、埋点 */
@ExtendWith(MockitoExtension.class)
class RatingServiceTest {

    @Mock private AgentSessionService sessionService;
    @Mock private TrackEventService trackEventService;

    private RatingService service() {
        return new RatingService(sessionService, trackEventService);
    }

    private AgentSession session(String status) {
        return new AgentSession().setId(1L).setUserId(100L).setStatus(status);
    }

    @Test
    void 满意评价_落库5_标签忽略_埋点() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(session("CLOSED"));
        when(sessionService.getById(1L)).thenReturn(session("CLOSED"));
        when(sessionService.rate(eq(1L), eq(100L), eq(5), isNull())).thenReturn(true);

        RatingService.RatingOutcome o = service().rate(100L, 1L, 5, List.of("没解决问题"));

        assertTrue(o.success());
        assertEquals(5, o.rating());
        assertTrue(o.tags().isEmpty(), "满意无原因标签（FR-12：标签仅不满意时）");
        verify(trackEventService).track(eq("m5_rating_submit"), eq(1L), eq(100L),
                argThat(p -> Integer.valueOf(5).equals(p.get("score"))));
    }

    @Test
    void 不满意_白名单外标签过滤_多选保留() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(session("TRANSFERRED"));
        when(sessionService.getById(1L)).thenReturn(session("TRANSFERRED"));
        when(sessionService.rate(eq(1L), eq(100L), eq(1), eq("[\"没解决问题\",\"答非所问\"]")))
                .thenReturn(true);

        RatingService.RatingOutcome o = service().rate(100L, 1L, 1,
                List.of("没解决问题", "答非所问", "不在白名单"));

        assertTrue(o.success());
        assertEquals(List.of("没解决问题", "答非所问"), o.tags());
        verify(trackEventService).track(eq("m5_rating_submit"), eq(1L), eq(100L), any());
    }

    @Test
    void score非法中间档_拒绝() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(session("CLOSED"));
        when(sessionService.getById(1L)).thenReturn(session("CLOSED"));

        RatingService.RatingOutcome o = service().rate(100L, 1L, 3, null);

        assertFalse(o.success());
        verify(sessionService, never()).rate(anyLong(), anyLong(), anyInt(), isNull());
        verifyNoInteractions(trackEventService);
    }

    @Test
    void 重复评价_拒绝_每会话仅一次() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(session("CLOSED"));
        when(sessionService.getById(1L)).thenReturn(session("CLOSED"));
        when(sessionService.rate(eq(1L), eq(100L), eq(5), isNull())).thenReturn(false);

        RatingService.RatingOutcome o = service().rate(100L, 1L, 5, null);

        assertFalse(o.success());
        verifyNoInteractions(trackEventService);
    }

    @Test
    void 越权评价_拦截_归属强绑定() {
        when(sessionService.getOwned(1L, 999L))
                .thenThrow(new BusinessException("会话不存在或无权访问"));

        assertThrows(BusinessException.class, () -> service().rate(999L, 1L, 5, null));
        verifyNoInteractions(trackEventService);
    }

    @Test
    void 会话未结束_拒绝评价() {
        when(sessionService.getOwned(1L, 100L)).thenReturn(session("ACTIVE"));
        when(sessionService.getById(1L)).thenReturn(session("ACTIVE"));

        RatingService.RatingOutcome o = service().rate(100L, 1L, 5, null);

        assertFalse(o.success());
        verify(sessionService, never()).rate(any(), any(), anyInt(), isNull());
        verifyNoInteractions(trackEventService);
    }
}
