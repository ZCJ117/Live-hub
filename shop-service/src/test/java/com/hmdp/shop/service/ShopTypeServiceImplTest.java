package com.hmdp.shop.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.cache.CacheInvalidationPublisher;
import com.hmdp.cache.LocalCacheRegistry;
import com.hmdp.cache.MultiLevelCacheFactory;
import com.hmdp.cache.MultiLevelCacheProperties;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.shop.mapper.ShopTypeMapper;
import com.hmdp.shop.service.impl.ShopTypeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hmdp.utils.RedisConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShopTypeServiceImplTest {

    @Mock
    private ShopTypeMapper shopTypeMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private CacheInvalidationPublisher publisher;

    private ShopTypeServiceImpl shopTypeService;

    @BeforeEach
    void setUp() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        MultiLevelCacheProperties properties = new MultiLevelCacheProperties();
        properties.setL1MaxSize(1000);
        properties.setL1Ttl(Duration.ofSeconds(10));
        MultiLevelCacheFactory factory = new MultiLevelCacheFactory(
                redisTemplate, publisher, new LocalCacheRegistry(), properties);
        shopTypeService = new ShopTypeServiceImpl(factory);
        // Mockito 不会给 ServiceImpl 继承来的 protected baseMapper 注入 @Mock，
        // 这里显式反射注入（断言不放松，注入失败仍然响亮报错）
        Field baseMapperField = ServiceImpl.class.getDeclaredField("baseMapper");
        baseMapperField.setAccessible(true);
        baseMapperField.set(shopTypeService, shopTypeMapper);
        assertNotNull(shopTypeService.getBaseMapper(),
                "显式反射注入 ServiceImpl.baseMapper 失败");
    }

    /** A6：DB 为空时连续 10 次查询只应穿透一次 */
    @Test
    void 空数据时连续查询只打一次数据库() {
        AtomicBoolean markerWritten = new AtomicBoolean(false);
        // 首次未命中返回 null；写入空值标记后，后续请求命中 ""
        when(valueOperations.get(SHOP_LIST_KEY)).thenAnswer(inv -> markerWritten.get() ? "" : null);
        when(shopTypeMapper.selectList(any())).thenReturn(Collections.emptyList());
        doAnswer(inv -> {
            markerWritten.set(true);
            return null;
        }).when(valueOperations).set(eq(SHOP_LIST_KEY), eq(""), anyLong(), eq(TimeUnit.SECONDS));

        for (int i = 0; i < 10; i++) {
            Result result = shopTypeService.queryList();
            assertFalse(result.getSuccess());
        }

        verify(shopTypeMapper, times(1)).selectList(any());
        ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
        verify(valueOperations, times(1)).set(eq(SHOP_LIST_KEY), eq(""), ttl.capture(), eq(TimeUnit.SECONDS));
        // 字面量边界是刻意为之：60 是 SPEC-05 冻结的负缓存窗口契约，不引用 CACHE_NULL_TTL
        assertEquals(60L, ttl.getValue(), "空值标记 TTL 必须恰好 60 秒");
    }

    /** G7：有数据时写入的 TTL 必须带抖动且在区间内 */
    @Test
    void 有数据时写入带抖动的TTL() {
        when(valueOperations.get(SHOP_LIST_KEY)).thenReturn(null);
        List<ShopType> types = List.of(new ShopType().setId(1L).setName("美食"));
        when(shopTypeMapper.selectList(any())).thenReturn(types);

        Result result = shopTypeService.queryList();

        assertTrue(result.getSuccess());
        ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
        verify(valueOperations).set(eq(SHOP_LIST_KEY), anyString(), ttl.capture(), eq(TimeUnit.SECONDS));
        assertTrue(ttl.getValue() >= 1800L && ttl.getValue() < 2100L, "TTL 越界: " + ttl.getValue());
    }

    /** 空值标记写入失败（Redis 抖动）时仍须优雅返回失败，不得把异常抛给调用方 */
    @Test
    void 空值标记写入失败时仍返回失败结果() {
        when(valueOperations.get(SHOP_LIST_KEY)).thenReturn(null);
        when(shopTypeMapper.selectList(any())).thenReturn(Collections.emptyList());
        doThrow(new RuntimeException("Redis 连接失败"))
                .when(valueOperations).set(eq(SHOP_LIST_KEY), eq(""), anyLong(), eq(TimeUnit.SECONDS));

        Result result = assertDoesNotThrow(() -> shopTypeService.queryList(),
                "空值标记写入失败不应把异常抛给调用方");

        assertFalse(result.getSuccess());
        assertEquals("列表信息不存在", result.getErrorMsg());
        verify(shopTypeMapper, times(1)).selectList(any());
    }

    /** 命中缓存时不查库 */
    @Test
    void 命中缓存不查库() {
        when(valueOperations.get(SHOP_LIST_KEY))
                .thenReturn("[{\"id\":1,\"name\":\"美食\"}]");

        Result result = shopTypeService.queryList();

        assertTrue(result.getSuccess());
        verifyNoInteractions(shopTypeMapper);
    }
}
