package com.hmdp.agent.planner;

import java.util.List;

/**
 * Planner 分流决策（FR-03）：interruptNotice 非空 = 上下文冲突中断提示（T3.5，先于回答推送）
 */
public record PlanDecision(PlanType type, Intent intent, double confidence,
                           List<String> subtasks, String clarifyText, String interruptNotice,
                           long classifyPromptTokens, long classifyCompletionTokens) {

    public enum PlanType {
        /** 查询类 → ReAct 工具循环（subtasks 非空 = 复合意图） */
        REACT,
        /** CHAT 直答（免工具，light 档，T3.6） */
        CHAT_DIRECT,
        /** 澄清话术（confidence < 0.6，T3.3） */
        CLARIFY,
        /** 降级菜单卡片（澄清第 3 轮 / 解析 3 败，T3.2/T3.3） */
        FALLBACK_MENU,
        /** 退款查证框架（Phase 4 接确认卡片） */
        REFUND,
        /** 投诉要素收集（Phase 4 状态机） */
        COMPLAINT,
        /** 转人工桩（Phase 4 实装 FR-10） */
        HUMAN_DEMAND
    }

    static PlanDecision menu(Intent intent, double confidence, ClassifyOutcome outcome) {
        return new PlanDecision(PlanType.FALLBACK_MENU, intent, confidence, List.of(), null, null,
                outcome.promptTokens(), outcome.completionTokens());
    }
}
