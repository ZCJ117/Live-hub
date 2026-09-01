package com.hmdp.agent.security;

import com.hmdp.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 情绪检测：高置信触发 / 低置信不误伤（R8，FR-10 验收 9 触发条件 4） */
class EmotionDetectorTest {

    private EmotionDetector detector(int minHits) {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setEmotionMinHits(minHits);
        return new EmotionDetector(p);
    }

    @Test
    void 三命中_高置信触发() {
        assertTrue(detector(3).isHighlyNegative("你们这就是骗子平台，服务垃圾，我要曝光你们！"));
    }

    @Test
    void 单词_低置信不触发() {
        assertFalse(detector(3).isHighlyNegative("这体验真垃圾"));
        assertFalse(detector(3).isHighlyNegative("我要投诉这家店的态度"));
    }

    @Test
    void 重复词只计一次() {
        assertEquals(1, detector(3).countHits("垃圾垃圾垃圾"));
    }

    @Test
    void 正常不满不触发() {
        assertFalse(detector(3).isHighlyNegative("退款有点慢，希望能加快处理"));
        assertFalse(detector(3).isHighlyNegative(null));
    }
}
