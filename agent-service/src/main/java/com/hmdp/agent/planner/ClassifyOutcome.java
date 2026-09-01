package com.hmdp.agent.planner;

/**
 * 分类产出：result=null 表示 3 次尝试均失败（调用方降级菜单）；token 用量供 T3.13 成本记账
 */
public record ClassifyOutcome(IntentResult result, long promptTokens, long completionTokens) {
    public boolean failed() {
        return result == null;
    }
}
