package com.hmdp.social.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.social.feed.FeedFanOutService;
import com.hmdp.social.feign.ShopFeignClient;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SPEC-09 A8：发布请求与粉丝数解耦——请求线程只提交任务，绝不在请求线程内做写扩散。
 */
class BlogServiceImplFeedTest {

    private BlogServiceImpl service;
    private FeedFanOutService feedFanOutService;
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUp() {
        service = Mockito.spy(new BlogServiceImpl());
        feedFanOutService = mock(FeedFanOutService.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(service, "feedFanOutService", feedFanOutService);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(service, "shopFeignClient", mock(ShopFeignClient.class));

        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void 发布笔记只提交fanout任务不在请求线程写扩散() {
        ShopFeignClient shopFeignClient = (ShopFeignClient) ReflectionTestUtils.getField(service, "shopFeignClient");
        when(shopFeignClient.queryShopById(1L)).thenReturn(Result.ok());
        doAnswer(inv -> {
            ((Blog) inv.getArgument(0)).setId(99L);
            return true;
        }).when(service).save(any(Blog.class));

        Blog blog = new Blog();
        blog.setTitle("好吃的店");
        blog.setContent("环境不错");
        blog.setShopId(1L);

        Result r = service.saveBlog(blog);

        assertTrue(r.getSuccess());
        assertEquals(99L, r.getData());
        verify(feedFanOutService).submit(99L, 7L);
        verify(stringRedisTemplate, never()).opsForZSet();
    }

    @Test
    void 保存失败时不提交fanout任务() {
        ShopFeignClient shopFeignClient = (ShopFeignClient) ReflectionTestUtils.getField(service, "shopFeignClient");
        when(shopFeignClient.queryShopById(1L)).thenReturn(Result.ok());
        doReturn(false).when(service).save(any(Blog.class));

        Blog blog = new Blog();
        blog.setTitle("好吃的店");
        blog.setContent("环境不错");
        blog.setShopId(1L);

        assertTrue(!service.saveBlog(blog).getSuccess());
        verify(feedFanOutService, never()).submit(any(), any());
    }
}
