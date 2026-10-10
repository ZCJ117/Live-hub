package com.hmdp.order.service;

/**
 * 秒杀失败文案（SPEC-03 §9 A8「返回可区分的错误码」）
 *
 * <p>{@code Result} 只有 {@code success}/{@code errorMsg}，没有 code 字段，因此**错误文案本身
 * 就是错误码**：每一类失败必须给出互不相同的文案，否则调用方与运维无法区分。
 *
 * <p>原有实现把两类完全不同的故障都回成「系统繁忙，请稍后重试」——运维既看不出是券没预热，
 * 也看不出是 broker 发不出去，而这两件事的处置动作毫无共同点。
 */
public final class SeckillFailMessages {

    private SeckillFailMessages() {
    }

    /** Redis 库存 key 缺失：券未预热/Redis 被 flush，属运维态，需重新预热 */
    public static final String STOCK_KEY_MISSING = "秒杀通道未就绪，请稍后重试";

    /** 库存不足：正常售罄 */
    public static final String STOCK_INSUFFICIENT = "库存不足";

    /** 重复下单：一人一单 */
    public static final String DUPLICATE_ORDER = "不能重复下单";

    /** MQ 发送失败且已回滚 Redis 预扣：消息中间件故障 */
    public static final String MQ_SEND_FAILED = "订单提交繁忙，请稍后重试";
}
