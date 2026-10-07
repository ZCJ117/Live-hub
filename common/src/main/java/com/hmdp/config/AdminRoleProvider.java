package com.hmdp.config;

import cn.dev33.satoken.stp.StpInterface;
import org.springframework.beans.factory.annotation.Value;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 最小角色模型（SPEC-06 §5.3）
 *
 * <p>全项目原先只有"登录/未登录"二元状态，故管理接口无法做授权。这里用配置来源
 * 提供 {@code admin} 角色，避免给 {@code tb_user} 加列引入 DDL 变更——若走持久化，
 * 还需额外 SQL 才能产生第一个管理员，否则所有管理接口对所有人 403。
 *
 * <p>注意：本类由 {@link SaTokenConfig} 以 {@code @Bean} 注册，是 {@code StpInterface}
 * 的唯一注册路径。**不得再加类级注解使其成为 Bean**：order-service 与 rag-service 都
 * 扫描 {@code com.hmdp} 全包，一旦被扫到就会与 {@code @Bean} 形成两个同类型 Bean，
 * 任何按类型注入 {@code StpInterface} 的地方都会抛
 * {@code NoUniqueBeanDefinitionException}（Sa-Token 内部按参数名回退才侥幸不炸）。
 */
public class AdminRoleProvider implements StpInterface {

    private static final String ADMIN = "admin";

    private final Set<String> adminUserIds;

    public AdminRoleProvider(@Value("${hmdp.admin-user-ids:}") String adminUserIds) {
        this.adminUserIds = Arrays.stream(adminUserIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        if (loginId != null && adminUserIds.contains(loginId.toString())) {
            return List.of(ADMIN);
        }
        return Collections.emptyList();
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return Collections.emptyList();
    }
}
