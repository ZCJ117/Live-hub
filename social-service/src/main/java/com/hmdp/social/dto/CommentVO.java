package com.hmdp.social.dto;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.BlogComments;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评论视图对象：评论本体 + 评论人昵称/头像（SPEC-09 §5.1 的联查结果）。
 */
@Data
public class CommentVO {

    private Long id;
    private Long userId;
    private Long blogId;
    private Long parentId;
    private Long answerId;
    private String content;
    private Integer liked;
    private LocalDateTime createTime;
    /** 评论人昵称；用户服务不可用时为 null */
    private String nickName;
    /** 评论人头像；用户服务不可用时为 null */
    private String icon;

    /**
     * @param user 评论人信息，可为 {@code null}（联查降级时不填昵称/头像）
     */
    public static CommentVO of(BlogComments comment, UserDTO user) {
        CommentVO vo = new CommentVO();
        vo.setId(comment.getId());
        vo.setUserId(comment.getUserId());
        vo.setBlogId(comment.getBlogId());
        vo.setParentId(comment.getParentId());
        vo.setAnswerId(comment.getAnswerId());
        vo.setContent(comment.getContent());
        vo.setLiked(comment.getLiked());
        vo.setCreateTime(comment.getCreateTime());
        if (user != null) {
            vo.setNickName(user.getNickName());
            vo.setIcon(user.getIcon());
        }
        return vo;
    }
}
