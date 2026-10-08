package com.hmdp.social.controller;

import com.hmdp.config.SaTokenConfig;
import com.hmdp.dto.Result;
import com.hmdp.social.service.IBlogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPEC-09 A10/A11：首屏 Feed 不因缺 lastId 报 400；current=0 不导致 500。
 *
 * <p>排除 {@link SaTokenConfig}：它对 {@code /**} 强制登录而这三个查询端点不在白名单，
 * 否则本用例会在参数绑定之前先被鉴权拦截。本用例检验的是参数绑定与响应码，不是鉴权。
 *
 * <p>显式声明 {@code classes} 以免切片回落到 {@code SocialApplication}——后者的
 * {@code @MapperScan("com.hmdp.social.mapper")} 会注册需要 {@code SqlSessionFactory} 的
 * mapper bean，离线环境下无法装配。
 */
@WebMvcTest(controllers = BlogController.class,
        excludeAutoConfiguration = SaTokenConfig.class,
        properties = {
                "spring.cloud.bootstrap.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.discovery.enabled=false"
        })
@ContextConfiguration(classes = {BlogController.class})
class BlogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IBlogService blogService;

    @Test
    void 首屏Feed不带lastId返回200() throws Exception {
        given(blogService.queryBlogOfFollow(any(), anyInt())).willReturn(Result.ok());

        mockMvc.perform(get("/blog/of/follow")).andExpect(status().isOk());
    }

    @Test
    void 首屏Feed带lastId返回200() throws Exception {
        given(blogService.queryBlogOfFollow(any(), anyInt())).willReturn(Result.ok());

        mockMvc.perform(get("/blog/of/follow").param("lastId", "1700000000000"))
                .andExpect(status().isOk());
    }

    @Test
    void 热点查询current为0返回200() throws Exception {
        given(blogService.queryHotBlog(anyInt())).willReturn(Result.ok());

        mockMvc.perform(get("/blog/hot").param("current", "0")).andExpect(status().isOk());
    }

    @Test
    void 用户笔记查询current为0返回200() throws Exception {
        given(blogService.queryBlogByUserId(anyInt(), any())).willReturn(Result.ok());

        mockMvc.perform(get("/blog/of/user").param("current", "0").param("id", "1"))
                .andExpect(status().isOk());
    }
}
