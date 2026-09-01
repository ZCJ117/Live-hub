package com.hmdp.agent.planner;

import java.util.List;
import java.util.Map;

/**
 * 意图分类结果（FR-03：{intent, entities, confidence} + 复合意图 subtasks）
 */
public record IntentResult(Intent intent, Map<String, Object> entities,
                           double confidence, List<String> subtasks) {
}
