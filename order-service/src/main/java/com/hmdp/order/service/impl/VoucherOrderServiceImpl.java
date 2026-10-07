package com.hmdp.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cn.hutool.core.bean.BeanUtil;
import com.hmdp.order.dto.OrderQueryVO;
import com.hmdp.dto.Result;
import com.hmdp.dto.RefundMessages;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.order.feign.VoucherFeignClient;
import com.hmdp.order.mapper.VoucherOrderMapper;
import com.hmdp.order.metrics.SeckillMetrics;
import com.hmdp.order.mq.SeckillOrderProducer;
import com.hmdp.order.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.micrometer.core.instrument.Timer;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Collections;

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
    private RedissonClient redissonClient;

    @Resource
    private SeckillOrderProducer seckillOrderProducer;

    @Resource
    private SeckillMetrics seckillMetrics;

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
            Long userId = UserHolder.getUser().getId();

            long orderId = redisIdWorker.nextId("order");

            Long result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(), userId.toString(), String.valueOf(orderId));

            if (result != 0) {
                seckillMetrics.incrementSeckillFail();
                if (result == 1) {
                    seckillMetrics.incrementStockInsufficient();
                    log.warn("秒杀失败-库存不足: userId={}, voucherId={}", userId, voucherId);
                    return Result.fail("库存不足");
                } else {
                    seckillMetrics.incrementDuplicateOrder();
                    log.warn("秒杀失败-重复下单: userId={}, voucherId={}", userId, voucherId);
                    return Result.fail("不能重复下单");
                }
            }

            SeckillOrderMessage message = new SeckillOrderMessage(orderId, userId, voucherId);

            boolean sendSuccess = seckillOrderProducer.sendSeckillOrderMessageAsync(message);
            if (sendSuccess) {
                seckillMetrics.incrementMqSendSuccess();
            } else {
                seckillMetrics.incrementMqSendFail();
                log.warn("消息发送失败，但Redis已预扣库存，订单将异步处理: orderId={}", orderId);
            }

            seckillMetrics.incrementSeckillSuccess();
            log.info("秒杀资格校验通过，订单异步处理中: orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);

            return Result.ok(orderId);
        } finally {
            seckillMetrics.recordLatency(timerSample);
        }
    }

    /**
     * 创建优惠券订单（供传统同步流程使用）
     * 
     * 注意：此方法在秒杀场景中已由异步流程替代，仅保留用于兼容传统调用。
     * 方法通过分布式事务（Seata）保证数据库操作和库存扣减的一致性。
     * 
     * @param voucherOrder 优惠券订单实体
     */
    @GlobalTransactional(name = "createVoucherOrder", rollbackFor = Exception.class)
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = UserHolder.getUser().getId();

        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("用户已经购买过一次！");
            return;
        }

        Result result = voucherFeignClient.deductStock(voucherOrder.getVoucherId(), voucherOrder.getId());
        if (!result.getSuccess()) {
            log.error("库存不足！");
            return;
        }

        save(voucherOrder);
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

        // 联查券信息，组装 VO（订单字段 + 券标题/金额），一次返回避免 agent 侧二次调用
        List<OrderQueryVO> vos = records.stream().map(order -> {
            OrderQueryVO vo = OrderQueryVO.of(order);
            try {
                Result voucherResult = voucherFeignClient.getVoucherById(order.getVoucherId());
                if (voucherResult.getSuccess() && voucherResult.getData() != null) {
                    Voucher voucher = BeanUtil.mapToBean((Map<?, ?>) voucherResult.getData(), Voucher.class, false, null);
                    vo.setVoucherTitle(voucher.getTitle());
                    vo.setPayValue(voucher.getPayValue());
                    vo.setActualValue(voucher.getActualValue());
                }
            } catch (Exception e) {
                // 券信息联查失败不阻塞订单返回（降级：仅订单字段）
                log.warn("联查券信息失败: voucherId={}", order.getVoucherId(), e);
            }
            return vo;
        }).toList();

        Result r = Result.ok(vos);
        r.setTotal(total);
        return r;
    }

    /**
     * 退款受理（FR-08 第二道闸门，T4.3/T4.4）
     * 双闸门语义：agent confirm 接口为第一道（actionId/归属/时效），本接口独立复核为最终裁决——
     * 原子 UPDATE ... WHERE status=2（已支付未核销）防并发漏单；影响 0 行返回具体原因
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
