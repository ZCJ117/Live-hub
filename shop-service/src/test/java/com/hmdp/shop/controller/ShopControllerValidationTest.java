package com.hmdp.shop.controller;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.config.SaTokenConfig;
import com.hmdp.entity.Shop;
import com.hmdp.shop.config.ShopValidationExceptionHandler;
import com.hmdp.shop.service.IShopService;
import com.hmdp.shop.service.ShopCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A7：分页参数越界必须返回 HTTP 400（修复前为 500）。
 *
 * <p>本用例覆盖的是**整条链路**：request → Spring MVC 方法参数校验（{@code MethodValidationInterceptor}
 * 代理）→ {@code ConstraintViolationException} → {@link ShopValidationExceptionHandler} → HTTP 400。
 * 因此断言里没有 mock 异常，异常必须由切片真的抛出来。
 *
 * <p>排除 {@link SaTokenConfig}：它对 {@code /**} 强制登录而 {@code /shop/**} 不在白名单，
 * 否则本用例会在参数校验之前先被鉴权拦截。本用例检验的是参数校验链路，不是鉴权。
 *
 * <p>显式声明 {@code classes} 以免切片回落到 {@code ShopApplication} —— 后者的
 * {@code @MapperScan("com.hmdp.shop.mapper")} 会注册需要 {@code SqlSessionFactory} 的 mapper bean，
 * 离线环境下无法装配。{@code MethodValidationPostProcessor} 由 web 切片仍会应用的
 * {@code ValidationAutoConfiguration} 提供，故此处显式注册的控制器 bean 仍会被 {@code @Validated} 代理。
 */
@WebMvcTest(controllers = ShopController.class,
        excludeAutoConfiguration = SaTokenConfig.class,
        properties = {
                "spring.cloud.bootstrap.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.discovery.enabled=false"
        })
@ContextConfiguration(classes = {ShopController.class, ShopValidationExceptionHandler.class})
@Import(ShopValidationExceptionHandler.class)
class ShopControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IShopService shopService;

    @MockBean
    private ShopCacheService shopCacheService;

    @Test
    void 分页参数越界返回400() throws Exception {
        mockMvc.perform(get("/shop/of/type").param("typeId", "1").param("current", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 合法页码不返回400() throws Exception {
        mockMvc.perform(get("/shop/of/type").param("typeId", "1").param("current", "1"))
                .andExpect(status().isOk());
    }

    @Test
    void 按名称查询分页参数越界返回400() throws Exception {
        mockMvc.perform(get("/shop/of/name").param("name", "店").param("current", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 按名称查询合法页码不返回400() throws Exception {
        // queryShopByName 走 IShopService.query() 的链式 API，mock 需返回链式替身，
        // 否则校验通过后会在 page(...) 上 NPE，把"合法页码 200"误判为失败。
        QueryChainWrapper<Shop> chain = mock(QueryChainWrapper.class);
        when(shopService.query()).thenReturn(chain);
        when(chain.like(any(Boolean.class), any(), any())).thenReturn(chain);
        when(chain.page(any())).thenReturn(new Page<>());

        mockMvc.perform(get("/shop/of/name").param("name", "店").param("current", "1"))
                .andExpect(status().isOk());
    }
}
