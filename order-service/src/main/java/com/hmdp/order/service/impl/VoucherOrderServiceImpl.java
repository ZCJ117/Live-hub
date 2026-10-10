package com.hmdp.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cn.hutool.core.bean.BeanUtil;
import com.hmdp.order.dto.OrderQueryVO;
import com.hmdp.dto.Result;
import com.hmdp.dto.RefundMessages;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.order.entity.SeckillOutbox;
import com.hmdp.order.feign.VoucherFeignClient;
import com.hmdp.order.mapper.SeckillOutboxMapper;
import com.hmdp.order.mapper.VoucherOrderMapper;
import com.hmdp.order.metrics.SeckillMetrics;
import com.hmdp.order.mq.SeckillOrderProducer;
import com.hmdp.order.service.IVoucherOrderService;
import com.hmdp.order.service.SeckillFailMessages;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 优惠券订单服务实现类
 * 
 * 负责处理优惠券秒杀的核心业务逻辑，包括：
 * 1. 秒杀资格校验（通过Lua脚本保证原子性）
 * 2. 异步订单处理（通过消息队列削峰）
 * 3. 秒杀指标监控（记录成功/失败等关键指标）
 * 
 * 采用"Redis预扣库存 + MQ异步下单"的架构，保证高并发下的系统稳定性和数据最终一致性。
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private VoucherFeignClient voucherFeignClient;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SeckillOrderProducer seckillOrderProducer;

    @Resource
    private SeckillMetrics seckillMetrics;

    @Resource
    private SeckillOutboxMapper seckillOutboxMapper;

    /** 本地事件表状态：待投递 */
    static final int OUTBOX_STATUS_PENDING = 0;
    /** 本地事件表状态：已投递（MQ 已确认） */
    static final int OUTBOX_STATUS_DELIVERED = 1;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 秒杀优惠券入口方法
     * 
     * 处理流程：
     * 1. 生成唯一订单ID（使用Redis ID生成器）
     * 2. 执行Lua脚本进行原子性库存预扣减和一人一单校验
     * 3. 根据脚本返回结果处理成功/失败逻辑
     * 4. 发送MQ消息异步创建订单（实现流量削峰）
     * 5. 记录秒杀相关指标（成功率、延迟等）
     * 
     * @param voucherId 优惠券ID
     * @return Result 包含订单ID（成功）或错误信息（失败）
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Timer.Sample timerSample = seckillMetrics.startTimer();
        seckillMetrics.incrementSeckillRequest();

        try {
            UserDTO user = UserHolder.getUser();
            if (user == null) {
                seckillMetrics.incrementSeckillFail();
                return Result.fail("未登录，请先登录");
            }
            Long userId = user.getId();

            // 活动时间窗前置判定（SPEC-14 P0-3）：放在 Lua 之前而非下沉脚本——
            // 时间窗不存在并发正确性问题（边界上的秒级误差不影响业务），
            // 真正的并发闸门仍是下面那次单 EVAL。下沉脚本会改动返回值契约，不值当。
            Result windowRejection = checkSeckillWindow(voucherId);
            if (windowRejection != null) {
                seckillMetrics.incrementSeckillFail();
                return windowRejection;
            }

            long orderId = redisIdWorker.nextId("order");

            Long scriptResult = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(), userId.toString(), String.valueOf(orderId),
                    // ARGV[4]：写入时刻，由在途补偿器用于判定在途超时（SPEC-14 P0-2 / §7 M6）
                    String.valueOf(nowMillis()));

            // 脚本返回 nil（Redis 异常）按 key 缺失处理，避免拆箱 NPE
            long result = scriptResult == null ? 3L : scriptResult;

            if (result != 0) {
                seckillMetrics.incrementSeckillFail();
                if (result == 1) {
                    seckillMetrics.incrementStockInsufficient();
                    log.warn("秒杀失败-库存不足: userId={}, voucherId={}", userId, voucherId);
                    return Result.fail(SeckillFailMessages.STOCK_INSUFFICIENT);
                } else if (result == 2) {
                    seckillMetrics.incrementDuplicateOrder();
                    log.warn("秒杀失败-重复下单: userId={}, voucherId={}", userId, voucherId);
                    return Result.fail(SeckillFailMessages.DUPLICATE_ORDER);
                } else {
                    // result == 3：seckill:stock:{voucherId} 不存在，需预热（运维态）。
                    // 文案必须与下文的「MQ 发送失败」区分（SPEC-03 A8）：两者都回"系统繁忙"时，
                    // 调用方无法判断该去预热库存还是去查 broker，处置动作完全不同
                    seckillMetrics.incrementRedisStockMissing();
                    log.error("秒杀失败-Redis库存key缺失，需预热: voucherId={}", voucherId);
                    return Result.fail(SeckillFailMessages.STOCK_KEY_MISSING);
                }
            }

            // 本地事件表（SPEC-15 P2-1 方案 a / 形态 D1-b）：先落库、再投递。
            //
            // 【为什么这样就够】落库成功即代表"这条订单一定会被投递"：即使本进程在
            // 紧接着的一行崩溃，SeckillOutboxDeliverer 也会扫到 status=0 的行补投。
            // 丢失窗口 = 0，不再依赖 P0-2 的 T+120s 事后补偿。
            //
            // 【诚实说明】本项目生产端此前没有任何 DB 写入（库存在 Redis 扣、订单由消费者落库），
            // 因此"投递与本地状态同事务"在这里**等价于**"INSERT 自身提交后再投递"——
            // 没有第二个 DB 写入可以与之原子化。不要把它理解成两阶段提交。
            // 也正因为如此，本方法**没有**加 @Transactional：那只会把 Redis 预扣和
            // MQ 同步发送（网络调用）一起圈进一个 DB 事务，长事务持有连接且毫无收益。
            SeckillOutbox outbox = new SeckillOutbox()
                    .setId(orderId)
                    .setUserId(userId)
                    .setVoucherId(voucherId)
                    .setStatus(OUTBOX_STATUS_PENDING)
                    .setRetryCount(0);
            try {
                seckillOutboxMapper.insert(outbox);
            } catch (Exception e) {
                // 落库失败 = 无法对投递做持久承诺，必须回滚预扣并明确返回失败
                rollbackSeckillReservation(voucherId, userId, orderId);
                seckillMetrics.incrementSeckillFail();
                log.error("秒杀事件行落库失败，已回滚Redis预扣: orderId={}, userId={}, voucherId={}",
                        orderId, userId, voucherId, e);
                return Result.fail(SeckillFailMessages.MQ_SEND_FAILED);
            }

            SeckillOrderMessage message = new SeckillOrderMessage(orderId, userId, voucherId);

            // 同步发送（SPEC-03 §5.2 方案 A）：asyncSend 的返回值只代表"提交成功"，
            // 真正的失败被吞在回调里，用户会拿到一个永不兑现的 orderId
            if (!seckillOrderProducer.sendSeckillOrderMessage(message)) {
                // 【顺序即正确性】必须先删事件行、再回滚预扣。理由见本段上方注释与
                // SeckillVoucherServiceTest#投递失败时先删事件行再回滚预扣 的论证：
                // 反过来会在"回滚完成但行未删"的崩溃点上留下一条待投递记录，
                // 补投出去会在已释放的预扣上重新建单 —— 超卖方向。
                if (deleteOutboxRow(orderId)) {
                    rollbackSeckillReservation(voucherId, userId, orderId);
                } else {
                    // 行没删掉 → 该单仍会被补投器投递 → 预扣**必须保留**。
                    // 若此处照常回滚（INCR 库存 + 移出用户 + 删明细），补投出去的消息会在
                    // 一份已释放的预扣上重新建单：Redis 库存比 DB 多 1，即超卖方向。
                    // 这是"先删后回滚"想防的同一类风险，只是触发方式从进程崩溃换成了删除抛异常。
                    // 取舍：宁可让用户先看到一次失败、稍后真的拿到订单（延迟/少卖），也不能超卖。
                    log.error("[需人工核对] 事件行删除失败，已保留预扣不回滚: orderId={}, userId={}, voucherId={}",
                            orderId, userId, voucherId);
                }
                seckillMetrics.incrementMqSendFail();
                seckillMetrics.incrementSeckillFail();
                log.error("秒杀订单消息发送失败: orderId={}, userId={}, voucherId={}",
                        orderId, userId, voucherId);
                return Result.fail(SeckillFailMessages.MQ_SEND_FAILED);
            }

            // 标记已投递。失败只记 warn、不影响返回：补投器下一轮会再投一次，
            // 消费端以 orderId 为主键幂等，重复投递不会重复建单。
            markOutboxDelivered(orderId);

            seckillMetrics.incrementMqSendSuccess();
            seckillMetrics.incrementSeckillSuccess();
            log.info("秒杀资格校验通过，订单异步处理中: orderId={}, userId={}, voucherId={}",
                    orderId, userId, voucherId);
            return Result.ok(orderId);
        } finally {
            seckillMetrics.recordLatency(timerSample);
        }
    }

    /**
     * 当前时刻（毫秒）。抽成可覆写方法，是为了让时间窗边界用例可确定复现——
     * 直接读挂钟会让 {@code =begin} / {@code =end} / {@code begin-1ms} / {@code end+1ms}
     * 这四个 SPEC-14 §5.2 明确要求的边界用例受 JIT/GC 抖动影响（实测最坏 25ms），
     * 从而无法稳定断言。
     */
    long nowMillis() {
        return System.currentTimeMillis();
    }

    /**
     * 活动时间窗判定（SPEC-14 P0-3 / §7 M2）。
     *
     * <p><b>口径与对账侧严格一致</b>：活跃 ⇔ {@code begin <= now <= end}（闭区间），
     * 即 {@code VoucherServiceImpl.listActiveSeckillVoucherIds()} 的
     * {@code le(beginTime)} + {@code ge(endTime)}。入口只拒绝 {@code now < begin} 与 {@code now > end}。
     *
     * <p><b>key 缺失/字段不可解析/Redis 异常一律放行</b>：向后兼容上线前创建的存量券，
     * 避免它们被误拒；且时间窗只是边界校验，不应因 Redis 抖动阻断秒杀主链路。
     *
     * @return {@code null} 表示放行；否则为拒绝结果
     */
    private Result checkSeckillWindow(Long voucherId) {
        List<Object> window;
        try {
            window = stringRedisTemplate.opsForHash().multiGet(
                    RedisConstants.windowKey(voucherId),
                    List.of(RedisConstants.SECKILL_WINDOW_FIELD_BEGIN,
                            RedisConstants.SECKILL_WINDOW_FIELD_END));
        } catch (Exception e) {
            log.warn("读取秒杀活动时间窗失败，放行: voucherId={}", voucherId, e);
            return null;
        }
        if (window == null || window.size() < 2) {
            return null;
        }

        Long beginMs = parseEpochMillis(window.get(0));
        Long endMs = parseEpochMillis(window.get(1));
        if (beginMs == null || endMs == null) {
            log.warn("秒杀活动时间窗格式非法，放行: voucherId={}, raw={}", voucherId, window);
            return null;
        }

        long now = nowMillis();
        if (now < beginMs) {
            seckillMetrics.incrementSeckillNotStarted();
            log.warn("秒杀失败-活动未开始: voucherId={}, begin={}, now={}", voucherId, beginMs, now);
            return Result.fail(SeckillFailMessages.SECKILL_NOT_STARTED);
        }
        if (now > endMs) {
            seckillMetrics.incrementSeckillEnded();
            log.warn("秒杀失败-活动已结束: voucherId={}, end={}, now={}", voucherId, endMs, now);
            return Result.fail(SeckillFailMessages.SECKILL_ENDED);
        }
        return null;
    }

    /**
     * 解析时间窗字段为 epoch millis。
     *
     * <p>null / 非数字一律返回 null，由调用方按「无窗口」放行。
     *
     * <p>刻意**不**先把值转成字符串再 {@code Long.parseLong}：{@code String.valueOf((Object) null)}
     * 会得到字面量 "null"，再靠 parseLong 抛 NumberFormatException 兜底。那样写会让上层的
     * null 判断看起来在保护什么、实则不参与判定（SPEC-14 P0-3 评审发现）。
     */
    private Long parseEpochMillis(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 入口侧回滚（SPEC-03 §5.6）：库存恢复 + 移除用户标记 + 删除订单明细。
     * 三者同源，必须一起回滚——任一遗漏都会让用户被永久标记"已购买"或库存凭空少 1。
     */
    private void rollbackSeckillReservation(Long voucherId, Long userId, Long orderId) {
        try {
            stringRedisTemplate.opsForValue().increment(RedisConstants.SECKILL_STOCK_KEY + voucherId);
            stringRedisTemplate.opsForSet().remove(
                    RedisConstants.SECKILL_ORDER_SET_KEY + voucherId, userId.toString());
            stringRedisTemplate.opsForHash().delete(
                    RedisConstants.SECKILL_ORDER_DETAIL_KEY + voucherId, orderId.toString());
        } catch (Exception e) {
            // 回滚失败无法在同一请求内自愈，记录待人工核对（补偿能力归 SPEC-04）
            log.error("回滚秒杀预扣失败，需人工核对: voucherId={}, userId={}, orderId={}",
                    voucherId, userId, orderId, e);
        }
    }

    /**
     * 标记事件行已投递。
     *
     * <p>条件更新（{@code status = 待投递}）：补投器可能已经抢先投递并置位，
     * 无条件的 UPDATE 会把它的结果覆盖掉，语义上没错但会掩盖真实投递方。
     */
    private void markOutboxDelivered(Long orderId) {
        try {
            seckillOutboxMapper.update(null, Wrappers.<SeckillOutbox>lambdaUpdate()
                    .eq(SeckillOutbox::getId, orderId)
                    .eq(SeckillOutbox::getStatus, OUTBOX_STATUS_PENDING)
                    .set(SeckillOutbox::getStatus, OUTBOX_STATUS_DELIVERED));
        } catch (Exception e) {
            log.warn("秒杀事件行标记已投递失败，补投器会重投（消费端幂等）: orderId={}", orderId, e);
        }
    }

    /**
     * 删除事件行。
     *
     * <p>刻意**不**检查 {@code deleteById} 的返回值：返回 0 只代表该行本就不存在
     * （没有可投递的记录），与"删除成功"在业务上等价，都允许回滚预扣。
     *
     * @return true = 行已不存在（可以安全回滚预扣）；false = 删除抛异常
     *         （调用方**不得**回滚预扣，否则补投出去就是超卖）
     */
    private boolean deleteOutboxRow(Long orderId) {
        try {
            seckillOutboxMapper.deleteById(orderId);
            return true;
        } catch (Exception e) {
            log.error("秒杀事件行删除失败: orderId={}", orderId, e);
            return false;
        }
    }

    /**
     * 按 userId 批量查询订单（agent-service 客服工具调用）
     * 归属校验：userId 由登录态强制注入；指定 orderId 非本人时返回空列表（不泄露订单存在性，PRD FR-05 验收 2）
     */
    @Override
    public Result queryMyOrders(Long userId, Long orderId, Integer status, Integer days, Integer page, Integer size) {
        int p = Math.max(page == null ? 1 : page, 1);
        int s = Math.min(Math.max(size == null ? 5 : size, 1), 20);

        LambdaQueryWrapper<VoucherOrder> wrapper = Wrappers.<VoucherOrder>lambdaQuery()
                .eq(VoucherOrder::getUserId, userId)
                .orderByDesc(VoucherOrder::getCreateTime);
        if (orderId != null) {
            wrapper.eq(VoucherOrder::getId, orderId);
        }
        if (status != null) {
            wrapper.eq(VoucherOrder::getStatus, status);
        }
        if (days != null && days > 0) {
            wrapper.ge(VoucherOrder::getCreateTime, LocalDateTime.now().minusDays(days));
        }

        // 项目未配置 MP 分页插件，手动分页（count + limit/offset）
        long total = count(wrapper.clone());
        wrapper.last("LIMIT " + s + " OFFSET " + (long) (p - 1) * s);
        List<VoucherOrder> records = list(wrapper);

        // 联查券信息，组装 VO：先按 voucherId 去重后**一次**批量拉取，避免逐条远程调用（N+1）
        List<OrderQueryVO> vos = new ArrayList<>();
        if (!records.isEmpty()) {
            List<Long> voucherIds = records.stream()
                    .map(VoucherOrder::getVoucherId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();

            Map<Long, Voucher> voucherMap = new HashMap<>();
            try {
                Result voucherResult = voucherFeignClient.getVouchersByIds(voucherIds);
                if (voucherResult.getSuccess() && voucherResult.getData() instanceof List<?> list) {
                    for (Object item : list) {
                        Voucher v = BeanUtil.mapToBean((Map<?, ?>) item, Voucher.class, false, null);
                        voucherMap.put(v.getId(), v);
                    }
                }
            } catch (Exception e) {
                // 券信息联查失败不阻塞订单返回（降级：仅订单字段）
                log.warn("批量联查券信息失败: voucherIds={}", voucherIds, e);
            }

            for (VoucherOrder order : records) {
                OrderQueryVO vo = OrderQueryVO.of(order);
                Voucher voucher = voucherMap.get(order.getVoucherId());
                if (voucher != null) {
                    vo.setVoucherTitle(voucher.getTitle());
                    vo.setPayValue(voucher.getPayValue());
                    vo.setActualValue(voucher.getActualValue());
                }
                vos.add(vo);
            }
        }

        Result r = Result.ok(vos);
        r.setTotal(total);
        return r;
    }

    /**
     * 退款受理（FR-08 第二道闸门，T4.3/T4.4）
     * 双闸门语义：agent confirm 接口为第一道（actionId/归属/时效），本接口独立复核为最终裁决——
     * 原子 UPDATE ... WHERE status=2（已支付未核销）防并发漏单；影响 0 行返回具体原因。
     *
     * <p><b>状态口径（SPEC-09 §5.3 方案 B）</b>：退款是**受理登记**，线上状态推进到
     * {@code 5-退款受理} 即为**终态**；资金退还原路为线下流转，不含线上资金/库存回滚。
     * 故此处不再有 5 → 6 的推进逻辑，状态定义中也不再有 6（原定义的 6 从未被任何代码写入）。
     */
    @Override
    public Result refund(Long userId, Long orderId, String reason) {
        VoucherOrder exists = getById(orderId);
        if (exists == null || !userId.equals(exists.getUserId())) {
            // 归属不符：与"不存在"同文案，不泄露订单存在性（FR-05 越权口径）
            return Result.fail("订单不存在");
        }
        boolean updated = update(Wrappers.<VoucherOrder>lambdaUpdate()
                .eq(VoucherOrder::getId, orderId)
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getStatus, 2)
                .set(VoucherOrder::getStatus, 5)
                .set(VoucherOrder::getRefundTime, LocalDateTime.now()));
        if (!updated) {
            VoucherOrder cur = getById(orderId);
            if (cur != null && cur.getStatus() != null && cur.getStatus() == 5) {
                return Result.fail(RefundMessages.ALREADY_PENDING);
            }
            return Result.fail("订单状态已变更，请刷新后查看");
        }
        log.info("退款受理成功: orderId={}, userId={}, reason={}", orderId, userId, reason);
        return Result.ok(Map.of("orderId", orderId, "status", 5));
    }
}
