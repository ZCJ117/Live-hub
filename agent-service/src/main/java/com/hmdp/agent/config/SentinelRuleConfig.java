package com.hmdp.agent.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.hmdp.agent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 编程式降级规则（T4.14，D-2：Core 无 Dashboard）
 * 每个注册工具一条规则：慢调用 RT>2s 占比>50%（10 个请求起判）→ 熔断 10s → TOOL_DEGRADE 快速失败
 * 规则随 ToolRegistry 工具清单自动生成，新增工具零配置
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SentinelRuleConfig implements ApplicationRunner {

    private final ToolRegistry toolRegistry;

    @Override
    public void run(ApplicationArguments args) {
        List<DegradeRule> rules = new ArrayList<>();
        toolRegistry.getTools().keySet().forEach(toolName -> {
            DegradeRule rule = new DegradeRule(toolName)
                    .setGrade(RuleConstant.DEGRADE_GRADE_RT)
                    .setCount(2000)               // RT 阈值 2000ms（= Feign 超时上限，PRD 4.1）
                    .setSlowRatioThreshold(0.5)   // 慢调用占比 50%
                    .setMinRequestAmount(10)      // 统计窗口内最小请求数
                    .setStatIntervalMs(10_000)    // 统计窗口 10s
                    .setTimeWindow(10);           // 熔断时长 10s
            rules.add(rule);
        });
        DegradeRuleManager.loadRules(rules);
        log.info("Sentinel 降级规则已加载: {} 条（工具层）", rules.size());
    }
}
