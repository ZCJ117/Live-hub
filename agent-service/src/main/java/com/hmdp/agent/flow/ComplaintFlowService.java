package com.hmdp.agent.flow;

import com.hmdp.agent.config.AgentProperties;
import com.hmdp.agent.dto.TicketRequest;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.entity.AgentTicket;
import com.hmdp.agent.llm.GlmClient;
import com.hmdp.agent.llm.LlmTypes;
import com.hmdp.agent.metrics.TrackEventService;
import com.hmdp.agent.planner.FlowStateService;
import com.hmdp.agent.service.AgentSessionService;
import com.hmdp.agent.sse.SseSessionManager;
import com.hmdp.agent.ticket.TicketPriorityRules;
import com.hmdp.agent.ticket.TicketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 投诉要素收集状态机（FR-09 T4.6）
 * flowState=COMPLAINING + Redis 草稿；每轮 LLM 抽取要素合并，最多追问 2 轮；
 * 摘要 lightModel ≤200 字客观摘要（失败规则模板兜底）；涉资金 → HIGH；
 * 建单失败重试 1 次 → 转人工，绝不静默丢失（FR-09 边界）
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ComplaintFlowService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DRAFT_TTL = Duration.ofMinutes(30);
    /** LLM 抽取要素重试次数（R2 同源策略） */
    private static final int EXTRACT_RETRIES = 2;

    private final GlmClient glmClient;
    private final TicketService ticketService;
    private final FlowStateService flowStateService;
    private final AgentSessionService sessionService;
    private final TrackEventService trackEventService;
    private final SseSessionManager sseManager;
    private final RedissonClient redisson;
    private final AgentProperties props;

    /** COMPLAINT 分支主入口 */
    public void handle(AgentSession session, String message) {
        Long sessionId = session.getId();
        ComplaintDraft draft = loadDraft(sessionId);

        ComplaintElementParser.Element element = extract(message);
        merge(draft, element);
        draft.setRounds(draft.getRounds() + 1);

        boolean complete = draft.getCategory() != null && draft.getDemand() != null;
        if (!complete && draft.getRounds() <= props.getTicket().getMaxCollectRounds()) {
            saveDraft(sessionId, draft);
            say(sessionId, askMissing(draft));
            return;
        }
        if (draft.getCategory() == null) {
            draft.setCategory("OTHER"); // PRD：仍不全按"其他"建单
        }
        if (draft.getDemand() == null) {
            draft.setDemand(message);
        }
        finalizeTicket(session, draft);
    }

    private void finalizeTicket(AgentSession session, ComplaintDraft draft) {
        Long sessionId = session.getId();
        TicketPriorityRules rules = new TicketPriorityRules(props);
        String priority = rules.fundRelated(draft.getDemand()) ? "HIGH" : null; // null → TicketService 默认规则
        String summary = buildSummary(draft);
        try {
            AgentTicket ticket = createWithRetry(session, draft, priority, summary);
            trackEventService.track("m5_ticket_create", sessionId, session.getUserId(), Map.of(
                    "category", ticket.getCategory(), "priority", ticket.getPriority(),
                    "ticketNo", ticket.getTicketNo()));
            say(sessionId, "已为您登记工单 " + ticket.getTicketNo()
                    + "，预计 " + ticket.getExpectedSla() + " 内由人工跟进处理，您可在\"我的-客服记录\"查看进度。");
        } catch (Exception e) {
            log.error("工单创建失败（已重试）: sessionId={}", sessionId, e);
            trackEventService.track("m5_ticket_create_fail", sessionId, session.getUserId(), Map.of());
            // FR-09 边界：建单失败 → 记录诉求 + 转人工，绝不静默丢失
            sessionService.markTransferred(sessionId, "TICKET_FAIL");
            say(sessionId, "工单登记暂时失败，您的诉求已完整记录，将由人工客服直接跟进，请稍候。");
            return;
        }
        flowStateService.setFlowState(sessionId, "IDLE");
        redisson.<String>getBucket(draftKey(sessionId)).delete();
    }

    private AgentTicket createWithRetry(AgentSession session, ComplaintDraft draft,
                                        String priority, String summary) {
        TicketRequest req = TicketRequest.of(draft.getCategory(), priority, summary, draft.getRefs());
        RuntimeException last = null;
        for (int i = 0; i < 2; i++) {
            try {
                return ticketService.create(session.getUserId(), session.getId(), req);
            } catch (RuntimeException e) {
                last = e;
                log.warn("建单第 {} 次失败: sessionId={}", i + 1, session.getId());
            }
        }
        throw last;
    }

    /** LLM 摘要 ≤200 字客观摘要；失败 → 规则模板兜底（FR-09 边界） */
    private String buildSummary(ComplaintDraft draft) {
        try {
            String prompt = "请将以下投诉要素压缩为不超过200字的客观摘要，只陈述事实（类别/涉及对象/时间/诉求），"
                    + "不含主观评价与情绪词，只输出摘要正文。\n"
                    + "要素：" + toJson(draft);
            LlmTypes.Response r = glmClient.complete(LlmTypes.Request.builder()
                    .model("glm-4-flash")
                    .messages(List.of(LlmTypes.Message.user(prompt)))
                    .temperature(0.2)
                    .build());
            if (r.getContent() != null && !r.getContent().isBlank()) {
                String s = r.getContent().trim();
                return s.length() > 200 ? s.substring(0, 200) : s;
            }
        } catch (Exception e) {
            log.warn("工单摘要生成失败，模板兜底: 诉求={}", draft.getDemand(), e);
        }
        StringBuilder sb = new StringBuilder("用户反馈");
        sb.append(draft.getCategory() == null ? "问题" : categoryText(draft.getCategory()));
        if (draft.getRefs() != null && !draft.getRefs().isEmpty()) {
            sb.append("，涉及 ").append(toJson(draft.getRefs()));
        }
        if (draft.getTime() != null) {
            sb.append("，发生时间：").append(draft.getTime());
        }
        sb.append("，诉求：").append(draft.getDemand() == null ? "待补充" : draft.getDemand());
        String s = sb.toString();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /** LLM 抽取要素（JSON mode，失败重试，仍失败返回空要素继续追问） */
    private ComplaintElementParser.Element extract(String message) {
        ComplaintElementParser parser = new ComplaintElementParser();
        String prompt = """
                从用户消息中抽取投诉要素，只输出 JSON 对象：
                {"category":"ORDER|VOUCHER|MERCHANT_SERVICE|ACCOUNT_SECURITY|OTHER|null","refs":{"orderId":数字,"voucherId":数字,"shopId":数字},"time":"发生时间原文或null","demand":"诉求原文"}
                无法判断的字段输出 null，refs 只保留能识别的键。
                用户消息：%s
                """.formatted(message);
        Exception last = null;
        for (int i = 0; i <= EXTRACT_RETRIES; i++) {
            try {
                LlmTypes.Response r = glmClient.complete(LlmTypes.Request.builder()
                        .model("glm-4-flash")
                        .messages(List.of(LlmTypes.Message.user(prompt)))
                        .jsonMode(true)
                        .temperature(0.1)
                        .build());
                return parser.parse(r.getContent());
            } catch (Exception e) {
                last = e;
            }
        }
        log.warn("要素抽取失败（用原消息作诉求）: {}", last == null ? "" : last.getMessage());
        return new ComplaintElementParser.Element(null, Map.of(), null, null);
    }

    private void merge(ComplaintDraft draft, ComplaintElementParser.Element e) {
        if (e.category() != null) {
            draft.setCategory(e.category());
        }
        if (e.refs() != null) {
            draft.getRefs().putAll(e.refs());
        }
        if (e.time() != null) {
            draft.setTime(e.time());
        }
        if (e.demand() != null) {
            draft.setDemand(e.demand());
        }
    }

    private String askMissing(ComplaintDraft draft) {
        StringBuilder sb = new StringBuilder("非常抱歉给您带来不便。为了准确登记工单，请补充：");
        if (draft.getCategory() == null) {
            sb.append("\n· 问题类别（订单问题/券问题/商户服务/账号安全）");
        }
        if (draft.getDemand() == null) {
            sb.append("\n· 您的诉求（希望如何解决）");
        }
        if (draft.getRefs().isEmpty()) {
            sb.append("\n· 涉及的订单号/券号/店铺（如方便）");
        }
        if (draft.getTime() == null) {
            sb.append("\n· 问题发生的大致时间");
        }
        return sb.toString();
    }

    private String categoryText(String category) {
        return switch (category) {
            case "ORDER" -> "订单问题";
            case "VOUCHER" -> "优惠券问题";
            case "MERCHANT_SERVICE" -> "商户服务问题";
            case "ACCOUNT_SECURITY" -> "账号安全问题";
            default -> "其他问题";
        };
    }

    private ComplaintDraft loadDraft(Long sessionId) {
        String json = redisson.<String>getBucket(draftKey(sessionId)).get();
        if (json == null) {
            return new ComplaintDraft();
        }
        try {
            return MAPPER.readValue(json, ComplaintDraft.class);
        } catch (Exception e) {
            return new ComplaintDraft();
        }
    }

    private void saveDraft(Long sessionId, ComplaintDraft draft) {
        redisson.<String>getBucket(draftKey(sessionId)).set(toJson(draft), DRAFT_TTL);
    }

    private String draftKey(Long sessionId) {
        return "agent:session:" + sessionId + ":complaintDraft";
    }

    private void say(Long sessionId, String text) {
        sseManager.send(sessionId, "delta", Map.of("text", text));
    }

    /** 供测试与状态机共用 */
    public static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
