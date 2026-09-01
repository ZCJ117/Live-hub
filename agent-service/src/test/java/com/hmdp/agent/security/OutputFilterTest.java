package com.hmdp.agent.security;

import com.hmdp.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 输出过滤（T4.12）：滑动窗口尾持 + 命中阻断 + flush；词长决定尾持下限 */
class OutputFilterTest {

    private AgentProperties props() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setSensitiveWords(List.of("违禁词"));
        return p;
    }

    private SensitiveWordService sw() {
        SensitiveWordService s = new SensitiveWordService(props());
        s.rebuild();
        return s;
    }

    private OutputFilter filter() {
        return new OutputFilter(sw(), props());
    }

    @Test
    void 无命中_流式原样转发_flush后完整() {
        StringBuilder out = new StringBuilder();
        OutputFilter.FilteredStream fs = filter().stream(out::append);
        fs.accept("这是正常的");
        fs.accept("回答内容");
        assertFalse(fs.isBlocked());
        fs.flush();
        assertEquals("这是正常的回答内容", out.toString());
    }

    @Test
    void 命中阻断_已批准前缀之外不再转发() {
        StringBuilder out = new StringBuilder();
        OutputFilter.FilteredStream fs = filter().stream(out::append);
        fs.accept("正常开头，后面出现");
        fs.accept("违禁词了");
        assertTrue(fs.isBlocked());
        fs.flush();
        assertFalse(out.toString().contains("违禁词"), "敏感词泄漏到输出: " + out);
    }

    @Test
    void 命中后剩余delta全部吞掉() {
        StringBuilder out = new StringBuilder();
        OutputFilter.FilteredStream fs = filter().stream(out::append);
        fs.accept("a");
        fs.accept("违禁词");
        fs.accept("后续内容也不转发");
        assertTrue(fs.isBlocked());
        fs.flush();
        assertFalse(out.toString().contains("后续内容"));
    }

    @Test
    void 整段检查_非流式() {
        assertEquals(Optional.of("违禁词"), filter().firstHit("含违禁词的整段"));
        assertEquals(Optional.empty(), filter().firstHit("干净内容"));
    }

    @Test
    void 尾持下限_不小于最长敏感词() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setSensitiveWords(List.of("一个超长敏感词汇测试"));
        p.getSecurity().setOutputFilterTailHold(4);
        SensitiveWordService s = new SensitiveWordService(p);
        s.rebuild();
        OutputFilter f = new OutputFilter(s, p);
        StringBuilder out = new StringBuilder();
        OutputFilter.FilteredStream fs = f.stream(out::append);
        fs.accept("前缀+一个超长敏感词汇测试");
        assertTrue(fs.isBlocked());
        fs.flush();
        assertFalse(out.toString().contains("一个超长敏感词汇测试"));
    }
}
