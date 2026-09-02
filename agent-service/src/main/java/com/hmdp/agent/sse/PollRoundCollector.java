package com.hmdp.agent.sse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * D2 降级轮询轮次收集器（FR-01 边界 2 / 4.4）
 * 聚合一轮对话的 delta 文本 + card 事件 + 完成信号，供 /agent/chat/poll 在请求线程
 * 限时等待后整体一次返回；轮次执行仍在 sseExecutor 异步线程池（PRD 4.1 不阻塞 Tomcat 工作线程）
 */
public class PollRoundCollector {

    private final StringBuilder text = new StringBuilder();
    private final List<Object> cards = new ArrayList<>();
    private final CompletableFuture<Boolean> done = new CompletableFuture<>();
    private volatile String finishReason = "OK";

    /** 事件回调（与 SSE 推送同源同序，SseSessionManager.send 拦截） */
    public synchronized void accept(String event, Object data) {
        switch (event) {
            case "delta" -> {
                if (data instanceof Map<?, ?> m && m.get("text") != null) {
                    text.append(m.get("text"));
                }
            }
            case "card" -> cards.add(data);
            case "error" -> {
                finishReason = "ERROR";
                done.complete(true);
            }
            case "done" -> {
                if (data instanceof Map<?, ?> m && m.get("finishReason") != null) {
                    finishReason = String.valueOf(m.get("finishReason"));
                }
                done.complete(true);
            }
            default -> {
                // session/tool_call/tool_result 轮询协议不回传（工具过程属状态条 UI，PRD FR-04）
            }
        }
    }

    /**
     * 限时等待本轮完成
     * @return true=已完成；false=超时（partial，收集器保留供下一轮轮询继续聚合）
     */
    public boolean await(long timeoutMillis) {
        try {
            return done.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    public String text() {
        return text.toString();
    }

    public List<Object> cards() {
        return cards;
    }

    public String finishReason() {
        return finishReason;
    }
}
