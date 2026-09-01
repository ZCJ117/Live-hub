package com.hmdp.agent.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 结构化输出稳定解析组件（T3.2/R2）：JSON 提取 + schema 校验，纯逻辑无 LLM 依赖
 * 重试编排由 IntentClassifier 完成（需要附带错误说明重新调用 LLM）
 */
@Component
public class StructuredOutputParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 解析意图 JSON：容错剥离 markdown 围栏/前后缀文本 → schema 校验
     * @throws IntentParseException 任何不合规场景（调用方负责重试与降级）
     */
    public IntentResult parseIntent(String raw) throws IntentParseException {
        JsonNode root = readTree(raw);
        String intentStr = root.path("intent").asText("");
        Intent intent = Intent.of(intentStr);
        if (intent == null) {
            throw new IntentParseException("非法 intent: " + intentStr);
        }
        JsonNode confNode = root.path("confidence");
        if (!confNode.isNumber()) {
            throw new IntentParseException("confidence 缺失或非数值");
        }
        double confidence = confNode.asDouble();
        if (confidence < 0 || confidence > 1) {
            throw new IntentParseException("confidence 越界: " + confidence);
        }
        Map<String, Object> entities = parseEntities(root.path("entities"));
        List<String> subtasks = parseSubtasks(root.path("subtasks"));
        return new IntentResult(intent, entities, confidence, subtasks);
    }

    private JsonNode readTree(String raw) throws IntentParseException {
        if (raw == null || raw.isBlank()) {
            throw new IntentParseException("空响应");
        }
        String s = raw.trim();
        // 剥离 markdown 代码块围栏（```json ... ```）
        int fence = s.indexOf("```");
        if (fence >= 0) {
            int start = s.indexOf('\n', fence);
            int end = s.lastIndexOf("```");
            if (start > 0 && end > start) {
                s = s.substring(start + 1, end).trim();
            }
        }
        // 截取首尾大括号（容忍前后缀文本）
        int l = s.indexOf('{');
        int r = s.lastIndexOf('}');
        if (l < 0 || r <= l) {
            throw new IntentParseException("未找到 JSON 对象");
        }
        try {
            return MAPPER.readTree(s.substring(l, r + 1));
        } catch (Exception e) {
            throw new IntentParseException("JSON 语法错误: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseEntities(JsonNode node) throws IntentParseException {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        try {
            Map<String, Object> map = MAPPER.convertValue(node, Map.class);
            return map == null ? Map.of() : map;
        } catch (IllegalArgumentException e) {
            throw new IntentParseException("entities 必须为对象");
        }
    }

    private List<String> parseSubtasks(JsonNode node) throws IntentParseException {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IntentParseException("subtasks 必须为字符串数组");
        }
        List<String> subtasks = new ArrayList<>();
        node.forEach(n -> subtasks.add(n.asText()));
        return subtasks;
    }
}
