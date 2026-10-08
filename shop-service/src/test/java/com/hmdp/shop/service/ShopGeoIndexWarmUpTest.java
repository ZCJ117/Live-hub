package com.hmdp.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.Shop;
import com.hmdp.shop.mapper.ShopMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

import static com.hmdp.shop.service.ShopGeoIndexWarmUp.PAGE_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopGeoIndexWarmUpTest {

    /**
     * 分页边界与分批落库：满页之后不得停在满页上（那会重复读同一段），必须再查一次
     * 才收尾；每批各自写一次 Redis，而不是攒到最后一次性写入（峰值内存收敛为一批）。
     */
    @Test
    void 按批分页且每批落Redis() {
        ShopMapper shopMapper = mock(ShopMapper.class);
        // 满页 → 短页 → 空页：满页不算收尾，短页走到批次末尾才 break，循环不会读第三次
        when(shopMapper.selectList(any())).thenReturn(fullPage(), shortPage(), List.of());

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        GeoOperations<String, String> geoOperations = mock(GeoOperations.class);
        when(redis.opsForGeo()).thenReturn(geoOperations);

        ShopGeoIndexWarmUp warmUp = new ShopGeoIndexWarmUp(shopMapper, redis);
        try {
            warmUp.warmUpGeoIndex();

            // 恰好两次：满页触发下一轮，短页当轮收尾
            verify(shopMapper, times(2)).selectList(any());
            // 两批各落一次 Redis —— 证明是分批 flush 而非单次收尾写入
            verify(geoOperations, times(2)).add(eq("shop:geo:1"), anyList());
        } finally {
            warmUp.shutdown();
        }
    }

    /**
     * 游标推进：某行被空值守卫跳过时，游标仍必须前进到该行 id；否则下一批会重新拉到
     * 同一段数据，整页被跳过时更会退化成启动期死循环。
     *
     * <p>mock 无法复现真实死锁（返回值与 wrapper 无关），因此做结构性断言：捕获每次
     * {@code selectList} 收到的 wrapper，断言其 {@code id > ?} 绑定值等于上一页最后一行
     * 的 id——而那一行正是被守卫跳过的行。若把 {@code lastId = shop.getId()} 放回守卫
     * 之内，第二次绑定值会停在上一页最后一个<i>有效</i>行上，断言随即失败。
     */
    @Test
    void 游标跨过被跳过的行() {
        ShopMapper shopMapper = mock(ShopMapper.class);
        // 满页，最后一行的 x 为 null：整页除它之外都有效，游标却必须推进到它
        when(shopMapper.selectList(any())).thenReturn(pageEndingInSkippedRow(), List.of());

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        GeoOperations<String, String> geoOperations = mock(GeoOperations.class);
        when(redis.opsForGeo()).thenReturn(geoOperations);

        ShopGeoIndexWarmUp warmUp = new ShopGeoIndexWarmUp(shopMapper, redis);
        try {
            warmUp.warmUpGeoIndex();

            ArgumentCaptor<QueryWrapper<Shop>> captor = wrapperCaptor();
            verify(shopMapper, times(2)).selectList(captor.capture());
            List<QueryWrapper<Shop>> wrappers = captor.getAllValues();

            assertEquals(0L, cursorOf(wrappers.get(0)), "首批应从 id > 0 开始");
            assertEquals(SKIPPED_ID, cursorOf(wrappers.get(1)),
                    "游标必须落在被跳过的最后一行上，否则会重复读取同一页");
            assertTrue(cursorOf(wrappers.get(1)) > PAGE_SIZE - 1L,
                    "游标必须越过被守卫跳过的行，否则会被守卫卡在上一页内");
            // 被跳过的行不写入 GEO
            verify(geoOperations).add(eq("shop:geo:1"), anyList());
        } finally {
            warmUp.shutdown();
        }
    }

    /** 被空值守卫跳过的行位于整页末尾，因此游标必须越过它才谈得上收敛。 */
    private static final long SKIPPED_ID = PAGE_SIZE;

    /**
     * 取出 wrapper 上 {@code gt("id", …)} 绑定的游标值。
     *
     * <p>必须先调 {@code getSqlSegment()}：MyBatis-Plus 只在 {@code formatParam} 里写
     * {@code paramNameValuePairs}，而该方法由 {@code getSqlSegment()} 惰性触发——构造完
     * 就取 map 只会拿到空表，从而让断言退化成永不失败的比较。绑定的实参按 wrapper 自身
     * 数量计，{@code MPGENVAL1} 即 gt 的值。
     */
    private static long cursorOf(QueryWrapper<Shop> wrapper) {
        wrapper.getSqlSegment();
        return (Long) wrapper.getParamNameValuePairs().get("MPGENVAL1");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<QueryWrapper<Shop>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(QueryWrapper.class);
    }

    private static Shop shop(long id, long typeId, Double x, Double y) {
        return new Shop().setId(id).setTypeId(typeId).setX(x).setY(y);
    }

    /** 恰好 PAGE_SIZE 条的满页，全部为有效坐标。 */
    private static List<Shop> fullPage() {
        List<Shop> page = new ArrayList<>(PAGE_SIZE);
        for (int i = 1; i <= PAGE_SIZE; i++) {
            page.add(shop(i, 1L, 1.0 + i * 1e-3, 2.0 + i * 1e-3));
        }
        return page;
    }

    /** 恰好 PAGE_SIZE 条的满页，仅最后一行坐标为空——它会被守卫跳过。 */
    private static List<Shop> pageEndingInSkippedRow() {
        List<Shop> page = fullPage();
        page.set(PAGE_SIZE - 1, shop(SKIPPED_ID, 1L, null, 2.0));
        return page;
    }

    /** 短页：走到批次末尾即收尾。 */
    private static List<Shop> shortPage() {
        return List.of(shop(PAGE_SIZE + 1L, 1L, 3.0, 4.0));
    }
}
