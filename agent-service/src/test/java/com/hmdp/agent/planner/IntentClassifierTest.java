package com.hmdp.agent.planner;

import com.hmdp.agent.config.GlmProperties;
import com.hmdp.agent.llm.GlmClient;
import com.hmdp.agent.llm.LlmTypes;
import com.hmdp.agent.memory.ChatMemoryService;
import com.hmdp.agent.metrics.TrackEventService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 意图分类器单测（T3.1/T3.2）：JSON mode + 解析失败重试 2 次 + 失败埋点
 */
class IntentClassifierTest {

    private final GlmClient glmClient = mock(GlmClient.class);
    private final TrackEventService track = mock(TrackEventService.class);
    private final GlmProperties glmProps = new GlmProperties();
    private final IntentClassifier classifier =
            new IntentClassifier(glmClient, new StructuredOutputParser(), track, glmProps);

    private void llmReturns(String... contents) {
        LlmTypes.Response[] responses = Arrays.stream(contents)
                .map(c -> LlmTypes.Response.builder()
                        .content(c).promptTokens(100L).completionTokens(20L).build())
                .toArray(LlmTypes.Response[]::new);
        when(glmClient.complete(any(LlmTypes.Request.class))).thenReturn(responses[0],
                Arrays.copyOfRange(responses, 1, responses.length));
    }

    @Test
    void classifies_on_first_attempt() {
        llmReturns("{\"intent\":\"ORDER_QUERY\",\"entities\":{},\"confidence\":0.9,\"subtasks\":[]}");
        ClassifyOutcome outcome = classifier.classify("查订单", List.of(), 1L, 2L);
        assertEquals(Intent.ORDER_QUERY, outcome.result().intent());
        assertEquals(120, outcome.promptTokens() + outcome.completionTokens());
        verify(glmClient, times(1)).complete(any());
        verify(track, never()).track(any(), any(), any(), any());
    }

    @Test
    void retries_with_error_feedback_then_succeeds() {
        llmReturns("垃圾输出", "{\"intent\":\"CHAT\",\"entities\":{},\"confidence\":0.9,\"subtasks\":[]}");
        ClassifyOutcome outcome = classifier.classify("你好", List.of(), 1L, 2L);
        assertEquals(Intent.CHAT, outcome.result().intent());
        verify(glmClient, times(2)).complete(any());
        // 重试 prompt 携带错误说明（T3.2 步骤 3）
        ArgumentCaptor<LlmTypes.Request> captor = ArgumentCaptor.forClass(LlmTypes.Request.class);
        verify(glmClient, times(2)).complete(captor.capture());
        assertTrue(captor.getAllValues().get(1).getMessages().get(0).getContent().contains("未通过校验"));
        verify(track, times(1)).track(eq("m5_intent_parse_fail"), eq(1L), eq(2L), any());
    }

    @Test
    void fails_after_three_attempts() {
        llmReturns("垃圾1", "垃圾2", "垃圾3");
        ClassifyOutcome outcome = classifier.classify("你好", List.of(), 1L, 2L);
        assertTrue(outcome.failed());
        verify(glmClient, times(3)).complete(any());
        verify(track, times(3)).track(eq("m5_intent_parse_fail"), any(), any(), any());
    }

    @Test
    void llm_exception_fails_fast_without_retry() {
        when(glmClient.complete(any())).thenThrow(new LlmTypes.LlmException("timeout"));
        ClassifyOutcome outcome = classifier.classify("你好", List.of(), 1L, 2L);
        assertTrue(outcome.failed());
        verify(glmClient, times(1)).complete(any());
        verify(track, times(1)).track(eq("m5_intent_parse_fail"), any(), any(), any());
    }

    @Test
    void uses_light_model_with_json_mode() {
        llmReturns("{\"intent\":\"CHAT\",\"entities\":{},\"confidence\":0.9,\"subtasks\":[]}");
        classifier.classify("你好", List.of(new ChatMemoryService.LlmTypesMsg("user", "嗨")), 1L, 2L);
        ArgumentCaptor<LlmTypes.Request> captor = ArgumentCaptor.forClass(LlmTypes.Request.class);
        verify(glmClient).complete(captor.capture());
        assertEquals(glmProps.getLightModel(), captor.getValue().getModel());
        assertTrue(captor.getValue().isJsonMode());
        // 最近对话历史注入 prompt（多轮指代上下文）
        assertTrue(captor.getValue().getMessages().get(0).getContent().contains("user: 嗨"));
    }
}
