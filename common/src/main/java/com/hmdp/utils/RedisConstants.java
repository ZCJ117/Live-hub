package com.hmdp.utils;

import java.util.concurrent.ThreadLocalRandom;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    /** 验证码频控（SPEC-06 §5.5）：手机号 60 秒 1 次 / 24 小时 10 次，IP 24 小时 20 次 */
    public static final String LOGIN_CODE_LIMIT_KEY = "login:code:limit:phone:";
    public static final String LOGIN_CODE_COUNT_KEY = "login:code:count:phone:";
    public static final String LOGIN_CODE_IP_COUNT_KEY = "login:code:count:ip:";

    /** 空值标记 TTL，单位：秒（SPEC-05 §5.1：把负缓存窗口从 30 分钟压到 60 秒） */
    public static final Long CACHE_NULL_TTL = 60L;

    public static final String CACHE_SHOP_KEY = "cache:shop:";

    /** 二级缓存失效广播频道（二级缓存设计文档 §4.2）：消息体为被失效的 Redis 全键 */
    public static final String CACHE_INVALIDATE_CHANNEL = "cache:invalidate";

    /** 缓存基础 TTL，单位：秒（商铺与商铺分类共用，SPEC-05 G7） */
    public static final Long CACHE_TTL_BASE_SECONDS = 1800L;
    /** 缓存 TTL 抖动上限，单位：秒；实际 TTL = 基础值 + [0, 抖动) */
    public static final Integer CACHE_TTL_JITTER_SECONDS = 300;

    /**
     * 计算带抖动的缓存 TTL（秒）：基础值 + [0, 抖动上限)。
     *
     * <p>SPEC-05 G7：同一时刻写入的缓存若 TTL 完全相同，会集体失效并同时回源 DB。
     * 抖动规则集中在此，避免各调用点各写一份而漏改。
     */
    public static long cacheTtlSeconds() {
        return CACHE_TTL_BASE_SECONDS + ThreadLocalRandom.current().nextInt(CACHE_TTL_JITTER_SECONDS);
    }

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    /** 已购用户 Set —— 与 seckill.lua 的 orderKey 同拼法 */
    public static final String SECKILL_ORDER_SET_KEY = "seckill:order:";
    /**
     * 订单明细 Hash —— 与 seckill.lua 的 orderDetailKey 同拼法（本批冻结的契约），
     * field 为 orderId，value 为一行 JSON：
     * {@code {"voucherId","userId","orderId","ts","retryCount"}}，其中 {@code ts} 是
     * **epoch millis 字符串**、{@code retryCount} 是数值。
     *
     * <p>语义已从"在途"扩展为「在途 + 待处置」（SPEC-14 §2.2 第 3 点）：由
     * {@code seckill.lua} 下单成功时写入（{@code retryCount} 恒为初始 0），
     * 由 {@code SeckillInFlightCompensator} 重投时刷新、重投耗尽则移出，并由
     * {@code SeckillOrderDLQConsumer} 把死信回写进来。{@code retryCount} 只由
     * 补偿器与 DLQ 消费者递增。
     */
    public static final String SECKILL_ORDER_DETAIL_KEY = "seckill:order:detail:";
    /** 扣减幂等 Set（SPEC-03 §5.4，键为 orderId） */
    public static final String SECKILL_DEDUCT_KEY = "seckill:deduct:";
    /**
     * 需人工处置的订单列表 —— 仅由 {@code SeckillInFlightCompensator} 的
     * 「重投耗尽 → 安全释放」分支写入（SPEC-14 P0-2 B3 起死信不再回写，改为合流进明细 Hash）。
     * 稳态应为 0，非 0 即代表有订单需人工核对；长度已纳入指标 {@code seckill.pending.size}。
     */
    public static final String SECKILL_PENDING_KEY = "seckill:order:pending";
    /** seckill.lua 的临时队列（SPEC-04 §1.6：曾无消费者且无界增长，现加 LTRIM + TTL 收敛） */
    public static final String SECKILL_ORDER_QUEUE_KEY = "seckill:order:queue";
    /** 秒杀观察类 List 的长度上限（SPEC-04 §5.3/§5.6：超限丢弃最旧项） */
    public static final Integer SECKILL_LIST_MAX_SIZE = 1000;
    /** 明细 hash / 临时队列的 TTL（秒，SPEC-04 §5.2：清理失败时也不无界增长） */
    public static final Long SECKILL_DETAIL_TTL_SECONDS = 3600L;

    /**
     * 秒杀活动时间窗 Hash（字段 begin/end，值为 epoch millis）——SPEC-14 P0-3。
     *
     * <p>与 {@code seckill.lua} **无关**：脚本不读写这个 key，它由 voucher-service 写入、
     * order-service 秒杀入口读取，用于在调用脚本**之前**拒绝时间窗外的请求。
     */
    public static final String SECKILL_WINDOW_KEY = "seckill:window:";
    /** 时间窗 Hash 字段名：活动开始（epoch millis） */
    public static final String SECKILL_WINDOW_FIELD_BEGIN = "begin";
    /** 时间窗 Hash 字段名：活动结束（epoch millis） */
    public static final String SECKILL_WINDOW_FIELD_END = "end";
    /**
     * 时间窗 key 在活动结束后仍保留的时长（小时）——SPEC-14 §7 M2。
     *
     * <p>入口在 key 缺失时按「无窗口限制」放行（向后兼容历史券）。若 TTL 恰好等于活动周期，
     * 活动结束瞬间 key 过期 → 缺失 → **反而放行**，与 P0-3 要修的缺陷同构。必须长于活动周期。
     */
    public static final Long SECKILL_WINDOW_RETAIN_HOURS = 24L;

    /**
     * 秒杀 key 工厂 —— 唯一事实源（SPEC-04 §5.2）。
     *
     * <p>三处拼法（写入端 {@code seckill.lua}、清理端 {@code SeckillOrderConsumer}、
     * 读取端 {@code SeckillConsistencyServiceImpl}）历史上各写一份，导致「写的 key 和删的 key 不同」
     * 这类契约漂移（SPEC-04 §1.5）。改动此处必须同步 {@code order-service/src/main/resources/seckill.lua}，
     * 由 {@code SeckillKeyContractTest} 锁定。
     */
    public static String stockKey(Long voucherId) {
        return SECKILL_STOCK_KEY + voucherId;
    }

    public static String orderKey(Long voucherId) {
        return SECKILL_ORDER_SET_KEY + voucherId;
    }

    public static String detailKey(Long voucherId) {
        return SECKILL_ORDER_DETAIL_KEY + voucherId;
    }

    public static String windowKey(Long voucherId) {
        return SECKILL_WINDOW_KEY + voucherId;
    }
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    /** 关注流收件箱 ZSet 的 TTL（天）。SPEC-09 §1.5：避免僵尸用户的收件箱永久驻留 */
    public static final Long FEED_KEY_TTL_DAYS = 30L;
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    public static final String SHOP_LIST_KEY = "shop:list:";

    /**
     * MQ 消费者前置去重 key 前缀（SPEC-08 §5.4），完整键 = 前缀 + msgId。
     *
     * <p>同时服务 social-service（工单通知）与 rag-service 两侧的消费者幂等，
     * 故收口在此而非各自拼写，避免「写的键与判重的键不同」这类契约漂移。
     */
    public static final String MQ_DEDUP_KEY = "mq:dedup:";

}
