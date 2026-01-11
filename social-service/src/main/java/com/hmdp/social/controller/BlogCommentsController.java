package com.hmdp.social.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;
import com.hmdp.social.service.IBlogCommentsService;
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
@RequestMapping("/blog/comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService blogCommentsService;

    /**
     * 保存评论
     * @param comment 评论信息
     * @return 评论结果
     */
    @PostMapping
    public Result saveComment(@RequestBody BlogComments comment) {
        return blogCommentsService.saveComment(comment);
    }

    /**
     * 查询评论列表
     * @param blogId 博客id
     * @param current 当前页
     * @return 评论列表
     */
    @GetMapping("/list")
    public Result queryCommentsByBlogId(
            @RequestParam("blogId") Long blogId,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogCommentsService.queryCommentsByBlogId(blogId, current);
    }
}
