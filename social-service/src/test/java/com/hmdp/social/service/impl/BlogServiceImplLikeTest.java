package com.hmdp.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.social.feign.UserFeignClient;
import com.hmdp.social.support.MybatisLambdaCache;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SPEC-09 A4/A5/A6。
 *
 * <p>A4：点赞数在任何操作序列下 ≥ 0——递减必须带 liked > 0 下限守卫。
 * <p>A5：对不存在的博客点赞必须返回失败（修复前静默返回 ok）。
 * <p>收敛：DB 未递减时（守卫挡回）仍必须把用户移出 Redis ZSet，
 * 否则用户会永久卡在"Redis 说已点赞、DB 已归零"的死锁态。
 */
class BlogServiceImplLikeTest {

    private static final long BLOG_ID = 1L;
    private static final long USER_ID = 7L;
    private static final String KEY = RedisConstants.BLOG_LIKED_KEY + BLOG_ID;

    private BlogServiceImpl service;
    private StringRedisTemplate stringRedisTemplate;
    private ZSetOperations<String, String> zSetOperations;

    @BeforeAll
    static void prewarmLambdaCache() {
        // 纯单测环境下 lambda wrapper 的列名解析需要先预热实体元数据，否则构造 wrapper 即抛异常
        MybatisLambdaCache.prewarm(Blog.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = Mockito.spy(new BlogServiceImpl());
        stringRedisTemplate = mock(StringRedisTemplate.class);
        zSetOperations = mock(ZSetOperations.class);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(service, "userFeignClient", mock(UserFeignClient.class));

        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void 点赞不存在的博客返回失败() {
        doReturn(null).when(service).getById(999L);

        Result r = service.likeBlog(999L);

        assertFalse(r.getSuccess(), "对不存在的博客点赞必须失败（SPEC-09 A5）");
        verify(service, never()).update(any(Wrapper.class));
        verify(zSetOperations, never()).add(any(), any(), anyDouble());
    }

    @Test
    void 未登录点赞返回失败() {
        UserHolder.removeUser();

        Result r = service.likeBlog(BLOG_ID);

        assertFalse(r.getSuccess());
        verify(service, never()).getById(any());
    }

    @Test
    void 首次点赞递增并在成功后写入Redis() {
        doReturn(new Blog().setId(BLOG_ID)).when(service).getById(BLOG_ID);
        when(zSetOperations.score(KEY, String.valueOf(USER_ID))).thenReturn(null);
        doReturn(true).when(service).update(any(Wrapper.class));

        Result r = service.likeBlog(BLOG_ID);

        assertTrue(r.getSuccess());
        assertEquals("liked = liked + 1", capturedSqlSet());
        verify(zSetOperations).add(eq(KEY), eq(String.valueOf(USER_ID)), anyDouble());
    }

    @Test
    void 数据库写失败时点赞返回失败且不写Redis() {
        doReturn(new Blog().setId(BLOG_ID)).when(service).getById(BLOG_ID);
        when(zSetOperations.score(KEY, String.valueOf(USER_ID))).thenReturn(null);
        doReturn(false).when(service).update(any(Wrapper.class));

        Result r = service.likeBlog(BLOG_ID);

        assertFalse(r.getSuccess(), "DB 更新 0 行必须明确返回失败（SPEC-09 §1.3）");
        verify(zSetOperations, never()).add(any(), any(), anyDouble());
    }

    @Test
    void 取消点赞的递减带liked大于0守卫() {
        doReturn(new Blog().setId(BLOG_ID)).when(service).getById(BLOG_ID);
        when(zSetOperations.score(KEY, String.valueOf(USER_ID))).thenReturn(1.0);
        doReturn(true).when(service).update(any(Wrapper.class));

        service.likeBlog(BLOG_ID);

        ArgumentCaptor<Wrapper<Blog>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(service).update(captor.capture());
        LambdaUpdateWrapper<Blog> wrapper = (LambdaUpdateWrapper<Blog>) captor.getValue();
        assertEquals("liked = liked - 1", wrapper.getSqlSet());
        assertTrue(wrapper.getSqlSegment().contains("liked"),
                "递减语句必须带 liked > 0 下限守卫（SPEC-09 A4）：" + wrapper.getSqlSegment());
    }

    @Test
    void 守卫挡回递减时仍然清理Redis以收敛状态() {
        doReturn(new Blog().setId(BLOG_ID)).when(service).getById(BLOG_ID);
        when(zSetOperations.score(KEY, String.valueOf(USER_ID))).thenReturn(1.0);
        // 模拟 "DB 成功、Redis 移除失败" 之后的重复调用：liked 已为 0，守卫让 UPDATE 影响 0 行
        doReturn(false).when(service).update(any(Wrapper.class));

        assertTrue(service.likeBlog(BLOG_ID).getSuccess());
        verify(zSetOperations).remove(KEY, String.valueOf(USER_ID));
    }

    @Test
    void 重复取消点赞始终清理Redis且不产生负值路径() {
        doReturn(new Blog().setId(BLOG_ID)).when(service).getById(BLOG_ID);
        when(zSetOperations.score(KEY, String.valueOf(USER_ID))).thenReturn(1.0);
        // 前 5 次 DB 被守卫挡回（liked 已为 0），Redis 移除也"失败"（模拟抖动）
        doReturn(false).when(service).update(any(Wrapper.class));

        for (int i = 0; i < 5; i++) {
            assertTrue(service.likeBlog(BLOG_ID).getSuccess());
        }

        // 每一次都执行了带守卫的递减 + Redis 清理；SQL 中永远不存在无守卫的递减
        ArgumentCaptor<Wrapper<Blog>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(service, times(5)).update(captor.capture());
        for (Wrapper<Blog> wrapper : captor.getAllValues()) {
            LambdaUpdateWrapper<Blog> w = (LambdaUpdateWrapper<Blog>) wrapper;
            assertEquals("liked = liked - 1", w.getSqlSet());
            assertTrue(w.getSqlSegment().contains("liked >"), "每次递减都必须带守卫：" + w.getSqlSegment());
        }
        verify(zSetOperations, times(5)).remove(KEY, String.valueOf(USER_ID));
    }

    private String capturedSqlSet() {
        ArgumentCaptor<Wrapper<Blog>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(service).update(captor.capture());
        return ((LambdaUpdateWrapper<Blog>) captor.getValue()).getSqlSet();
    }
}
