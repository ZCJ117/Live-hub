package com.hmdp.social.feed;

import com.hmdp.social.service.IFollowService;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        followService = mock(IFollowService.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        zSetOperations = mock(ZSetOperations.class);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        // 同步 Executor：让断言可以观察到任务体
        service = new FeedFanOutService(followService, stringRedisTemplate, (Executor) Runnable::run);
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
        FeedFanOutService rejecting = new FeedFanOutService(followService, stringRedisTemplate,
                task -> { throw new RejectedExecutionException("queue full"); });

        rejecting.submit(BLOG_ID, AUTHOR_ID); // 不得抛出

        verifyNoInteractions(zSetOperations);
    }

    @Test
    void 提交不等待任务执行() {
        java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
        FeedFanOutService nonExecuting = new FeedFanOutService(followService, stringRedisTemplate,
                task -> { /* 故意不执行 */ ran.set(false); });
        when(followService.queryFollowerIds(AUTHOR_ID)).thenReturn(List.of(10L));

        nonExecuting.submit(BLOG_ID, AUTHOR_ID);

        verify(followService, never()).queryFollowerIds(anyLong());
        assertTrue(!ran.get(), "submit 不得同步执行任务体");
    }

    /**
     * SPEC-09 §5.4 装配护栏：只允许一个构造器。
     *
     * <p>若再出现第二个（比如为测试便利补的包私有 no-arg 构造器），Spring 的
     * {@code determineConstructorsFromBeanPostProcessors} 会因「声明构造器数 ≠ 1」而不走
     * 「唯一构造器免 {@code @Autowired}」的捷径，最终回落到无参构造器，注入出三个 null 协作者，
     * 使 {@code POST /blog} 的 fan-out 首次调用即 NPE。此测试用真实容器锁死该不变量。
     */
    @Test
    void 容器装配后三个协作者均非空_防止多构造器导致Spring走无参构造() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(IFollowService.class, () -> mock(IFollowService.class));
            ctx.registerBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));
            ctx.registerBean("feedFanOutExecutor", Executor.class, () -> (Executor) Runnable::run);
            ctx.register(FeedFanOutService.class);
            ctx.refresh();

            FeedFanOutService assembled = ctx.getBean(FeedFanOutService.class);
            assertNotNull(ReflectionTestUtils.getField(assembled, "followService"),
                    "followService 在容器装配后不得为 null");
            assertNotNull(ReflectionTestUtils.getField(assembled, "stringRedisTemplate"),
                    "stringRedisTemplate 在容器装配后不得为 null");
            assertNotNull(ReflectionTestUtils.getField(assembled, "feedFanOutExecutor"),
                    "feedFanOutExecutor 在容器装配后不得为 null");
        }
    }
}
