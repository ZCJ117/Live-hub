package com.hmdp.social.cache;

import com.hmdp.entity.Follow;
import com.hmdp.social.mapper.FollowMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SPEC-09 §1.11：tb_follow 已有数据的老用户其 Redis Set 为空，导致 followCommons 恒返回空。
 * 启动时按 userId 分组批量 SADD 回填；SADD 幂等，重复启动无害；回填失败不得阻塞启动。
 */
class FollowCacheWarmupRunnerTest {

    private FollowMapper followMapper;
    private StringRedisTemplate stringRedisTemplate;
    private SetOperations<String, String> setOperations;
    private FollowCacheWarmupRunner runner;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        followMapper = mock(FollowMapper.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        setOperations = mock(SetOperations.class);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        runner = new FollowCacheWarmupRunner(followMapper, stringRedisTemplate);
    }

    private Follow follow(long userId, long followUserId) {
        return new Follow().setUserId(userId).setFollowUserId(followUserId);
    }

    @Test
    void 按用户分组批量回填到Redis集合() throws Exception {
        when(followMapper.selectList(any()))
                .thenReturn(List.of(follow(1L, 10L), follow(1L, 11L), follow(2L, 10L)));

        runner.run(null);

        verify(setOperations).add("follows:1", "10", "11");
        verify(setOperations).add("follows:2", "10");
    }

    @Test
    void 无关注数据时不写Redis() throws Exception {
        when(followMapper.selectList(any())).thenReturn(List.of());

        runner.run(null);

        verifyNoInteractions(setOperations);
    }

    @Test
    void 回填失败不阻塞启动() {
        when(followMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> runner.run(null));
    }

    @Test
    void 单条SADD失败不影响其余用户() throws Exception {
        when(followMapper.selectList(any()))
                .thenReturn(List.of(follow(1L, 10L), follow(2L, 10L)));
        when(setOperations.add("follows:1", "10")).thenThrow(new RuntimeException("redis error"));

        assertDoesNotThrow(() -> runner.run(null));
    }
}
