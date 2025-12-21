package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 左常健
 * @since 2025-12-04
 */

// NOTE 这个类实现了博客相关的服务，包括查询热门博客、点赞博客、保存博客等功能。
// 它使用了MyBatis-Plus进行数据库操作，使用Redis进行缓存和点赞数据的存储。
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;


    // NOTE 查看热门博客
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    private void isBlogLiked(Blog blog) {
        // 1. 安全获取登录用户
        UserDTO user = UserHolder.getUser();

        // 如果用户未登录，默认未点赞
        if (user == null) {
            blog.setIsLike(Boolean.FALSE);
            return;
        }

        // 2. 获取登录用户ID
        Long userId = user.getId();

        // 3. 判断当前登录用户是否已经点赞
        String key = "blog:liked:" + blog.getId();

        // 先检查键的类型
        DataType keyType = stringRedisTemplate.type(key);
        if (keyType != null && keyType != DataType.ZSET) {
            // 类型不对，删除并记录日志
            String logMessage = "Redis key " + key + " has wrong type: " + keyType + ". Deleting key.";
            log.warn(logMessage);
            stringRedisTemplate.delete(key);
            blog.setIsLike(Boolean.FALSE);
            return;
        }

        // 正常查询
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }


    @Override
    public Result likeBlog(Long id) {
        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        //2.判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        if (score == null) {
            //3.如果没有点赞，可以点赞
            //3.1数据库点赞 +1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //3.2保存用户到Redis的set集合中
            if (isSuccess) {
                // NOTE 这里是用户点赞时，用ZSet存储点赞记录，score为时间戳
                // NOTE ZSet 可以按照时间排序，方便后续查询最近点赞的用户
                stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());
            }
        } else {
            //4.如果已经点赞
            //4.1数据库点赞数 -1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //4.2把用户从Redis的set集合中移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        //1.查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null ||top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",",ids);
        //3.根据用户id查询用户
        List<UserDTO> userDTOS = userService.query()
                .in("id",ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user,UserDTO.class))
                .collect(Collectors.toList());
        //4.返回
        return Result.ok(userDTOS);


    }


    // NOTE 保存博客并推送给粉丝
    // NOTE 这里采用推送模式，写扩散 （fan-out on write）  这个模式适用于读多写少的场景
    @Override
    public Result saveBlog(Blog blog) {
        //1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        //2.保存探店笔记
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }
        //3.查询笔记作者的所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id",user.getId()).list();
        //4.推送笔记id给所有粉丝
        for(Follow follow : follows){
            //4.1获取粉丝id
            Long userId = follow.getUserId();
            //4.2推送
            String key = "feed:" + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());

        }

        //5返回id
        return Result.ok(blog.getId());
    }

    // NOTE 基于时间戳和偏移量的滚动分页查询关注的博文
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询收件箱
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max,offset,2);
        //3.非空判断
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //4解析数据
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for(ZSetOperations.TypedTuple<String> tuple : typedTuples){
            //4.1获取id
            ids.add(Long.valueOf(tuple.getValue()));
            //4.2获取分数（时间戳）
            long time = tuple.getScore().longValue();
            if(time == minTime){
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }
        //5.根据id查询blog
        String idStr = StrUtil.join(",",ids);
        List<Blog> blogs = query().in("id",ids).last("ORDER BY FIELD(id,"+ idStr + ")").list();

        for(Blog blog : blogs){
            //5.1查询blog有关的用户
            queryBlogUser(blog);
            //5.2查询blog是否被点赞
            isBlogLiked(blog);
        }

        //6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.ok(r);
    }


    @Override
    public Result queryBlogById(Long id) {
        //1.查询blog
        Blog blog = getById(id);
        if(blog==null){
            return Result.fail("笔记不存在");
        }
        //2.查询blog有关的用户
        queryBlogUser(blog);
        //3判断blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());

    }


}


// NOTE 高频面试题
// NOTE 1.点赞系统中要用ZSet存储点赞用户，为什么不是Set？
// 答：ZSet可以按照分数排序，方便查询最近点赞的用户，而Set不支持排序。

// NOTE 2.在查询博客时，为什么要检查Redis键的类型？
// 答：为了防止类型错误导致的异常，确保数据一致性和系统稳定性。

// NOTE 3.推送模式和拉取模式有什么区别？为什么选择推送模式？
// 答：推送模式在写入时将数据分发给所有相关用户，适合读多写少的场景；拉取模式在读取时才获取数据，
// 适合写多读少的场景。这里选择推送模式是因为博客系统通常读多写少，推送模式可以提高读取效率。

//NOTE 4.如何实现Feed的滚动分页查询？
// 答：通过时间戳和偏移量实现滚动分页查询，利用ZSet的分数排序特性，按时间顺序获取数据。

// NOTE 基于这段代码，面试官可以深入考察你对Spring Boot、MyBatis-Plus、Redis、分布式系统设计等核心技术的理解。























