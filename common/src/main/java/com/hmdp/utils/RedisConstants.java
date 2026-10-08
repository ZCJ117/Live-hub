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
    /** 订单明细 Hash —— 与 seckill.lua 的 orderDetailKey 同拼法（本批冻结的契约） */
    public static final String SECKILL_ORDER_DETAIL_KEY = "seckill:order:detail:";
    /** 扣减幂等 Set（SPEC-03 §5.4，键为 orderId） */
    public static final String SECKILL_DEDUCT_KEY = "seckill:deduct:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    public static final String SHOP_LIST_KEY = "shop:list:";

}
