package com.hmdp.social.feed;

import com.hmdp.social.service.IFollowService;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SPEC-09 A8/A9：写扩散不阻塞主请求，且失败可观测（有 error 日志）。
 */
@org.junit.jupiter.api.extension.ExtendWith(OutputCaptureExtension.class)
class FeedFanOutServiceTest {

    private static final long BLOG_ID = 100L;
    private static final long AUTHOR_ID = 7L;

    private FeedFanOutService service;
    private IFollowService followService;
    private StringRedisTemplate stringRedisTemplate;
    private ZSetOperations<String, String> zSetOperations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new FeedFanOutService();
        followService = mock(IFollowService.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        zSetOperations = mock(ZSetOperations.class);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        ReflectionTestUtils.setField(service, "followService", followService);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        // 同步 Executor：让断言可以观察到任务体
        ReflectionTestUtils.setField(service, "feedFanOutExecutor", (Executor) Runnable::run);
    }

    @Test
    void 把笔记推送到每个粉丝的收件箱并设置TTL() {
        when(followService.queryFollowerIds(AUTHOR_ID)).thenReturn(List.of(10L, 11L));

        service.submit(BLOG_ID, AUTHOR_ID);

        verify(zSetOperations).add(eq(RedisConstants.FEED_KEY + 10L), eq(String.valueOf(BLOG_ID)), anyDouble());
        verify(zSetOperations).add(eq(RedisConstants.FEED_KEY + 11L), eq(String.valueOf(BLOG_ID)), anyDouble());
        verify(stringRedisTemplate).expire(RedisConstants.FEED_KEY + 10L, RedisConstants.FEED_KEY_TTL_DAYS,
                java.util.concurrent.TimeUnit.DAYS);
    }

    @Test
    void 无粉丝时不触碰Redis() {
        when(followService.queryFollowerIds(AUTHOR_ID)).thenReturn(List.of());

        service.submit(BLOG_ID, AUTHOR_ID);

        verifyNoInteractions(zSetOperations);
    }

    @Test
    void 推送异常被捕获并记录error日志(CapturedOutput output) {
        when(followService.queryFollowerIds(AUTHOR_ID)).thenReturn(List.of(10L));
        doThrow(new RuntimeException("redis down"))
                .when(zSetOperations).add(anyString(), anyString(), anyDouble());

        service.submit(BLOG_ID, AUTHOR_ID); // 不得抛出

        assertTrue(output.getOut().contains("Feed 推送失败"), "失败必须可观测：" + output.getOut());
    }

    @Test
    void 线程池拒绝时记账而不抛出() {
        ReflectionTestUtils.setField(service, "feedFanOutExecutor",
                (Executor) task -> { throw new RejectedExecutionException("queue full"); });

        service.submit(BLOG_ID, AUTHOR_ID); // 不得抛出

        verifyNoInteractions(zSetOperations);
    }

    @Test
    void 提交不等待任务执行() {
        java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
        ReflectionTestUtils.setField(service, "feedFanOutExecutor",
                (Executor) task -> { /* 故意不执行 */ ran.set(false); });
        when(followService.queryFollowerIds(AUTHOR_ID)).thenReturn(List.of(10L));

        service.submit(BLOG_ID, AUTHOR_ID);

        verify(followService, never()).queryFollowerIds(anyLong());
        assertTrue(!ran.get(), "submit 不得同步执行任务体");
    }
}
