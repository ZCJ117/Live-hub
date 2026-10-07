package com.hmdp.user.controller;


import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.UserInfoVO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.user.service.IUserInfoService;
import com.hmdp.user.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;

import java.util.Collections;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    /**
     * 发送手机验证码
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        // 发送短信验证码并保存验证码
        return userService.sendCode(phone,session);
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        // 实现登录功能
        return userService.login(loginForm,session);
    }

    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout() {
        if (!StpUtil.isLogin()) {
            return Result.fail("登出失败，未提供token");
        }
        StpUtil.logout();
        return Result.ok("登出成功");
    }

    @GetMapping("/me")
    public Result me(){
        //获取当前登录的用户并返回
        UserDTO userDTO = UserHolder.getUser();
        return Result.ok(userDTO);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        // 脱敏返回（SPEC-06 §5.4）：不回传 credits/birthday/gender
        return Result.ok(BeanUtil.copyProperties(info, UserInfoVO.class));
    }

    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long userId){
        // 查询详情
        User user = userService.getById(userId);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回
        return Result.ok(userDTO);
    }

    @PostMapping("/sign")
    public Result sign(){
        return userService.sign();
    }

    @GetMapping("/sign/count")
    public Result signCount(){
        return userService.signCount();
    }

    /**
     * 批量查询用户（供 social-service 的点赞排行榜与共同关注，SPEC-07 G1）
     * 脱敏：复用既有 UserDTO 投影，仅返回 id/nickName/icon
     */
    @PostMapping("/list")
    public Result queryUserByIds(@RequestBody List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        if (ids.size() > 100) {
            return Result.fail("批量查询数量不能超过 100");
        }
        return Result.ok(userService.listByIds(ids).stream()
                .map(u -> BeanUtil.copyProperties(u, UserDTO.class))
                .toList());
    }

}