package com.hmdp.agent.security;

import com.hmdp.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 敏感词表服务（FR-11 T4.11，D-3）
 * 词表来自 agent.security.sensitive-words（本地 yaml 兜底，Nacos 托管热更新）；
 * EnvironmentChangeEvent 触发重编译 Pattern（Nacos 控制台改配置秒级生效，验收 14：1 分钟内）
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SensitiveWordService implements ApplicationListener<EnvironmentChangeEvent> {

    private final AgentProperties props;
    private final ConfigurableEnvironment environment;

    private volatile List<Pattern> patterns = List.of();
    private volatile int maxWordLength = 0;

    /** 首个命中的敏感词（输入检测/输出过滤共用） */
    public Optional<String> firstHit(String text) {
        if (text == null || text.isEmpty() || patterns.isEmpty()) {
            return Optional.empty();
        }
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                // Pattern.quote 包裹过（\Q..\E），还原为原始敏感词
                return Optional.of(p.pattern().replace("\\Q", "").replace("\\E", ""));
            }
        }
        return Optional.empty();
    }

    /** 最长敏感词长度（OutputFilter 滑动窗口尾部长度下限） */
    public int maxWordLength() {
        return maxWordLength;
    }

    /** Nacos 配置刷新 → 重建词表（D-3） */
    @Override
    public void onApplicationEvent(EnvironmentChangeEvent event) {
        if (event.getKeys() == null || event.getKeys().stream().noneMatch(k -> k.startsWith("agent.security"))) {
            return;
        }
        rebuild();
    }

    /** 启动时构建；热更新时直接从 Environment 重新绑定（T14 联调修复：监听器与
     *  ConfigurationPropertiesRebinder 对同一事件无序，读 props 可能拿到 rebind 前的旧值，
     *  而 Nacos 属性源更新先于事件发布，故 Environment 绑定在任意监听顺序下都读到新值） */
    @jakarta.annotation.PostConstruct
    void rebuild() {
        AgentProperties.Security security = org.springframework.boot.context.properties.bind.Binder
                .get(environment)
                .bind("agent.security", org.springframework.boot.context.properties.bind.Bindable
                        .of(AgentProperties.Security.class))
                .orElseGet(props::getSecurity);
        List<String> words = security.getSensitiveWords() == null
                ? List.<String>of() : security.getSensitiveWords();
        this.patterns = words.stream()
                .filter(w -> w != null && !w.isBlank())
                .map(w -> Pattern.compile(Pattern.quote(w.trim())))
                .toList();
        this.maxWordLength = words.stream()
                .filter(w -> w != null && !w.isBlank())
                .map(w -> w.trim().length())
                .max(Comparator.naturalOrder())
                .orElse(0);
        log.info("敏感词表已加载/刷新: {} 条, 最长 {} 字", patterns.size(), maxWordLength);
    }
}
