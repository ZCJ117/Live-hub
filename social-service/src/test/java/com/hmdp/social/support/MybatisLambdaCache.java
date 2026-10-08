package com.hmdp.social.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 纯单测环境下的 MyBatis-Plus 实体元数据预热。
 *
 * <p>lambda wrapper（{@code Wrappers.<Blog>lambdaQuery().eq(Blog::getId, x)}）的列名解析依赖
 * {@code TableInfoHelper} 的缓存；该缓存平时由 MyBatis 扫描实体时填充。不启 Spring/MyBatis
 * 上下文的单测必须自己预热，否则构造 wrapper 时抛
 * {@code can not find lambda cache for this entity}。
 *
 * <p>仅测试作用域，不参与生产代码。
 */
public final class MybatisLambdaCache {

    private MybatisLambdaCache() {
    }

    /** 预热给定实体的元数据；幂等，重复调用无害。 */
    public static void prewarm(Class<?>... entities) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : entities) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }
}
