package com.hmdp.voucher.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.voucher.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.voucher.service.ISeckillVoucherService;
import com.hmdp.voucher.service.IVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_DEDUCT_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    /** 秒杀扣减幂等 Set 的 TTL：只需覆盖 MQ 重试窗口（秒~分钟级），24h 足够且能自清理。 */
    private static final Duration SECKILL_DEDUCT_TTL = Duration.ofHours(24);

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        //保存秒杀库存到redis中
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(),voucher.getStock().toString());

    }

    /**
     * 扣减秒杀券库存（SPEC-03 §5.1/§5.4）
     *
     * <p>唯一 owner 原则：{@code seckill:stock:{voucherId}} 的扣减权只属于 order-service 的
     * seckill.lua（秒杀流量入口在那里）。本方法**只扣 DB**，不再操作该 key——原先的 decrement
     * 与入口的 DECR 叠加成双扣，使 Redis 库存以 2 倍速度消耗（SPEC-03 §1.2）。
     *
     * <p>幂等：以 {@code (voucherId, orderId)} 为键，重复请求（MQ 重试）直接返回成功，
     * 避免"扣库存成功后、插单前崩溃"导致的重试再次扣减 DB（SPEC-03 G4）。
     */
    @Override
    @Transactional
    public Result deductStock(Long voucherId, Long orderId) {
        String deductKey = SECKILL_DEDUCT_KEY + voucherId;
        Long first = stringRedisTemplate.opsForSet().add(deductKey, orderId.toString());
        if (first == null) {
            // Redis 故障（无返回值）：宁可少卖也不超卖，但与"已扣减"区分开，否则该窗口完全不可观测
            log.warn("秒杀扣减幂等标记写入失败(Redis 无返回)，跳过 DB 扣减，stock 可能少扣: voucherId={}, orderId={}",
                    voucherId, orderId);
            return Result.ok();
        }
        if (first == 0L) {
            // 正常 MQ 重投：该订单已扣减过，幂等返回，不必打日志
            return Result.ok();
        }
        // TTL 每次 add 都刷新，而不是只在建 Set 时设一次：券可能连卖数天，若 TTL 锚定在首次创建，
        // 集合会在售卖中途过期，近期订单的重试将再次扣减 DB（重复扣减）。
        stringRedisTemplate.expire(deductKey, SECKILL_DEDUCT_TTL);

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            // 未扣成功，放开幂等标记，避免订单被永久误标为"已扣"
            stringRedisTemplate.opsForSet().remove(deductKey, orderId.toString());
            return Result.fail("库存不足");
        }
        return Result.ok();
    }

    @Override
    public Result getSeckillStock(Long voucherId) {
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            return Result.fail("秒杀券不存在");
        }
        return Result.ok(seckillVoucher.getStock());
    }

    @Override
    @Transactional
    public Result resetSeckillStock(Long voucherId, Integer stock) {
        if (stock == null || stock < 0) {
            return Result.fail("库存值非法");
        }
        if (seckillVoucherService.getById(voucherId) == null) {
            return Result.fail("秒杀券不存在");
        }
        seckillVoucherService.update(Wrappers.<SeckillVoucher>lambdaUpdate()
                .eq(SeckillVoucher::getVoucherId, voucherId)
                .set(SeckillVoucher::getStock, stock));
        log.warn("秒杀券 DB 库存被对账接口绝对回写: voucherId={}, stock={}", voucherId, stock);
        return Result.ok();
    }

    @Override
    public Result listActiveSeckillVoucherIds() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> ids = seckillVoucherService.list(Wrappers.<SeckillVoucher>lambdaQuery()
                        .le(SeckillVoucher::getBeginTime, now)
                        .ge(SeckillVoucher::getEndTime, now))
                .stream()
                .map(SeckillVoucher::getVoucherId)
                .toList();
        return Result.ok(ids);
    }
}