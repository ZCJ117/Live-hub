package com.hmdp.agent.security;

import com.hmdp.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输入预处理与脱敏单测（FR-02 边界 / FR-04 验收 2）
 */
class InputPreprocessorTest {

    private final AgentProperties props = new AgentProperties();

    @Test
    void emptyMessage_guides() {
        var r = InputPreprocessor.preprocess("   ", props);
        assertEquals(InputPreprocessor.Verdict.GUIDE_EMOJI, r.verdict());
        assertTrue(r.pureEmoji());
    }

    @Test
    void pureEmoji_guides_without_llm() {
        var r = InputPreprocessor.preprocess("\uD83D\uDE00\uD83D\uDE00 \uFE0F", props);
        assertEquals(InputPreprocessor.Verdict.GUIDE_EMOJI, r.verdict());
    }

    @Test
    void overlongMessage_truncated_to_500() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) sb.append("字");
        var r = InputPreprocessor.preprocess(sb.toString(), props);
        assertEquals(InputPreprocessor.Verdict.TRUNCATED, r.verdict());
        assertEquals(500 + "\n（消息过长已截断，请精简后重发）".length(), r.message().length());
    }

    @Test
    void normalMessage_ok() {
        var r = InputPreprocessor.preprocess(" 我上周抢的券怎么还没到？ ", props);
        assertEquals(InputPreprocessor.Verdict.OK, r.verdict());
        assertEquals("我上周抢的券怎么还没到？", r.message());
    }

    @Test
    void phoneMasked() {
        assertEquals("联系方式 138****1234 保留", Desensitizer.mask("联系方式 13812341234 保留"));
        assertFalse(Desensitizer.mask("联系电话:15912345678").contains("15912345678"));
    }

    @Test
    void bankCardMasked_keepLast4() {
        String masked = Desensitizer.mask("卡号 6222021234567890123 请核对");
        assertFalse(masked.contains("6222021234567890123"));
        assertTrue(masked.endsWith("0123 请核对"));
    }

    @Test
    void blankOrNull_passthrough() {
        assertNull(Desensitizer.mask(null));
        assertEquals("", Desensitizer.mask(""));
        assertEquals("普通文本", Desensitizer.mask("普通文本"));
    }
}
