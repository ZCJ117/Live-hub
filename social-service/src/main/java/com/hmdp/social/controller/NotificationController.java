package com.hmdp.social.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.social.service.NotificationService;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 我的站内信（FR-09 T4.7；userId 登录态强制注入，网关 Sa-Token 拦截未登录） */
@RestController
@RequestMapping("/notification")
public class NotificationController {

    @Resource
    private NotificationService notificationService;

    @GetMapping("my")
    public Result my() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录，请先登录");
        }
        return Result.ok(notificationService.listByUser(user.getId()));
    }
}
