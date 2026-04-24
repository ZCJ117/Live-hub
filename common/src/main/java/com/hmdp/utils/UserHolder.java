package com.hmdp.utils;

import cn.dev33.satoken.stp.StpUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;

/**
 * 当前登录用户持有者
 * 内部委托 Sa-Token 的 StpUtil 获取会话中的用户信息
 */
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    /**
     * 保存用户到当前线程（保留兼容，内部通过 Sa-Token 会话管理）
     */
    public static void saveUser(UserDTO user) {
        tl.set(user);
    }

    /**
     * 从 Sa-Token 会话获取当前登录用户
     */
    public static UserDTO getUser() {
        // 优先从 ThreadLocal 获取（兼容旧流程）
        UserDTO user = tl.get();
        if (user != null) {
            return user;
        }
        // 委托 Sa-Token 会话
        try {
            if (StpUtil.isLogin()) {
                return (UserDTO) StpUtil.getSession().get("user");
            }
        } catch (Exception e) {
            // StpUtil 尚未初始化或不在 Web 上下文中，忽略
        }
        return null;
    }

    /**
     * 移除当前线程中的用户（保留兼容）
     */
    public static void removeUser() {
        tl.remove();
    }
}
