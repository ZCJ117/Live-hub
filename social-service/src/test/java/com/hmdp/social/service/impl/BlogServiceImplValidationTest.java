package com.hmdp.social.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.social.feign.ShopFeignClient;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * SPEC-09 §1.8 / G7：写接口必须具备基本入参校验（非空、长度、外键存在性）。
 * 商户存在性走 shop-service 的 {@code GET /shop/{id}}，校验失败为 fail-closed。
 */
class BlogServiceImplValidationTest {

    private BlogServiceImpl service;
    private ShopFeignClient shopFeignClient;

    @BeforeEach
    void setUp() {
        service = Mockito.spy(new BlogServiceImpl());
        shopFeignClient = mock(ShopFeignClient.class);
        ReflectionTestUtils.setField(service, "shopFeignClient", shopFeignClient);

        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    private Blog validBlog() {
        Blog blog = new Blog();
        blog.setTitle("好吃的店");
        blog.setContent("环境不错");
        blog.setShopId(1L);
        return blog;
    }

    private Result save(Blog blog) {
        doAnswer(inv -> {
            ((Blog) inv.getArgument(0)).setId(99L);
            return true;
        }).when(service).save(any(Blog.class));
        return service.saveBlog(blog);
    }

    @Test
    void 未登录时拒绝发布() {
        UserHolder.removeUser();
        assertFalse(service.saveBlog(validBlog()).getSuccess());
        verify(service, never()).save(any(Blog.class));
    }

    @Test
    void 标题为空时拒绝发布() {
        Blog blog = validBlog();
        blog.setTitle("  ");
        assertFalse(save(blog).getSuccess());
        verify(service, never()).save(any(Blog.class));
    }

    @Test
    void 标题超长时拒绝发布() {
        Blog blog = validBlog();
        blog.setTitle("x".repeat(256));
        assertFalse(save(blog).getSuccess());
        verify(service, never()).save(any(Blog.class));
    }

    @Test
    void 内容为空时拒绝发布() {
        Blog blog = validBlog();
        blog.setContent(null);
        assertFalse(save(blog).getSuccess());
        verify(service, never()).save(any(Blog.class));
    }

    @Test
    void 内容超长时拒绝发布() {
        Blog blog = validBlog();
        blog.setContent("x".repeat(5001));
        assertFalse(save(blog).getSuccess());
        verify(service, never()).save(any(Blog.class));
    }

    @Test
    void 图片串超长时拒绝发布() {
        Blog blog = validBlog();
        blog.setImages("x".repeat(1025));
        assertFalse(save(blog).getSuccess());
        verify(service, never()).save(any(Blog.class));
    }

    @Test
    void 商户为空时拒绝发布() {
        Blog blog = validBlog();
        blog.setShopId(null);
        assertFalse(save(blog).getSuccess());
        verify(service, never()).save(any(Blog.class));
    }

    @Test
    void 商户不存在时拒绝发布() {
        when(shopFeignClient.queryShopById(1L)).thenReturn(Result.fail("店铺不存在!"));
        assertFalse(save(validBlog()).getSuccess());
        verify(service, never()).save(any(Blog.class));
    }

    @Test
    void 商户服务不可用时拒绝发布() {
        when(shopFeignClient.queryShopById(anyLong())).thenThrow(new RuntimeException("shop-service down"));
        assertFalse(save(validBlog()).getSuccess());
        verify(service, never()).save(any(Blog.class));
    }

    @Test
    void 合法入参发布成功并强制覆盖作者为登录态() {
        when(shopFeignClient.queryShopById(1L)).thenReturn(Result.ok());

        Blog blog = validBlog();
        blog.setUserId(999L); // 伪造作者

        Result r = save(blog);

        assertTrue(r.getSuccess());
        verify(service).save(blog);
        assertEquals(7L, blog.getUserId(), "作者必须由登录态强制覆盖");
    }
}
