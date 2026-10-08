package com.hmdp.social.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.social.feign.UserFeignClient;
import com.hmdp.social.mapper.FollowMapper;
import com.hmdp.social.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserFeignClient userFeignClient;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录");
        }
        Long userId = user.getId();
        String key = "follows:" + userId;
        if (isFollow) {
            //2.关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                //把关注的用户id 放入redis的set集合 sadd userId followerUserId
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            //3.取关。MyBatis-Plus 的 remove 返回的是"语句是否执行成功"而非"是否删除了行"，
            //  未关注状态下同样返回 true，故必须先查存在性再删（SPEC-09 §1.11）
            Long exists = count(Wrappers.<Follow>lambdaQuery()
                    .eq(Follow::getUserId, userId)
                    .eq(Follow::getFollowUserId, followUserId));
            if (exists != null && exists > 0) {
                remove(Wrappers.<Follow>lambdaQuery()
                        .eq(Follow::getUserId, userId)
                        .eq(Follow::getFollowUserId, followUserId));
            }
            //4.无论 DB 侧是否有行，都清理 Redis，使两侧收敛到"未关注"
            stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录");
        }
        Long userId = user.getId();
        //2.查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Long count = count(Wrappers.<Follow>lambdaQuery()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFollowUserId, followUserId));
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        // 1.获取当前用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录");
        }
        Long userId = user.getId();
        String key = "follows:" + userId;
        // 2.求交集
        String key2 = "follows:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()) {
            // 无交集
            return Result.ok(Collections.emptyList());
        }
        // 3.解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 4.查询用户
        Result result = userFeignClient.getUserByIds(ids);
        if (result == null || !Boolean.TRUE.equals(result.getSuccess())) {
            return Result.fail("获取用户信息失败");
        }
        List<UserDTO> userDTOS = (List<UserDTO>) result.getData();
        return Result.ok(userDTOS);
    }

    @Override
    public List<Long> queryFollowerIds(Long authorId) {
        return list(Wrappers.<Follow>lambdaQuery().eq(Follow::getFollowUserId, authorId))
                .stream()
                .map(Follow::getUserId)
                .collect(Collectors.toList());
    }
}
