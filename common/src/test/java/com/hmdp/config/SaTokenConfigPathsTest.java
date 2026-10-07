package com.hmdp.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 登录白名单口径锁定（SPEC-06 §5.1 / 验收 A10）
 *
 * <p>白名单必须与网关 SaTokenGatewayConfig 的既有白名单严格一致——多一项会放宽公开面，
 * 少一项会把网关已放行的接口在服务侧拒掉。
 */
class SaTokenConfigPathsTest {

    @Test
    void 白名单包含公开接口与内部端点与运维端点() throws Exception {
        Field f = SaTokenConfig.class.getDeclaredField("LOGIN_EXCLUDE_PATHS");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> excludes = (Set<String>) f.get(null);

        assertTrue(excludes.containsAll(Arrays.asList(
                "/user/login", "/user/code", "/actuator/**", "/error", "/internal/**")),
                "白名单缺失：" + excludes);
        // /user/list 需要登录态，绝不能被放行（SPEC-07 §5.1）
        assertFalse(excludes.contains("/user/list"), "批量查用户不得免登录");
        assertFalse(excludes.contains("/**"), "不得整体放行");
    }
}
