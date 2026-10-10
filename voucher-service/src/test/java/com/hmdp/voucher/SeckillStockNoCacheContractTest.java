package com.hmdp.voucher;

import com.hmdp.cache.MultiLevelCache;
import com.hmdp.voucher.service.IVoucherService;
import com.hmdp.voucher.service.VoucherCacheService;
import com.hmdp.voucher.service.impl.VoucherServiceImpl;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 秒杀库存必须直查 DB（SPEC-15 §5.1 风险登记首条 / §5.2 契约测试 / §6 验收 4）
 *
 * <p><b>风险场景</b>：有人"顺手"把 {@code getSeckillStock} 也接上缓存。它是
 * SPEC-04 §5.5 对账的**权威读数**（Redis 库存与 DB 库存的比对基准），一旦被缓存，
 * 对账等式 {@code Redis := DB − 在途} 立刻失去意义，而且表现是"对账永远通过"——
 * 比没有对账更危险。
 *
 * <p>本测试用反射而非源码扫描：注解/字段是编译期事实，不会被格式改动打偏。
 */
class SeckillStockNoCacheContractTest {

    @Test
    void 券服务实现类不得持有任何缓存依赖() {
        for (Field field : VoucherServiceImpl.class.getDeclaredFields()) {
            Class<?> type = field.getType();
            assertFalse(MultiLevelCache.class.isAssignableFrom(type),
                    "VoucherServiceImpl 不得持有 MultiLevelCache 字段：" + field.getName()
                            + "（库存读会因此被缓存，破坏对账等式）");
            assertFalse(VoucherCacheService.class.isAssignableFrom(type),
                    "VoucherServiceImpl 不得注入 VoucherCacheService：" + field.getName());
        }
    }

    @Test
    void getSeckillStock方法上不得出现缓存类注解() throws Exception {
        assertNoCacheAnnotation(VoucherServiceImpl.class);
        // 必须**同时**查接口：Spring 解析缓存注解走 AnnotatedElementUtils 的合并查找，
        // 写在 `IVoucherService#getSeckillStock` 上与写在实现类上同样生效；
        // 而 VoucherServiceImpl.class.getMethod(...).getAnnotations() 只返回该实现类自己的
        // 方法对象，看不到接口声明上的注解 —— 只查实现类会留下一个假绿缺口。
        assertNoCacheAnnotation(IVoucherService.class);
    }

    private static void assertNoCacheAnnotation(Class<?> declaringType) throws Exception {
        Method method = declaringType.getMethod("getSeckillStock", Long.class);
        for (Annotation annotation : method.getAnnotations()) {
            String name = annotation.annotationType().getSimpleName();
            assertFalse(name.contains("Cache"),
                    declaringType.getSimpleName() + "#getSeckillStock 上出现缓存注解 " + name
                            + "：库存读必须直查 DB");
        }
    }
}
