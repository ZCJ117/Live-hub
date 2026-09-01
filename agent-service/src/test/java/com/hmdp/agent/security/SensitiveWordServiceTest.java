package com.hmdp.agent.security;

import com.hmdp.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 敏感词服务：词表构建 + 首次命中 + 热更新（EnvironmentChangeEvent 重编译，D-3/验收 14） */
class SensitiveWordServiceTest {

    private SensitiveWordService service(AgentProperties props) {
        SensitiveWordService s = new SensitiveWordService(props);
        s.rebuild();
        return s;
    }

    private AgentProperties props(List<String> words) {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setSensitiveWords(words);
        return p;
    }

    @Test
    void 命中敏感词返回首个匹配() {
        SensitiveWordService s = service(props(List.of("违禁词A", "违禁词B")));
        assertEquals(Optional.of("违禁词B"), s.firstHit("这句话包含违禁词B请处理"));
        assertEquals(Optional.empty(), s.firstHit("正常咨询内容"));
    }

    @Test
    void 空词表不误伤() {
        SensitiveWordService s = service(props(List.of()));
        assertEquals(Optional.empty(), s.firstHit("任何内容"));
        assertEquals(0, s.maxWordLength());
    }

    @Test
    void 配置变更后重新编译规则_热更新() {
        AgentProperties p = props(List.of("旧词"));
        SensitiveWordService s = service(p);
        assertTrue(s.firstHit("包含旧词").isPresent());

        // 模拟 Nacos 配置刷新：改属性 → 发布 EnvironmentChangeEvent → 规则重建
        p.getSecurity().setSensitiveWords(List.of("新词"));
        s.onApplicationEvent(new org.springframework.cloud.context.environment.EnvironmentChangeEvent(
                new Object(), java.util.Set.of("agent.security.sensitive-words")));

        assertTrue(s.firstHit("包含新词").isPresent(), "热更新后新词应生效");
        assertEquals(Optional.empty(), s.firstHit("包含旧词"), "热更新后旧词应失效");
    }
}
