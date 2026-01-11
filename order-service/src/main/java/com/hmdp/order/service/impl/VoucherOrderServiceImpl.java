package com.hmdp.order.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.order.feign.VoucherFeignClient;
import com.hmdp.order.mapper.VoucherOrderMapper;
import com.hmdp.order.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.Collections;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
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

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.执行lua脚本，判断当前用户的购买资格
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());
        if (result != 0) {
            //2.不为0说明没有购买资格
            return Result.fail(result==1?"库存不足":"不能重复下单");
        }
        //3.走到这一步说明有购买资格，直接创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);
        //直接调用方法创建订单
        createVoucherOrder(voucherOrder);
        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        // 5.一人一单逻辑
        // 5.1.用户id
        Long userId = UserHolder.getUser().getId();

        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过一次！");
            return ;
        }

        //6.扣减库存 - 调用voucher-service的API
        Result result = voucherFeignClient.deductStock(voucherOrder.getVoucherId());
        if (!result.getSuccess()) {
            //扣减库存失败
            log.error("库存不足！");
            return ;
        }

        save(voucherOrder);
    }
}