package com.hmdp.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComments;
import com.hmdp.social.dto.CommentVO;
import com.hmdp.social.feign.UserFeignClient;
import com.hmdp.social.service.IBlogService;
import com.hmdp.social.support.MybatisLambdaCache;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * SPEC-09 A1/A2/A3。
 *
 * <p>A1：评论的 user_id 必须由登录态注入，前端传入的 userId 一律被覆盖。
 * <p>A2：查询必须过滤 status != 0 的记录。
 * <p>A3：保存评论必须维护 tb_blog.comments 计数。
 */
class BlogCommentsServiceImplTest {

    private BlogCommentsServiceImpl service;
    private IBlogService blogService;
    private UserFeignClient userFeignClient;

    @BeforeAll
    static void prewarmLambdaCache() {
        MybatisLambdaCache.prewarm(Blog.class, BlogComments.class);
    }

    @BeforeEach
    void setUp() {
        service = Mockito.spy(new BlogCommentsServiceImpl());
        blogService = mock(IBlogService.class);
        userFeignClient = mock(UserFeignClient.class);
        ReflectionTestUtils.setField(service, "blogService", blogService);
        ReflectionTestUtils.setField(service, "userFeignClient", userFeignClient);
        login(7L);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    private void login(Long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        user.setNickName("user_" + userId);
        UserHolder.saveUser(user);
    }

    private BlogComments comment(Long blogId, String content) {
        BlogComments c = new BlogComments();
        c.setBlogId(blogId);
        c.setContent(content);
        return c;
    }

    @Test
    void 保存评论时前端伪造的userId被登录态覆盖() {
        BlogComments forged = comment(1L, "好吃");
        forged.setUserId(999L);              // 伪造他人身份
        forged.setId(12345L);                // 试图指定主键
        doReturn(true).when(service).save(any(BlogComments.class));
        when(blogService.update(any(Wrapper.class))).thenReturn(true);

        Result r = service.saveComment(forged);
        assertTrue(r.getSuccess());

        ArgumentCaptor<BlogComments> captor = ArgumentCaptor.forClass(BlogComments.class);
        verify(service).save(captor.capture());
        BlogComments saved = captor.getValue();
        assertEquals(7L, saved.getUserId(), "user_id 必须来自登录态");
        assertNull(saved.getId(), "主键必须清空，防前端指定");
        assertEquals(Boolean.FALSE, saved.getStatus(), "status 必须置为 0-正常");
        assertEquals(0, saved.getLiked());
        assertEquals(0L, saved.getParentId(), "一级评论 parentId 默认 0");
        assertEquals(1L, saved.getBlogId());
    }

    @Test
    void 保存评论后博客评论计数加一() {
        doReturn(true).when(service).save(any(BlogComments.class));
        when(blogService.update(any(Wrapper.class))).thenReturn(true);

        service.saveComment(comment(1L, "好吃"));

        ArgumentCaptor<Wrapper<Blog>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(blogService).update(captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("comments + 1"),
                "必须对 tb_blog.comments 做 +1：" + captor.getValue().getSqlSet());
    }

    @Test
    void 内容为空时拒绝保存() {
        Result r = service.saveComment(comment(1L, "   "));
        assertFalse(r.getSuccess());
        verify(service, never()).save(any(BlogComments.class));
        verify(blogService, never()).update(any(Wrapper.class));
    }

    @Test
    void 内容超长时拒绝保存() {
        Result r = service.saveComment(comment(1L, "x".repeat(501)));
        assertFalse(r.getSuccess());
        verify(service, never()).save(any(BlogComments.class));
    }

    @Test
    void blogId为空时拒绝保存() {
        BlogComments c = new BlogComments();
        c.setContent("好吃");
        Result r = service.saveComment(c);
        assertFalse(r.getSuccess());
        verify(service, never()).save(any(BlogComments.class));
    }

    @Test
    void 未登录时拒绝保存() {
        UserHolder.removeUser();
        Result r = service.saveComment(comment(1L, "好吃"));
        assertFalse(r.getSuccess());
        verify(service, never()).save(any(BlogComments.class));
    }

    @Test
    void 查询评论只返回status为0的记录并联查评论人昵称头像() {
        BlogComments c1 = comment(1L, "好吃");
        c1.setId(11L);
        c1.setUserId(7L);
        c1.setStatus(false);
        doReturn(1L).when(service).count(any(Wrapper.class));
        doReturn(List.of(c1)).when(service).list(any(Wrapper.class));
        when(userFeignClient.getUserByIds(List.of(7L))).thenReturn(
                Result.ok(List.of(Map.of("id", 7, "nickName", "小明", "icon", "a.png"))));

        Result r = service.queryCommentsByBlogId(1L, 1);

        assertTrue(r.getSuccess());
        assertEquals(1L, r.getTotal());
        List<?> data = (List<?>) r.getData();
        assertEquals(1, data.size());
        CommentVO vo = (CommentVO) data.get(0);
        assertEquals(11L, vo.getId());
        assertEquals("小明", vo.getNickName());
        assertEquals("a.png", vo.getIcon());
    }

    @Test
    void 查询评论的SQL同时过滤blog_id与status() {
        doReturn(0L).when(service).count(any(Wrapper.class));
        doReturn(List.of()).when(service).list(any(Wrapper.class));

        service.queryCommentsByBlogId(1L, 1);

        ArgumentCaptor<Wrapper<BlogComments>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(service).list(captor.capture());
        String segment = captor.getValue().getSqlSegment();
        assertTrue(segment.contains("blog_id"), "必须按 blog_id 过滤：" + segment);
        assertTrue(segment.contains("status"), "必须按 status 过滤（A2）：" + segment);
    }

    @Test
    void 页码为0或null时兜底为1且不抛异常() {
        doReturn(0L).when(service).count(any(Wrapper.class));
        doReturn(List.of()).when(service).list(any(Wrapper.class));

        assertTrue(service.queryCommentsByBlogId(1L, 0).getSuccess());
        assertTrue(service.queryCommentsByBlogId(1L, null).getSuccess());

        ArgumentCaptor<Wrapper<BlogComments>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(service, times(2)).list(captor.capture());
        for (Wrapper<BlogComments> w : captor.getAllValues()) {
            // 已核实：AbstractWrapper.getSqlSegment() = MergeSegments + lastSql，
            // 故 last("LIMIT ...") 的内容可以从 getSqlSegment() 断言到（getLastSql 并不存在）
            assertTrue(w.getSqlSegment().contains("LIMIT 5 OFFSET 0"),
                    "页码越界必须兜底为第 1 页：" + w.getSqlSegment());
        }
    }

    @Test
    void 用户服务不可用时评论列表降级返回不报错() {
        BlogComments c1 = comment(1L, "好吃");
        c1.setId(11L);
        c1.setUserId(7L);
        doReturn(1L).when(service).count(any(Wrapper.class));
        doReturn(List.of(c1)).when(service).list(any(Wrapper.class));
        when(userFeignClient.getUserByIds(any())).thenThrow(new RuntimeException("user-service down"));

        Result r = service.queryCommentsByBlogId(1L, 1);

        assertTrue(r.getSuccess(), "联查失败不得升级为接口失败");
        CommentVO vo = (CommentVO) ((List<?>) r.getData()).get(0);
        assertEquals(11L, vo.getId());
        assertNull(vo.getNickName());
    }
}
