package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
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
 *  服务实现类
 * </p>
 *
 * @author 左常健
 * @since 2025-12-5
 */
// NOTE 关注服务的实现类，处理用户关注和取关的业务逻辑
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private final StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    //NOTE 构造函数注入 StringRedisTemplate
    public FollowServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    //NOTE 处理用户关注和取关的业务逻辑
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        if(isFollow){
            //2.关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
             boolean isSuccess = save(follow);
             if(isSuccess){
                 // NOTE 关注成功后，将关注的用户ID存入Redis的Set集合中，便于后续快速查询和交集计算
                 //把关注的用户id 放入redis的set集合 sadd userId followerUserId
                 stringRedisTemplate.opsForSet().add(key,followUserId.toString());
             }
        }else {
            //3.取关，删除
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id",userId).eq("follow_user_id",followUserId));
            if(isSuccess){
                //把关注用户的id从redis集合中移除
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }

        }
        return Result.ok();
    }

    //NOTE 检查当前用户是否关注了指定用户
    @Override
    public Result isFollow(Long followUserId) {
        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        //2.查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Long count = query().eq("user_id",userId).eq("follow_user_id",followUserId).count();
        return Result.ok(count > 0);
    }

    //NOTE 获取当前用户和指定用户的共同关注者
    @Override
    public Result followCommons(Long id) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 2.求交集
        String key2 = "follows:" + id;
        //NOTE 使用 Redis 的 Set 交集操作，快速找出两个用户的共同关注者
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()) {
            // 无交集
            return Result.ok(Collections.emptyList());
        }
        // 3.解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 4.查询用户
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

}

//NOTE 这段代码展示了如何使用 Spring Boot 和 MyBatis-Plus 实现用户关注功能，
// 并结合 Redis 提高性能，适合用于面试中展示对后端开发技术栈的掌握。

//NOTE 高频面试题
//NOTE 1.为什么要选redis中的set数据结构来存储关注用户的数据？而不用list或者zset？
//答：因为set数据结构天然支持去重和高效的交集运算，适合存储关注关系这种无序且唯一的数据集合。
// set支持自动去重，避免重复关注同一用户的问题。
// set的交集操作在计算共同关注者时非常高效，时间复杂度较低，适合高并发场景。
// list不支持去重，且交集运算效率低下，不适合此场景。
// zset虽然支持排序，但在关注关系中排序并不重要，且交集运算复杂度较高。

//NOTE 2.如何保住数据库和redis中的关注数据一致性？
//答：可以通过以下几种方式来保证数据一致性：
// 事务处理：在关注和取关操作中，使用数据库事务确保数据操作的原子性，确保数据库和Redis的更新要么同时成功，要么同时失败。
// 双写机制：在更新数据库的同时，立即更新Redis。如果Redis更新失败，可以设置重试机制，确保最终一致性。
// 定期同步：定期从数据库中读取关注数据，同步到Redis，修正可能存在的不一致情况。
// 监听机制：使用消息队列监听数据库的变更事件，实时更新Redis中的数据。

//NOTE 3.如果用户有百万粉丝，Set会很大，如何优化？
//答：可以考虑以下优化策略：
// 分片存储：将关注数据按用户ID进行分片存储，减少单个Set的大小，提高查询效率。
// 热门用户缓存：对于拥有大量粉丝的用户，可以单独缓存其关注数据，减少对主Set的访问压力。
// 限制关注数量：对用户的关注数量进行限制，防止单个用户关注过多导致Set过大。
// 使用布隆过滤器：在关注操作前，使用布隆过滤器快速判断是否已关注，减少对Set的访问频率。

















