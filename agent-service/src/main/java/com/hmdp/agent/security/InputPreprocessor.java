package com.hmdp.agent.security;

import cn.hutool.core.util.StrUtil;
import com.hmdp.agent.config.AgentProperties;

/**
 * 消息输入预处理（FR-02 边界，Phase 2 基础版）
 * - 空消息/纯表情 → 引导话术，不进入 LLM
 * - 超长消息 → 截断并提示（PRD：工具调用场景长文本无意义）
 * 注入检测/敏感词为 Phase 4 FR-11 范围，此处仅预留过滤器链接口
 */
public final class InputPreprocessor {

    /** 判定为纯表情/空白（ emoji 区段 + 变体选择符 + 零宽字符） */
    private static final String EMOJI_ONLY =
            "^[\\p{So}\\p{Sk}\\p{Cf}\\p{Cs}\\p{Co}\\s\\u200d\\uFE0F]+$";

    private InputPreprocessor() {
    }

    public enum Verdict {GUIDE_EMOJI, TRUNCATED, OK}

    public record PreprocessResult(Verdict verdict, String message, boolean pureEmoji) {
    }

    public static PreprocessResult preprocess(String raw, AgentProperties props) {
        String msg = StrUtil.trimToEmpty(raw);
        if (msg.isEmpty()) {
            return new PreprocessResult(Verdict.GUIDE_EMOJI,
                    "请描述您遇到的问题，例如：查询我的订单 / 咨询优惠券使用规则", true);
        }
        if (msg.matches(EMOJI_ONLY)) {
            return new PreprocessResult(Verdict.GUIDE_EMOJI,
                    "看起来您只发送了表情～可以用文字描述您的问题，我来帮您查询订单或解答优惠券疑问", true);
        }
        int max = props.getMessage().getMaxLength();
        if (msg.length() > max) {
            // DEF-A6 修复：不再把提示内嵌进 user message（用户不可见）；截断事实由 doChat 以独立 delta 推送给用户
            return new PreprocessResult(Verdict.TRUNCATED, msg.substring(0, max), false);
        }
        return new PreprocessResult(Verdict.OK, msg, false);
    }

    public static final String WELCOME_MESSAGE =
            "本服务由 AI 提供，内容由人工智能生成。\n"
            + "您好，我是 LiveHub 智能客服～\n我可以帮您：\n· 查询订单（如：我上周抢的券怎么还没到）\n· 咨询优惠券使用规则\n· 申请退款、提交投诉\n· 随时转接人工客服";
}
