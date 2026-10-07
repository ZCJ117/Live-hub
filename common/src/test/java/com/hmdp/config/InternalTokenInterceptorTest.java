package com.hmdp.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class InternalTokenInterceptorTest {

    @Test
    void 密钥正确时放行() throws Exception {
        InternalTokenInterceptor interceptor = new InternalTokenInterceptor("secret");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Internal-Token", "secret");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(req, resp, new Object()));
        assertEquals(200, resp.getStatus());
    }

    @Test
    void 密钥缺失时返回401且不放行() throws Exception {
        InternalTokenInterceptor interceptor = new InternalTokenInterceptor("secret");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(new MockHttpServletRequest(), resp, new Object()));
        assertEquals(401, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("内部接口拒绝访问"));
    }

    @Test
    void 密钥错误时返回401() throws Exception {
        InternalTokenInterceptor interceptor = new InternalTokenInterceptor("secret");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Internal-Token", "wrong");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(req, resp, new Object()));
        assertEquals(401, resp.getStatus());
    }

    @Test
    void 服务端未配置密钥时不放行_安全默认() throws Exception {
        InternalTokenInterceptor interceptor = new InternalTokenInterceptor("");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Internal-Token", "");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(req, resp, new Object()));
        assertEquals(401, resp.getStatus());
    }
}
