package com.hmdp.social.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.social.feed.FeedFanOutService;
import com.hmdp.social.feign.ShopFeignClient;
import com.hmdp.social.feign.UserFeignClient;
import com.hmdp.social.mapper.BlogMapper;
import com.hmdp.social.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
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
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    /** 标题长度上限，对齐 tb_blog.title varchar(255) */
    private static final int MAX_TITLE_LENGTH = 255;
    /** 图片串长度上限，对齐 tb_blog.images varchar(1024) */
    private static final int MAX_IMAGES_LENGTH = 1024;
    /** 正文长度上限（DDL 为 text 无上限，此处取 5000 作为业务上限） */
    private static final int MAX_CONTENT_LENGTH = 5000;

    @Resource
    private UserFeignClient userFeignClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ShopFeignClient shopFeignClient;

    @Resource
    private FeedFanOutService feedFanOutService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 页码下限兜底（SPEC-09 §1.9）
        int page = Math.max(current == null ? 1 : current, 1);
        // 根据用户查询
        Page<Blog> pageResult = query()
                .orderByDesc("liked")
                .page(new Page<>(page, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = pageResult.getRecords();
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
        String key = BLOG_LIKED_KEY + blog.getId();

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
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录");
        }
        Long userId = user.getId();

        //2.校验博客存在（SPEC-09 §1.3：不存在时必须明确失败，不得静默返回成功）
        if (getById(id) == null) {
            return Result.fail("博客不存在");
        }

        //3.判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        if (score == null) {
            //4.未点赞 → 点赞
            //4.1数据库点赞 +1
            boolean isSuccess = update(Wrappers.<Blog>lambdaUpdate()
                    .setSql("liked = liked + 1")
                    .eq(Blog::getId, id));
            if (!isSuccess) {
                return Result.fail("操作失败，请重试");
            }
            //4.2保存用户到Redis的ZSet中
            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
        } else {
            //5.已点赞 → 取消点赞
            //5.1数据库点赞 -1，带 liked > 0 下限守卫，杜绝负值（SPEC-09 A4）
            update(Wrappers.<Blog>lambdaUpdate()
                    .setSql("liked = liked - 1")
                    .eq(Blog::getId, id)
                    .gt(Blog::getLiked, 0));
            //5.2无论 DB 是否真的递减，都把用户移出 Redis：
            //   守卫挡回（liked 已为 0）时若不移除，用户会永久卡在
            //   "Redis 说已点赞、DB 已归零"的死锁态——两级状态必须收敛到"未点赞"
            stringRedisTemplate.opsForZSet().remove(key, userId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        //1.查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        //3.根据用户id查询用户
        Result result = userFeignClient.getUserByIds(ids);
        if (!result.getSuccess()) {
            return Result.fail("获取用户信息失败");
        }
        List<UserDTO> userDTOS = (List<UserDTO>) result.getData();
        //4.返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        //1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录");
        }
        //2.入参校验（SPEC-09 §1.8 / G7）
        Result invalid = validateBlog(blog);
        if (invalid != null) {
            return invalid;
        }
        //3.强制覆盖作者，防伪造（不得采信前端传入的 userId）
        blog.setUserId(user.getId());
        //4.保存探店笔记
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }
        //5.异步推送笔记id给所有粉丝（SPEC-09 §5.4：不阻塞主请求，失败可观测）
        feedFanOutService.submit(blog.getId(), user.getId());
        //6.返回id
        return Result.ok(blog.getId());
    }

    /**
     * 发布笔记的入参校验。
     *
     * @return 校验通过返回 {@code null}；否则返回带失败原因的 {@link Result}
     */
    private Result validateBlog(Blog blog) {
        if (StrUtil.isBlank(blog.getTitle())) {
            return Result.fail("标题不能为空");
        }
        if (blog.getTitle().length() > MAX_TITLE_LENGTH) {
            return Result.fail("标题过长");
        }
        if (StrUtil.isBlank(blog.getContent())) {
            return Result.fail("内容不能为空");
        }
        if (blog.getContent().length() > MAX_CONTENT_LENGTH) {
            return Result.fail("内容过长");
        }
        if (blog.getImages() != null && blog.getImages().length() > MAX_IMAGES_LENGTH) {
            return Result.fail("图片过多或地址过长");
        }
        if (blog.getShopId() == null) {
            return Result.fail("商户不能为空");
        }
        // 外键存在性：fail-closed——商户服务不可用时宁可拒绝发布，也不写入挂到不存在商户的脏数据
        try {
            Result shopResult = shopFeignClient.queryShopById(blog.getShopId());
            if (shopResult == null || !Boolean.TRUE.equals(shopResult.getSuccess())) {
                return Result.fail("商户不存在");
            }
        } catch (Exception e) {
            // 注意：ServiceImpl 的 log 是 org.apache.ibatis.logging.Log，只有 error(String, Throwable)，
            // **不支持 SLF4J 的 {} 可变参数**
            log.error("商户存在性校验失败: shopId=" + blog.getShopId(), e);
            return Result.fail("商户信息校验失败，请稍后重试");
        }
        return null;
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录");
        }
        Long userId = user.getId();
        //1.1首屏未传 lastId 时以当前时间戳兜底，避免前端 400（SPEC-09 §1.7）
        long maxScore = max == null ? System.currentTimeMillis() : max;
        //2.查询收件箱
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, maxScore, offset, 2);
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
        //5.根据id查询blog，并按收件箱顺序在内存中重排
        //  （原实现用 last("ORDER BY FIELD(id, ...)") 拼接字符串，SPEC-09 §1.10 要求消除该范式）
        List<Blog> blogs = list(Wrappers.<Blog>lambdaQuery().in(Blog::getId, ids));
        Map<Long, Blog> byId = blogs.stream().collect(Collectors.toMap(Blog::getId, b -> b, (a, b) -> a));
        List<Blog> ordered = ids.stream().map(byId::get).filter(Objects::nonNull).toList();

        for (Blog blog : ordered) {
            //5.1查询blog有关的用户
            queryBlogUser(blog);
            //5.2查询blog是否被点赞
            isBlogLiked(blog);
        }

        //6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(ordered);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.ok(r);
    }

    @Override
    public Result queryBlogById(Long id) {
        //1.查询blog
        Blog blog = getById(id);
        if(blog == null){
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
        Result result = userFeignClient.getUserById(userId);
        if (result.getSuccess()) {
            UserDTO user = BeanUtil.copyProperties(result.getData(), UserDTO.class);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
    }

    @Override
    public Result queryBlogByUserId(Integer current, Long id) {
        // 页码下限兜底（SPEC-09 §1.9）
        int page = Math.max(current == null ? 1 : current, 1);
        // 根据用户查询
        Page<Blog> pageResult = query()
                .eq("user_id", id)
                .page(new Page<>(page, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = pageResult.getRecords();
        // 查询用户：本页博客同属该用户，博主信息只在循环外取一次（SPEC-07 §1.5 消除逐条远程调用）
        Result userResult = userFeignClient.getUserById(id);
        UserDTO author = userResult.getSuccess()
                ? BeanUtil.copyProperties(userResult.getData(), UserDTO.class)
                : null;
        for (Blog blog : records) {
            if (author != null) {
                blog.setName(author.getNickName());
                blog.setIcon(author.getIcon());
            }
            this.isBlogLiked(blog);
        }
        return Result.ok(records);
    }
}
