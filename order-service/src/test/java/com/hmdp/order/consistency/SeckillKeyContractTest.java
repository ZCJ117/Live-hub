package com.hmdp.order.consistency;

import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC-04 §8.3 / A5 —— key 契约一致性（防回归）
 *
 * <p>从 {@code seckill.lua} 文本中正则提取全部 {@code 'seckill:...'} 字面量前缀，
 * 断言其与 {@link RedisConstants} 中声明的秒杀 key 前缀集合完全一致。
 *
 * <p><b>当前为红</b>：Lua 使用的 {@code seckill:order:queue} 与
 * {@code SeckillConsistencyServiceImpl} 硬编码的 {@code seckill:order:pending}
 * 都没有在 {@link RedisConstants} 中收口，属 SPEC-04 §1.6「key 无单一事实源」。
 */
class SeckillKeyContractTest {

    /**
     * 匹配 Lua 里任何以 seckill: 开头的单引号字面量。
     * 刻意<b>不</b>要求尾部冒号——{@code 'seckill:order:queue'} 就没有尾冒号，
     * 早先版本因要求尾冒号而漏掉它，导致测试假绿。
     */
    private static final Pattern LUA_KEY = Pattern.compile("'(seckill:[a-z:]+)'");

    @Test
    void lua中的秒杀key必须在RedisConstants中收口() throws IOException {
        Set<String> luaKeys = extractLuaKeys();
        assertTrue(!luaKeys.isEmpty(), "未能从 seckill.lua 提取到任何 key 字面量，正则或资源路径有问题");

        Set<String> declared = declaredSeckillKeys();
        Set<String> missing = new TreeSet<>(luaKeys);
        missing.removeAll(declared);

        assertTrue(missing.isEmpty(),
                "seckill.lua 使用了未在 RedisConstants 中收口的 key " + missing
                        + "；已声明=" + declared + "；Lua 实际=" + luaKeys);
    }

    private Set<String> extractLuaKeys() throws IOException {
        String lua = new String(new ClassPathResource("seckill.lua").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = LUA_KEY.matcher(lua);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    private Set<String> declaredSeckillKeys() {
        Set<String> prefixes = new LinkedHashSet<>();
        for (Field f : RedisConstants.class.getFields()) {
            if (!f.getName().startsWith("SECKILL_") || f.getType() != String.class) {
                continue;
            }
            try {
                prefixes.add((String) f.get(null));
            } catch (IllegalAccessException ignored) {
                // public static final String，不会走到这里
            }
        }
        return prefixes;
    }
}
