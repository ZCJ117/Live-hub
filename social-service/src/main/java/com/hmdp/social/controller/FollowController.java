package com.hmdp.social.controller;

import com.hmdp.dto.Result;
import com.hmdp.social.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    /**
     * 关注
     * @param followUserId 被关注用户id
     * @return 关注结果
     */
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(followUserId, isFollow);
    }

    /**
     * 判断是否关注
     * @param followUserId 被关注用户id
     * @return 是否关注
     */
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    /**
     * 获取共同关注
     * @param id 被查询用户id
     * @return 共同关注列表
     */
    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") Long id) {
        return followService.followCommons(id);
    }
}
