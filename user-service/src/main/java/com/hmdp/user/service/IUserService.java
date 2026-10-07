package com.hmdp.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import jakarta.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    /**
     * 发送手机验证码（SPEC-06 §5.5 增加频控）
     *
     * @param clientIp 调用方 IP，用于每 IP 24 小时上限
     */
    Result sendCode(String phone, HttpSession session, String clientIp);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result sign();

    Result signCount();
}