package com.hmdp.social.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.social.service.IBlogService;
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
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    /**
     * 保存探店笔记
     * @param blog 探店笔记
     * @return 探店笔记id
     */
    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    /**
     * 点赞
     * @param id 探店笔记id
     * @return 点赞结果
     */
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        return blogService.likeBlog(id);
    }

    /**
     * 取消点赞
     * @param id 探店笔记id
     * @return 取消点赞结果
     */
    @PutMapping("/dislike/{id}")
    public Result dislikeBlog(@PathVariable("id") Long id) {
        return blogService.dislikeBlog(id);
    }

    /**
     * 查询探店笔记详情
     * @param id 探店笔记id
     * @return 探店笔记详情
     */
    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    /**
     * 查询点赞排行榜
     * @param id 探店笔记id
     * @return 点赞排行榜
     */
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    /**
     * 分页查询探店笔记
     * @param current 当前页
     * @param id 作者id
     * @return 探店笔记列表
     */
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        return blogService.queryBlogByUserId(current, id);
    }

    /**
     * 分页查询探店笔记
     * @param current 当前页
     * @return 探店笔记列表
     */
    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    /**
     * 查询关注的人的探店笔记
     * @param max 上一次查询的最大时间
     * @param offset 偏移量
     * @return 探店笔记列表
     */
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId") Long max,
            @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
        return blogService.queryBlogOfFollow(max, offset);
    }
}
