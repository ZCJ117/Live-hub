package com.hmdp.social.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    Result follow(Long followUserId, Boolean isFollow);

    Result isFollow(Long followUserId);

    Result followCommons(Long id);

    /** 查询某作者的全部粉丝 userId（供 Feed 写扩散使用，SPEC-09 §5.4） */
    java.util.List<Long> queryFollowerIds(Long authorId);
}
