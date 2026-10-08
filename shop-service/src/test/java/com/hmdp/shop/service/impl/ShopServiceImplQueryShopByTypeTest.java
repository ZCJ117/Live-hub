package com.hmdp.shop.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.utils.SystemConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G6 服务层兜底：{@code queryShopByType} 是对外接口，内部调用可绕过 MVC 参数校验。
 * 页码 ≤ 0 时修复前 {@code from = (current - 1) * 5} 为负，{@code Stream.skip(负数)}
 * 会抛 {@link IllegalArgumentException}，在 Controller 之外升级为 500。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShopServiceImplQueryShopByTypeTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private GeoOperations<String, String> geoOperations;

    @InjectMocks
    private ShopServiceImpl shopService;

    private void mockEmptyGeoSearch() {
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        // 空结果集：修复前 list.size()(0) <= from(-5) 为 false，会继续走到 skip(负数)
        GeoResults<RedisGeoCommands.GeoLocation<String>> emptyResults =
                new GeoResults<>(Collections.emptyList());
        when(geoOperations.search(anyString(), any(), any(Distance.class), any())).thenReturn(emptyResults);
    }

    /** 取回本次地理位置检索实际使用的 limit，即服务层算出的 end */
    private long searchLimit() {
        ArgumentCaptor<RedisGeoCommands.GeoSearchCommandArgs> captor =
                ArgumentCaptor.forClass(RedisGeoCommands.GeoSearchCommandArgs.class);
        verify(geoOperations).search(anyString(), any(), any(Distance.class), captor.capture());
        return captor.getValue().getLimit();
    }

    @Test
    void 页码为0时服务层兜底为第一页不再抛异常() {
        mockEmptyGeoSearch();

        Result result = shopService.queryShopByType(1, 0, 116.4, 39.9);

        assertEquals(Collections.emptyList(), result.getData());
        // 兜底后 end = 1 * 5 = 5；修复前 end = 0，limit(0) 抛 IllegalArgumentException
        assertEquals(SystemConstants.DEFAULT_PAGE_SIZE, searchLimit());
    }

    @Test
    void 页码为null时服务层兜底为第一页不再抛异常() {
        mockEmptyGeoSearch();

        assertDoesNotThrow(() -> shopService.queryShopByType(1, null, 116.4, 39.9));

        assertEquals(SystemConstants.DEFAULT_PAGE_SIZE, searchLimit());
    }

    /** 兜底不应改变合法页码的行为：第 2 页仍以 end = 2 * 5 发起检索 */
    @Test
    void 合法页码不被兜底改写() {
        mockEmptyGeoSearch();

        shopService.queryShopByType(1, 2, 116.4, 39.9);

        assertEquals(2 * SystemConstants.DEFAULT_PAGE_SIZE, searchLimit());
    }
}
