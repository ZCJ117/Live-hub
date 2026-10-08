package com.hmdp.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.social.feign.UserFeignClient;
import com.hmdp.social.support.MybatisLambdaCache;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SPEC-09 §1.11：取关的返回值语义、判空、粉丝 id 查询。
 *
 * <p>MyBatis-Plus 的 {@code remove(QueryWrapper)} 返回的是"语句是否执行成功"而非
 * "是否删除了行"，未关注状态下取关同样返回 true。修复后必须先 count 再删，
 * 且 Redis 的 SREM 无条件执行以收敛两侧状态。
 */
class FollowServiceImplTest {

    private static final long ME = 7L;
    private static final long TARGET = 9L;
    private static final String KEY = "follows:" + ME;

    private FollowServiceImpl service;
    private StringRedisTemplate stringRedisTemplate;
    private SetOperations<String, String> setOperations;

    @BeforeAll
    static void prewarmLambdaCache() {
        MybatisLambdaCache.prewarm(Follow.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = Mockito.spy(new FollowServiceImpl());
        stringRedisTemplate = mock(StringRedisTemplate.class);
        setOperations = mock(SetOperations.class);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(service, "userFeignClient", mock(UserFeignClient.class));

        UserDTO user = new UserDTO();
        user.setId(ME);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void 未登录时关注返回失败() {
        UserHolder.removeUser();
        assertFalse(service.follow(TARGET, true).getSuccess());
        assertFalse(service.isFollow(TARGET).getSuccess());
        assertFalse(service.followCommons(TARGET).getSuccess());
        verify(service, never()).save(any(Follow.class));
    }

    @Test
    void 关注时写库并写入Redis集合() {
        doReturn(true).when(service).save(any(Follow.class));

        Result r = service.follow(TARGET, true);

        assertTrue(r.getSuccess());
        verify(setOperations).add(KEY, String.valueOf(TARGET));
    }

    @Test
    void 未关注状态下取关不执行删除但仍清理Redis() {
        doReturn(0L).when(service).count(any(Wrapper.class));

        assertTrue(service.follow(TARGET, false).getSuccess());

        verify(service, never()).remove(any(Wrapper.class));
        verify(setOperations).remove(KEY, String.valueOf(TARGET));
    }

    @Test
    void 已关注状态下取关删除数据库行并清理Redis() {
        doReturn(1L).when(service).count(any(Wrapper.class));
        doReturn(true).when(service).remove(any(Wrapper.class));

        assertTrue(service.follow(TARGET, false).getSuccess());

        verify(service).remove(any(Wrapper.class));
        verify(setOperations).remove(KEY, String.valueOf(TARGET));
    }

    @Test
    void 查询粉丝id列表() {
        Follow f1 = new Follow().setUserId(10L).setFollowUserId(ME);
        Follow f2 = new Follow().setUserId(11L).setFollowUserId(ME);
        doReturn(List.of(f1, f2)).when(service).list(any(Wrapper.class));

        assertEquals(List.of(10L, 11L), service.queryFollowerIds(ME));
    }

    @Test
    void 无粉丝时返回空列表() {
        doReturn(List.of()).when(service).list(any(Wrapper.class));
        assertTrue(service.queryFollowerIds(ME).isEmpty());
    }

    @Test
    void 查询粉丝列表按follow_user_id过滤() {
        doReturn(List.of()).when(service).list(any(Wrapper.class));

        service.queryFollowerIds(ME);

        var captor = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(service).list(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("follow_user_id"),
                "必须按 follow_user_id 过滤：" + captor.getValue().getSqlSegment());
    }

    @Test
    void 共同关注在用户服务失败时返回失败() {
        doReturn(java.util.Set.of("10")).when(setOperations).intersect(KEY, "follows:" + TARGET);
        var userFeignClient = (UserFeignClient) ReflectionTestUtils.getField(service, "userFeignClient");
        when(userFeignClient.getUserByIds(any())).thenReturn(Result.fail("获取用户信息失败"));

        Result r = service.followCommons(TARGET);

        assertFalse(r.getSuccess());
        verify(userFeignClient).getUserByIds(List.of(10L));
    }
}
