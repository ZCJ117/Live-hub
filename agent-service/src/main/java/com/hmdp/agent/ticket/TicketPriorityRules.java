package com.hmdp.agent.ticket;

import com.hmdp.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工单优先级规则（FR-09 T4.6：涉资金=HIGH）
 * 关键词表 agent.ticket.fund-keywords（yaml 可配）
 */
@Component
public class TicketPriorityRules {

    private final AgentProperties props;

    public TicketPriorityRules(AgentProperties props) {
        this.props = props;
    }

    /** 诉求/摘要文本是否涉资金（命中 → priority=HIGH） */
    public boolean fundRelated(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        List<String> keys = props.getTicket().getFundKeywords();
        return keys != null && keys.stream().anyMatch(text::contains);
    }
}
