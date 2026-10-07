package com.hmdp.voucher.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.voucher.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.voucher.service.ISeckillVoucherService;
import com.hmdp.voucher.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
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
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

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
        if (first == null || first == 0L) {
            // 该订单已扣减过：幂等返回，不再扣 DB
            return Result.ok();
        }

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
}