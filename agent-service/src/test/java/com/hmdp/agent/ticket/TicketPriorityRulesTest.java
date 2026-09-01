package com.hmdp.agent.ticket;

import com.hmdp.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-09 验收 6：涉资金问题 priority=高 覆盖率 100%（规则测试） */
class TicketPriorityRulesTest {

    private TicketPriorityRules rules() {
        AgentProperties p = new AgentProperties();
        return new TicketPriorityRules(p);
    }

    @Test
    void 涉资金关键词_全部命中HIGH() {
        for (String text : new String[]{
                "退款失败了怎么办", "被重复扣款了", "多扣了我十块钱", "少扣了是不是要补",
                "扣款没成功券也没到", "资金安全有问题", "我的退款未到账"}) {
            assertTrue(rules().fundRelated(text), "应判定涉资金: " + text);
        }
    }

    @Test
    void 普通问题不命中() {
        assertFalse(rules().fundRelated("这家店态度太差"));
        assertFalse(rules().fundRelated("优惠券怎么使用"));
        assertFalse(rules().fundRelated(""));
        assertFalse(rules().fundRelated(null));
    }
}
