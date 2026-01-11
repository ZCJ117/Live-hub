package com.hmdp.social.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;
import com.hmdp.social.mapper.BlogCommentsMapper;
import com.hmdp.social.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

    @Override
    public Result saveComment(BlogComments comment) {
        // 保存评论
        boolean isSuccess = save(comment);
        if (!isSuccess) {
            return Result.fail("保存评论失败");
        }
        return Result.ok();
    }

    @Override
    public Result queryCommentsByBlogId(Long blogId, Integer current) {
        // 查询评论列表
        return Result.ok(query().eq("blog_id", blogId).page(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, 10)));
    }
}
