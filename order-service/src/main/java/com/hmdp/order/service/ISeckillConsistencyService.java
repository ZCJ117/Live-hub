package com.hmdp.order.service;

import com.hmdp.dto.Result;

/**
 * 秒杀一致性对账与补偿（SPEC-04）
 *
 * <p><b>权威源约定</b>：
 * <ul>
 *   <li>{@code seckill:stock:{vid}}（Redis）是**可售数**——Lua 入口的唯一扣减方；</li>
 *   <li>{@code tb_seckill_voucher.stock}（DB）是**已落库订单扣除后的余量**；</li>
 *   <li>正常态下 {@code DB库存 = Redis库存 + 在途订单数}，在途 = Redis购买者数 − DB订单数。
 *       把 Redis 无条件拉平到 DB 会凭空多出「在途订单」那么多可售名额（超卖），
 *       故修复口径为 {@code Redis := DB − 在途}。</li>
 * </ul>
 */
public interface ISeckillConsistencyService {

    /**
     * 订单一致性检查（SPEC-04 §5.5 / 验收 A2）
     *
     * @param orderId   订单 ID
     * @param voucherId 订单所属秒杀券 ID；为 null 时自动定位（DB 订单 → 活跃券明细反查）
     * @return 一致时返回比对报告；发现分歧时 fail
     */
    Result checkOrderConsistency(Long orderId, Long voucherId);

    /**
     * 以 Redis 为准把库存回写 DB（SPEC-04 §5.5 / 验收 A4）
     *
     * @return 修复前后值；已一致时不写入
     */
    Result syncStockFromRedisToDb(Long voucherId);

    /**
     * 待处理订单列表（消费失败/死信落地的订单，供人工处置）
     */
    Result checkPendingOrders();

    /**
     * 以 DB 为准修复 Redis 库存（SPEC-04 §5.5 / 验收 A3）
     *
     * @return 修复报告，含 {@code repaired} 与 {@code action}；未发生写入时 {@code repaired=false}
     */
    Result repairInconsistentData(Long voucherId);
}
