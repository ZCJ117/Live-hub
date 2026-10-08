package com.hmdp.shop.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.shop.mapper.ShopTypeMapper;
import com.hmdp.shop.service.impl.ShopTypeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
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

    @InjectMocks
    private ShopTypeServiceImpl shopTypeService;

    @BeforeEach
    void setUp() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Mockito 不会给 ServiceImpl 继承来的 protected baseMapper 注入 @Mock，
        // 这里显式反射注入（断言不放松，注入失败仍然响亮报错）
        Field baseMapperField = ServiceImpl.class.getDeclaredField("baseMapper");
        baseMapperField.setAccessible(true);
        baseMapperField.set(shopTypeService, shopTypeMapper);
        assertNotNull(shopTypeService.getBaseMapper(),
                "@InjectMocks 未注入 ServiceImpl.baseMapper，请改为显式反射注入");
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
        assertTrue(ttl.getValue() <= 60L, "空值 TTL 必须 ≤60 秒，实际 " + ttl.getValue());
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
        assertTrue(ttl.getValue() >= CACHE_TTL_BASE_SECONDS
                        && ttl.getValue() < CACHE_TTL_BASE_SECONDS + CACHE_TTL_JITTER_SECONDS,
                "TTL 越界: " + ttl.getValue());
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
