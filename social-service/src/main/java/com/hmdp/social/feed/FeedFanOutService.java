package com.hmdp.social.feed;

import com.hmdp.social.service.IFollowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.FEED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY_TTL_DAYS;

/**
 * Feed 写扩散（SPEC-09 §5.4）。
 *
 * <p>写扩散本身保留（§1.12 明令不得改成拉模式），改的是**执行方式**：
 * 发布请求只提交任务，不等待 fan-out 完成；任务内异常只记日志，绝不上抛到请求线程。
 */
@Slf4j
@Service
public class FeedFanOutService {

    private final IFollowService followService;

    private final StringRedisTemplate stringRedisTemplate;

    private final Executor feedFanOutExecutor;

    public FeedFanOutService(IFollowService followService,
                             StringRedisTemplate stringRedisTemplate,
                             @Qualifier("feedFanOutExecutor") Executor feedFanOutExecutor) {
        this.followService = followService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.feedFanOutExecutor = feedFanOutExecutor;
    }

    /**
     * 提交写扩散任务。方法本身只做提交，立即返回。
     */
    public void submit(Long blogId, Long authorId) {
        try {
            feedFanOutExecutor.execute(() -> fanOut(blogId, authorId));
        } catch (RejectedExecutionException e) {
            log.error("Feed 推送任务被拒绝: blogId={}, authorId={}", blogId, authorId, e);
        }
    }

    private void fanOut(Long blogId, Long authorId) {
        try {
            List<Long> followerIds = followService.queryFollowerIds(authorId);
            if (followerIds == null || followerIds.isEmpty()) {
                return;
            }
            String blogIdStr = blogId.toString();
            long now = System.currentTimeMillis();
            for (Long followerId : followerIds) {
                String key = FEED_KEY + followerId;
                stringRedisTemplate.opsForZSet().add(key, blogIdStr, now);
                stringRedisTemplate.expire(key, FEED_KEY_TTL_DAYS, TimeUnit.DAYS);
            }
            log.debug("Feed 推送完成: blogId={}, 粉丝数={}", blogId, followerIds.size());
        } catch (Exception e) {
            log.error("Feed 推送失败: blogId={}, authorId={}", blogId, authorId, e);
        }
    }
}
