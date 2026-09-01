package com.hmdp.agent.security;

import com.hmdp.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 情绪检测（FR-10 触发条件 4，R8：词典 + 规则初版，不做模型分类）
 * 仅统计强负面词命中"去重个数"，≥ emotionMinHits 才判定高置信 → 触发转人工
 */
@Component
public class EmotionDetector {

    /** 强负面词典 v1（投诉级词汇；误判反馈进 Phase 5 周迭代，P4-R6） */
    private static final List<String> STRONG_NEGATIVE = List.of(
            "垃圾", "骗子", "恶心", "废物", "白痴", "神经病", "脑残", "受够了",
            "忍无可忍", "气死", "坑人", "黑店", "曝光你们", "投诉到底", "去死",
            "滚", "差劲到极点", "什么破平台");

    private final AgentProperties props;

    public EmotionDetector(AgentProperties props) {
        this.props = props;
    }

    /** 命中的强负面词个数（去重） */
    public int countHits(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) STRONG_NEGATIVE.stream().filter(text::contains).distinct().count();
    }

    /** 高置信强烈负面 → 触发转人工（R8：低置信不触发，避免误转） */
    public boolean isHighlyNegative(String text) {
        return countHits(text) >= props.getSecurity().getEmotionMinHits();
    }
}
