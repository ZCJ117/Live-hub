package com.hmdp.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdminRoleProviderTest {

    @Test
    void 配置内的用户id获得admin角色() {
        AdminRoleProvider provider = new AdminRoleProvider("1, 42");
        assertEquals(java.util.List.of("admin"), provider.getRoleList(1L, "login"));
        assertEquals(java.util.List.of("admin"), provider.getRoleList("42", "login"));
    }

    @Test
    void 配置外的用户无角色() {
        AdminRoleProvider provider = new AdminRoleProvider("1,42");
        assertTrue(provider.getRoleList(99L, "login").isEmpty());
    }

    @Test
    void 未配置时不授予任何角色_安全默认() {
        AdminRoleProvider provider = new AdminRoleProvider("");
        assertTrue(provider.getRoleList(1L, "login").isEmpty());
        assertTrue(provider.getRoleList(null, "login").isEmpty());
    }
}
