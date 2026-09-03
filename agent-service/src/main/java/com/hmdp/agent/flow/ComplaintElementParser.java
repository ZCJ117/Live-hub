package com.hmdp.agent.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 投诉要素 JSON 解析（复用 T3.2 StructuredOutputParser 模式：围栏剥离 + 截取大括号 + schema 校验）
 * 重试与降级由 ComplaintFlowService 负责
 */
@Component
public class ComplaintElementParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Element(String category, Map<String, Object> refs, String time, String demand) {
    }

    public static class ParseFail extends Exception {
        public ParseFail(String msg) {
            super(msg);
        }
    }

    public Element parse(String raw) throws ParseFail {
        JsonNode root = readTree(raw);
        JsonNode cat = root.path("category");
        String category = cat.isTextual() ? cat.asText() : null;
        // DEF-B3a 修复：非法类别值（如 LLM 输出 "ORDER|MERCHANT_SERVICE"）置 null，走追问/OTHER 兜底
        if (category != null && (category.isBlank() || !com.hmdp.agent.ticket.TicketService.VALID_CATEGORIES.contains(category))) {
            category = null;
        }
        Map<String, Object> refs;
        try {
            refs = root.path("refs").isObject()
                    ? normalizeRefs(MAPPER.convertValue(root.path("refs"), Map.class)) : Map.of();
        } catch (IllegalArgumentException e) {
            throw new ParseFail("refs 必须为对象");
        }
        JsonNode timeNode = root.path("time");
        String time = timeNode.isTextual() ? timeNode.asText() : null;
        JsonNode demandNode = root.path("demand");
        String demand = demandNode.isTextual() ? demandNode.asText() : null;
        if (demand == null && category == null && refs.isEmpty() && time == null) {
            throw new ParseFail("未抽取到任何要素");
        }
        return new Element(category, refs, time, demand);
    }

    /** DEF-B3c 修复：剔除 null 值键（LLM 时而输出 shopId:null 污染 dedup_key），数值字符串归一为 Long */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeRefs(Map<String, Object> raw) {
        Map<String, Object> refs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            if (v instanceof String s && s.matches("\\d+")) {
                v = Long.parseLong(s);
            }
            refs.put(e.getKey(), v);
        }
        return refs;
    }

    private JsonNode readTree(String raw) throws ParseFail {
        if (raw == null || raw.isBlank()) {
            throw new ParseFail("空响应");
        }
        String s = raw.trim();
        int fence = s.indexOf("```");
        if (fence >= 0) {
            int start = s.indexOf('\n', fence);
            int end = s.lastIndexOf("```");
            if (start > 0 && end > start) {
                s = s.substring(start + 1, end).trim();
            }
        }
        int l = s.indexOf('{');
        int r = s.lastIndexOf('}');
        if (l < 0 || r <= l) {
            throw new ParseFail("未找到 JSON 对象");
        }
        try {
            return MAPPER.readTree(s.substring(l, r + 1));
        } catch (Exception e) {
            throw new ParseFail("JSON 语法错误: " + e.getMessage());
        }
    }
}
