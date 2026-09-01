package com.hmdp.agent.security;

import cn.hutool.core.util.StrUtil;

import java.util.regex.Pattern;

/**
 * 数据脱敏（FR-04 验收 2 / 4.2 数据红线）
 * 进 LLM 上下文、SSE 事件、审计摘要前的统一脱敏出口
 */
public final class Desensitizer {

    /** 中国大陆手机号 */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d)(\\d{4})(\\d{4})(?!\\d)");
    /** 银行卡号（12-19 位连续数字） */
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)(\\d{6})\\d{6,13}(?!\\d)");

    private Desensitizer() {
    }

    public static String mask(String text) {
        if (StrUtil.isBlank(text)) {
            return text;
        }
        String result = text;
        // 支付凭证/卡号：保留后 4 位
        result = BANK_CARD.matcher(result).replaceAll(m -> "****" + m.group(0).substring(m.group(0).length() - 4));
        // 手机号：138****1234
        result = PHONE.matcher(result).replaceAll("$1****$3");
        return result;
    }
}
