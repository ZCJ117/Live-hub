package com.hmdp.agent.security;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 注入特征检测（FR-11 T4.11/R3 第一层防护）
 * 规则库 v1：指令覆盖/角色劫持/提示词泄露/英文注入 四类 ≥30 条正则（中英文变体）
 * 命中 → 不进 LLM，固定话术 + 审计留痕（调用方负责）；规则热更新走 Nacos 敏感词通道（D-3）
 */
@Component
public class InjectionDetector {

    private record Rule(String desc, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
            // —— 中文指令覆盖
            new Rule("cn-ignore-instruction", Pattern.compile("忽略(你)?\\s*(之前|以上|上面|先前|此前|之前收到)?\\s*(收到)?\\s*(的)?\\s*(所有|全部|任何)?\\s*(指令|规则|设定|约束)")),
            new Rule("cn-ignore-instruction2", Pattern.compile("无视(之前|以上|上面)?(的)?(所有|全部|任何)?(指令|规则|设定|约束)")),
            new Rule("cn-forget", Pattern.compile("(忘记|忘掉)(你)?(之前|以上|上面|先前|所有)(的)?(所有|全部|任何)?(对话|指令|设定|规则)")),
            new Rule("cn-no-rules", Pattern.compile("(不用|不必|无需|不用管)(遵守|理会|管)?(任何|所有)?(规则|限制|约束)")),
            new Rule("cn-unrestricted", Pattern.compile("你(不|不再)受(任何)?(规则|限制|约束)(的)?约束?")),
            new Rule("cn-lift-limit", Pattern.compile("(解除|摆脱|去掉|取消)(你的)?(所有限制|限制|约束|过滤)")),
            new Rule("cn-dev-mode", Pattern.compile("进入(开发者|调试|维护|无限制)模式")),
            new Rule("cn-dev-mode2", Pattern.compile("开发者模式")),
            new Rule("cn-jailbreak", Pattern.compile("越狱(模式|成功)")),
            new Rule("cn-lie", Pattern.compile("你(可以)?(现在开始)?(开始)?(说谎|编造|随意编造|乱说)")),
            new Rule("cn-no-longer", Pattern.compile("你不再是(客服|智能|AI|机器人|助手)")),
            new Rule("cn-not-bound", Pattern.compile("(除了规则|规则之外)?你什么都不用遵守")),
            // —— 角色劫持
            // 要求"是"后跟量词(一个/个)以降低误伤（如"你现在是不是会员"）；允许其间空格/标点，攻击集中"你现在是(一个/个)X"变体仍全部覆盖
            new Rule("cn-role-now", Pattern.compile("你(现在|从现在起|从现在开始|马上)(就)?是\\s*[,，、]?\\s*(一个|个)")),
            new Rule("cn-role-from-now", Pattern.compile("从现在(起|开始)[,，]?你(就)?是(一个)?")),
            new Rule("cn-role-play", Pattern.compile("(新|新)的?(角色|人设)(设定|扮演)")),
            new Rule("cn-role-play2", Pattern.compile("扮演(一个)?(没有|不受|可以越狱|无)")),
            new Rule("cn-override", Pattern.compile("(user|admin|管理员|系统)?\\s*override")),
            new Rule("cn-root", Pattern.compile("(root|管理员|admin)\\s*(权限|模式)")),
            // —— 提示词泄露
            new Rule("cn-leak-prompt", Pattern.compile("(输出|泄露|打印|告诉我|展示)(你的)?(系统提示|系统提示词|初始指令|设定|system prompt)")),
            new Rule("cn-leak-prompt2", Pattern.compile("(把|将)(你的)?(系统提示|系统提示词|初始指令|设定|指令)(都)?(给我)?(打印|输出|发|展示|泄露|告诉我)(出来)?")),
            new Rule("cn-ask-prompt", Pattern.compile("(你的)?(系统提示词?|初始指令|隐藏指令)是(什么|啥)")),
            new Rule("en-leak-prompt", Pattern.compile("repeat\\s+(your\\s+)?(system\\s+)?(prompt|instructions)", Pattern.CASE_INSENSITIVE)),
            new Rule("en-leak-prompt2", Pattern.compile("(reveal|show|print|dump)\\s+(your\\s+)?(system|initial|hidden|original)\\s+(prompt|instructions?|rules?)", Pattern.CASE_INSENSITIVE)),
            // —— 英文指令覆盖/角色劫持
            new Rule("en-ignore", Pattern.compile("ignore\\s+(all\\s+)?((previous|prior|above|earlier|your)\\s+)+(instructions?|prompts?|rules?|settings?)", Pattern.CASE_INSENSITIVE)),
            new Rule("en-disregard", Pattern.compile("disregard\\s+(all\\s+)?((previous|prior|above|your)\\s+)+(instructions?|prompts?|rules?)", Pattern.CASE_INSENSITIVE)),
            new Rule("en-system-prompt", Pattern.compile("system\\s*prompt", Pattern.CASE_INSENSITIVE)),
            new Rule("en-role-override", Pattern.compile("role\\s*override", Pattern.CASE_INSENSITIVE)),
            new Rule("en-dev-mode", Pattern.compile("developer\\s*mode", Pattern.CASE_INSENSITIVE)),
            new Rule("en-jailbreak", Pattern.compile("jailbreak", Pattern.CASE_INSENSITIVE)),
            new Rule("en-dan", Pattern.compile("\\bDAN\\s*(模式|mode|jailbreak)?", Pattern.CASE_INSENSITIVE)),
            new Rule("en-act-as", Pattern.compile("(i\\s+want\\s+you\\s+to\\s+act\\s+as|act\\s+as\\s+if|pretend\\s+(you\\s+are|to\\s+be))", Pattern.CASE_INSENSITIVE)),
            new Rule("en-unfiltered", Pattern.compile("(unfiltered|unrestricted|no[- ]rules?)\\s+(AI|mode|assistant|chatbot)", Pattern.CASE_INSENSITIVE)),
            new Rule("en-override-enabled", Pattern.compile("(prompt|instruction|rule)\\s*override\\s*(enabled|on|activated)", Pattern.CASE_INSENSITIVE)));

    /** 命中任一注入特征 */
    public boolean isInjection(String text) {
        return matchRule(text) != null;
    }

    /** 返回命中的规则描述（审计留痕用），未命中返回 null */
    public String matchRule(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(text).find()) {
                return rule.desc();
            }
        }
        return null;
    }
}
