package com.hmdp.agent.security;

import com.hmdp.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * LLM 输出安全过滤（FR-11 T4.12/R10）
 * 流式滑动窗口：尾持 max(tailHold, 最长敏感词) 字符不立即下发；每窗全文扫描；
 * 命中 → 阻断转发（已批准前缀之外不流出），由调用方走"重生成 1 次 → 兜底话术"链路。
 * 无命中时首 delta 即时下发（首 token P90 不受影响）。
 */
@Component
@RequiredArgsConstructor
public class OutputFilter {

    private final SensitiveWordService sensitiveWordService;
    private final AgentProperties props;

    /** 创建流式过滤器，包裹 onDelta 回调 */
    public FilteredStream stream(Consumer<String> delegate) {
        int tail = Math.max(props.getSecurity().getOutputFilterTailHold(),
                Math.max(sensitiveWordService.maxWordLength(), 1));
        return new FilteredStream(delegate, sensitiveWordService, tail);
    }

    /** 非流式整段检查 */
    public Optional<String> firstHit(String text) {
        return sensitiveWordService.firstHit(text);
    }

    public static final class FilteredStream {
        private final Consumer<String> delegate;
        private final SensitiveWordService sensitiveWordService;
        private final int tailHold;
        private final StringBuilder pending = new StringBuilder();
        private boolean blocked;

        private FilteredStream(Consumer<String> delegate, SensitiveWordService sw, int tailHold) {
            this.delegate = delegate;
            this.sensitiveWordService = sw;
            this.tailHold = tailHold;
        }

        public void accept(String delta) {
            if (blocked || delta == null || delta.isEmpty()) {
                return;
            }
            pending.append(delta);
            String full = pending.toString();
            if (sensitiveWordService.firstHit(full).isPresent()) {
                blocked = true;
                pending.setLength(0);
                return;
            }
            int safeLen = full.length() - tailHold;
            if (safeLen > 0) {
                delegate.accept(full.substring(0, safeLen));
                pending.delete(0, safeLen);
            }
        }

        public boolean isBlocked() {
            return blocked;
        }

        /** 流结束后转发尾持残余（仅未阻断时） */
        public void flush() {
            if (!blocked && pending.length() > 0) {
                delegate.accept(pending.toString());
                pending.setLength(0);
            }
        }
    }
}
