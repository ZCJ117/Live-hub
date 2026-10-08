package com.hmdp.social.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComments;
import com.hmdp.social.dto.CommentVO;
import com.hmdp.social.feign.UserFeignClient;
import com.hmdp.social.mapper.BlogCommentsMapper;
import com.hmdp.social.service.IBlogCommentsService;
import com.hmdp.social.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** 评论内容长度上限（SPEC-09 §5.1） */
    private static final int MAX_COMMENT_LENGTH = 500;

    @Resource
    private IBlogService blogService;

    @Resource
    private UserFeignClient userFeignClient;

    /**
     * 保存评论（SPEC-09 G1）：
     * 登录态注入 userId、入参校验、主键清空，并在同一事务内维护 tb_blog.comments 计数。
     */
    @Override
    @Transactional
    public Result saveComment(BlogComments comment) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录");
        }
        if (StrUtil.isBlank(comment.getContent())) {
            return Result.fail("评论内容不能为空");
        }
        if (comment.getContent().length() > MAX_COMMENT_LENGTH) {
            return Result.fail("评论内容过长");
        }
        if (comment.getBlogId() == null) {
            return Result.fail("blogId 不能为空");
        }

        // 防前端指定主键、防伪造他人身份
        comment.setId(null);
        comment.setUserId(user.getId());
        comment.setStatus(Boolean.FALSE); // 0-正常
        comment.setLiked(0);
        if (comment.getParentId() == null) {
            comment.setParentId(0L);
        }

        if (!save(comment)) {
            return Result.fail("保存评论失败");
        }

        // 维护 tb_blog.comments 计数（A3）。与上面的 insert 同事务，避免评论落库而计数丢失。
        blogService.update(Wrappers.<Blog>lambdaUpdate()
                .setSql("comments = comments + 1")
                .eq(Blog::getId, comment.getBlogId()));

        return Result.ok();
    }

    /**
     * 查询评论列表（SPEC-09 A2）：
     * 过滤 status = 0（屏蔽被举报/禁止查看），手工分页，联查评论人昵称/头像。
     */
    @Override
    public Result queryCommentsByBlogId(Long blogId, Integer current) {
        int page = Math.max(current == null ? 1 : current, 1);

        var wrapper = Wrappers.<BlogComments>lambdaQuery()
                .eq(BlogComments::getBlogId, blogId)
                .eq(BlogComments::getStatus, Boolean.FALSE)
                .orderByDesc(BlogComments::getCreateTime);

        // 本模块未配置 MyBatis-Plus 分页插件，selectPage 不会真正分页，
        // 故按 order-service queryMyOrders 的既有范式手工分页（count + LIMIT/OFFSET）
        long total = count(wrapper.clone());
        long size = SystemConstants.DEFAULT_PAGE_SIZE;
        wrapper.last("LIMIT " + size + " OFFSET " + (page - 1) * size);
        List<BlogComments> records = list(wrapper);

        Map<Long, UserDTO> users = queryCommentUsers(records);
        List<CommentVO> vos = new ArrayList<>(records.size());
        for (BlogComments record : records) {
            vos.add(CommentVO.of(record, users.get(record.getUserId())));
        }

        Result result = Result.ok(vos);
        result.setTotal(total);
        return result;
    }

    /**
     * 批量取评论人信息。**一次**远程调用，避免逐条 N+1（SPEC-07 §1.5 的教训）；
     * 失败时降级为不填昵称头像，不阻塞评论列表返回。
     */
    private Map<Long, UserDTO> queryCommentUsers(List<BlogComments> records) {
        List<Long> ids = records.stream()
                .map(BlogComments::getUserId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, UserDTO> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        try {
            Result result = userFeignClient.getUserByIds(ids);
            if (result == null || !Boolean.TRUE.equals(result.getSuccess()) || !(result.getData() instanceof List<?> list)) {
                return map;
            }
            for (Object item : list) {
                // Result.data 的泛型信息在跨服务边界丢失，元素是 Map，必须转换而非强转
                UserDTO user = BeanUtil.mapToBean((Map<?, ?>) item, UserDTO.class, false, null);
                if (user.getId() != null) {
                    map.put(user.getId(), user);
                }
            }
        } catch (Exception e) {
            // 注意：ServiceImpl 的 log 是 org.apache.ibatis.logging.Log，只有 warn(String)，
            // **不支持 SLF4J 的 {} 可变参数**，必须字符串拼接
            log.warn("批量联查评论人信息失败: userIds=" + ids + ", cause=" + e);
        }
        return map;
    }
}
