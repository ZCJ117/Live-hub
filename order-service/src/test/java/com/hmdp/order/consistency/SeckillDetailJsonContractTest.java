package com.hmdp.order.consistency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 明细 Hash 值契约（SPEC-14 §7 M6）
 *
 * <p>在途补偿器（{@code SeckillInFlightCompensator}）按 {@code ts} 判龄、按 {@code retryCount}
 * 决定是重投还是释放。这两个字段由 Lua 与 DLQ 消费者**共同**写入，任一侧漏写都会让补偿器
 * 静默跳过该条目（ts 缺失 → 不判龄），泄漏因此不可见。故用脚本文本断言把契约钉住。
 *
 * <p><b>断言只作用于 {@code cjson.encode} 的表字面量区域</b>：早先版本用整脚本
 * {@code lua.contains(..)}，结果连头部注释都能满足断言（注释里就写着 {@code ARGV[4]} 与
 * {@code retryCount}），字段改名、{@code ts} 被 {@code tonumber} 也照样绿。现在先截出
 * encode 块再匹配，注释与无关代码无法参与。
 */
class SeckillDetailJsonContractTest {

    /** encode 块：{@code cjson.encode({} 到行首的 })} 之间。贪婪 .*? 配合行首 } 定位收尾。 */
    private static final Pattern ENCODE_TABLE =
            Pattern.compile("cjson\\.encode\\(\\{(.*?)\\n\\}\\)", Pattern.DOTALL);

    /** 表字面量里的裸 key：行首缩进的 {@code name =}（Lua 表构造器的 name = value 形式）。 */
    private static final Pattern TABLE_KEY =
            Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9]*)\\s*=", Pattern.MULTILINE);

    private String lua;

    @BeforeEach
    void loadLua() throws IOException {
        lua = new String(new ClassPathResource("seckill.lua").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    @Test
    void 明细JSON必须恰好包含五个字段() {
        Set<String> keys = new TreeSet<>();
        Matcher m = TABLE_KEY.matcher(encodeTable());
        while (m.find()) {
            keys.add(m.group(1));
        }
        assertEquals(new TreeSet<>(Set.of("voucherId", "userId", "orderId", "ts", "retryCount")), keys,
                "明细 JSON 的字段集即 Task 6/Task 7 的读取契约，多一个少一个都会让补偿器静默跳过或误判");
    }

    @Test
    void ts必须以字符串形态写入_不得tonumber() {
        String table = encodeTable();
        assertTrue(table.matches("(?s).*\\bts\\s*=\\s*ts\\b.*"),
                "ts 必须由 ARGV[4] 原样写入 encode 表（在途补偿器据此判龄），实际表字面量=" + table);
        assertFalse(table.contains("tonumber"),
                "ts 一旦转成数字，JSON 里就是 number 而非 string，Task 6 的解析契约随之改变"
                        + "（当前实现按字符串解析），实际表字面量=" + table);
    }

    @Test
    void retryCount初值必须为字面量0() {
        assertTrue(encodeTable().matches("(?s).*\\bretryCount\\s*=\\s*0\\s*,?.*"),
                "脚本只写初值 0；递增由补偿器与 DLQ 消费者负责，脚本一旦自增就破坏了该分工，"
                        + "实际表字面量=" + encodeTable());
    }

    /**
     * 截取 {@code cjson.encode({...})} 的表字面量——注释与其它代码不参与断言。
     *
     * <p>{@code ts} 由 Java 侧传入的 {@code ARGV[4]} 覆盖，已有
     * {@code SeckillVoucherServiceTest#调用脚本时传入写入时刻作为ARGV4} 经 mock 捕获实参断言，
     * 故此处不重复断言 {@code ARGV[4]}。
     */
    private String encodeTable() {
        Matcher m = ENCODE_TABLE.matcher(lua);
        assertTrue(m.find(), "未能从 seckill.lua 定位 cjson.encode 的表字面量：脚本结构或正则已失配");
        return m.group(1);
    }
}
