package com.hmdp.agent.planner;

import com.hmdp.agent.config.GlmProperties;
import com.hmdp.agent.llm.GlmClient;
import com.hmdp.agent.llm.LlmTypes;
import com.hmdp.agent.memory.ChatMemoryService;
import com.hmdp.agent.metrics.TrackEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 意图分类器（T3.1/T3.2）：lightModel + JSON mode，解析失败重试 2 次（附错误说明），3 败降级
 * 分类失败率埋点 m5_intent_parse_fail（R2：告警阈值 5%，Phase 5 看板消费）
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class IntentClassifier {

    private static final String CLASSIFY_PROMPT = """
            你是 LiveHub 智能客服的意图分类器。将用户消息分类为以下 7 类之一：
            - ORDER_QUERY：查询订单（状态、进度、订单列表，含"单子/买的东西/抢的券到哪了"等口语）
            - VOUCHER_CONSULT：优惠券咨询（使用规则、门槛、适用店铺、为什么用不了）
            - SHOP_CONSULT：商户咨询（营业时间、地址、评分、人均、菜品招牌）
            - REFUND：退款申请（想退、退钱、申请退款）
            - COMPLAINT：投诉（差评、投诉商家/平台、吐槽服务）
            - CHAT：寒暄/闲聊/超范围话题（你好、写首诗、今天天气）
            - HUMAN_DEMAND：明确要求人工（转人工、真人客服、人工服务）

            规则：
            1. 一句话包含多个诉求 → 主意图取第一个，subtasks 按顺序列出每个子诉求（简短动宾短语）。
            2. entities 提取：orderId/voucherId/shopId/shopName（消息中出现才填，无则空对象）。
            3. confidence 为 0~1 的小数，表达越口语化/含错别字置信度可适当降低。
            4. 只输出 JSON 对象，schema：{"intent":"...","entities":{},"confidence":0.0,"subtasks":[]}

            示例：
            "我上周秒杀的券咋还没到账" → {"intent":"ORDER_QUERY","entities":{},"confidence":0.9,"subtasks":[]}
            "那个满100减30的为啥用不了啊" → {"intent":"VOUCHER_CONSULT","entities":{},"confidence":0.9,"subtasks":[]}
            "星巴克营业到几点" → {"intent":"SHOP_CONSULT","entities":{"shopName":"星巴克"},"confidence":0.9,"subtasks":[]}
            "我要退款" → {"intent":"REFUND","entities":{},"confidence":0.95,"subtasks":[]}
            "这店服务态度太差了，必须投诉" → {"intent":"COMPLAINT","entities":{},"confidence":0.9,"subtasks":[]}
            "你好呀" → {"intent":"CHAT","entities":{},"confidence":0.95,"subtasks":[]}
            "给我转人工" → {"intent":"HUMAN_DEMAND","entities":{},"confidence":0.95,"subtasks":[]}
            "查下我的单子，顺便把这个退了" → {"intent":"ORDER_QUERY","entities":{},"confidence":0.85,"subtasks":["查询我的订单","申请退款"]}
            """;

    private final GlmClient glmClient;
    private final StructuredOutputParser parser;
    private final TrackEventService trackEventService;
    private final GlmProperties glmProps;

    public ClassifyOutcome classify(String message, List<ChatMemoryService.LlmTypesMsg> history,
                                    Long sessionId, Long userId) {
        StringBuilder recent = new StringBuilder();
        if (history != null) {
            history.stream().skip(Math.max(0, history.size() - 6))
                    .forEach(m -> recent.append(m.role()).append(": ").append(m.content()).append('\n'));
        }
        String base = CLASSIFY_PROMPT + "\n\n[最近对话]\n" + recent + "\n[用户消息]\n" + message;
        String lastError = null;
        long promptTokens = 0, completionTokens = 0;

        for (int attempt = 1; attempt <= 3; attempt++) {
            String prompt = lastError == null ? base
                    : base + "\n\n注意：上次输出未通过校验（" + lastError + "），请严格按 schema 只输出一个 JSON 对象。";
            try {
                LlmTypes.Response resp = glmClient.complete(LlmTypes.Request.builder()
                        .model(glmProps.getLightModel())
                        .messages(List.of(LlmTypes.Message.user(prompt)))
                        .jsonMode(true)
                        .temperature(0.1)
                        .build());
                promptTokens += resp.getPromptTokens() == null ? 0 : resp.getPromptTokens();
                completionTokens += resp.getCompletionTokens() == null ? 0 : resp.getCompletionTokens();
                return new ClassifyOutcome(parser.parseIntent(resp.getContent()), promptTokens, completionTokens);
            } catch (LlmTypes.LlmException e) {
                lastError = "LLM 调用失败";
                trackEventService.track("m5_intent_parse_fail", sessionId, userId,
                        Map.of("attempt", attempt, "cause", "LLM_ERROR"));
                log.warn("意图分类 LLM 调用失败(第{}次): sessionId={}", attempt, sessionId);
            } catch (IntentParseException e) {
                lastError = e.getMessage();
                trackEventService.track("m5_intent_parse_fail", sessionId, userId,
                        Map.of("attempt", attempt, "cause", "PARSE_FAIL"));
                log.warn("意图分类解析失败(第{}次): {} sessionId={}", attempt, e.getMessage(), sessionId);
            }
        }
        return new ClassifyOutcome(null, promptTokens, completionTokens);
    }
}
