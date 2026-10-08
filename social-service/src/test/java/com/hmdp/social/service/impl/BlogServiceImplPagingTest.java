package com.hmdp.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.social.feign.UserFeignClient;
import com.hmdp.social.mapper.BlogMapper;
import com.hmdp.social.support.MybatisLambdaCache;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SPEC-09 §1.9 / §1.10：分页下限兜底、lastId 兜底、ZSet 顺序的内存排序。
 */
class BlogServiceImplPagingTest {

    private BlogServiceImpl service;
    private BlogMapper blogMapper;
    private StringRedisTemplate stringRedisTemplate;
    private ZSetOperations<String, String> zSetOperations;
    private UserFeignClient userFeignClient;

    @BeforeAll
    static void prewarmLambdaCache() {
        MybatisLambdaCache.prewarm(Blog.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = Mockito.spy(new BlogServiceImpl());
        blogMapper = mock(BlogMapper.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        zSetOperations = mock(ZSetOperations.class);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        ReflectionTestUtils.setField(service, "baseMapper", blogMapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        userFeignClient = mock(UserFeignClient.class);
        // 记录查询与 Feed 加载都会逐条取博主信息，未打桩时 mock 返回 null 会让被测代码空指针
        when(userFeignClient.getUserById(any())).thenReturn(Result.fail("未打桩"));
        ReflectionTestUtils.setField(service, "userFeignClient", userFeignClient);

        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void 热点查询页码为0时兜底为第1页() {
        when(blogMapper.selectPage(any(IPage.class), any())).thenReturn(new Page<>());

        Result r = service.queryHotBlog(0);

        assertTrue(r.getSuccess());
        assertTrue(capturedPage().getCurrent() == 1L, "current=0 必须兜底为 1");
    }

    @Test
    void 热点查询页码为null时兜底为第1页() {
        when(blogMapper.selectPage(any(IPage.class), any())).thenReturn(new Page<>());

        assertTrue(service.queryHotBlog(null).getSuccess());
        assertTrue(capturedPage().getCurrent() == 1L, "current=null 必须兜底为 1");
    }

    @Test
    void 用户笔记查询页码为0时兜底为第1页() {
        when(blogMapper.selectPage(any(IPage.class), any())).thenReturn(new Page<>());

        assertTrue(service.queryBlogByUserId(0, 1L).getSuccess());
        assertTrue(capturedPage().getCurrent() == 1L);
    }

    @Test
    void Feed首屏未传lastId时以当前时间兜底() {
        when(zSetOperations.reverseRangeByScoreWithScores(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(java.util.Set.of());

        assertTrue(service.queryBlogOfFollow(null, 0).getSuccess());

        ArgumentCaptor<Double> maxCaptor = ArgumentCaptor.forClass(Double.class);
        verify(zSetOperations).reverseRangeByScoreWithScores(anyString(), anyDouble(), maxCaptor.capture(),
                anyLong(), anyLong());
        assertTrue(maxCaptor.getValue() > System.currentTimeMillis() - 60_000,
                "null 的 lastId 必须兜底为当前时间戳，实际：" + maxCaptor.getValue());
    }

    @Test
    void Feed查询结果按收件箱顺序返回且不使用SQL拼接排序() {
        long t = 1_700_000_000_000L;
        ZSetOperations.TypedTuple<String> newer = new org.springframework.data.redis.core.DefaultTypedTuple<>("20", (double) t);
        ZSetOperations.TypedTuple<String> older = new org.springframework.data.redis.core.DefaultTypedTuple<>("10", (double) (t - 1000));
        when(zSetOperations.reverseRangeByScoreWithScores(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(new java.util.LinkedHashSet<>(List.of(newer, older)));

        Blog blog10 = new Blog().setId(10L);
        Blog blog20 = new Blog().setId(20L);
        // DB 返回顺序故意反着来，用来验证代码按收件箱顺序重排
        doReturn(List.of(blog10, blog20)).when(service).list(any(Wrapper.class));

        Result r = service.queryBlogOfFollow(t, 0);

        assertTrue(r.getSuccess());
        var scroll = (com.hmdp.dto.ScrollResult) r.getData();
        assertEquals(List.of(20L, 10L), scroll.getList().stream().map(b -> ((Blog) b).getId()).toList(),
                "必须按收件箱分值顺序重排");
        ArgumentCaptor<Wrapper<Blog>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(service).list(captor.capture());
        assertFalse(captor.getValue().getSqlSegment().contains("FIELD"),
                "不得再使用 ORDER BY FIELD 字符串拼接：" + captor.getValue().getSqlSegment());
    }

    @SuppressWarnings("unchecked")
    private IPage<Blog> capturedPage() {
        ArgumentCaptor<IPage<Blog>> captor = ArgumentCaptor.forClass(IPage.class);
        // 注意：BaseMapper 方法上用裸 any()，不可用 any(Wrapper.class)——该调用点不发出泛型 Signature，
        // Mockito 的类型兼容过滤会拒绝匹配（Task 6 实测：ShopCacheWarmUpTest 等同族测试一律用 selectList(any())）
        verify(blogMapper, atLeastOnce()).selectPage(captor.capture(), any());
        return captor.getValue();
    }
}
