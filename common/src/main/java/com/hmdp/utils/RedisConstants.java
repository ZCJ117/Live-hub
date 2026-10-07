package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

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

    // JetCache 配置（可根据需要调整）
    public static final Integer JETCACHE_LOCAL_LIMIT = 100;
    public static final Long JETCACHE_EXPIRE = 7200L; // 2小时
    public static final Long JETCACHE_REFRESH = 1800L; // 30分钟自动刷新

}
