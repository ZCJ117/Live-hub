package com.hmdp.order.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.order.dto.OrderQueryVO;
import com.hmdp.order.feign.VoucherFeignClient;
import com.hmdp.order.mapper.VoucherOrderMapper;
import com.hmdp.order.service.impl.VoucherOrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SPEC-07 A9：{@code queryMyOrders} 每页只做**一次**批量券查询。
 *
 * <p>契约测试只证明两侧路径能对上，证明不了"一次批量"而非"逐条 N+1"——
 * 本类把它变成自动化断言。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QueryMyOrdersBatchTest {

    @Mock private VoucherOrderMapper voucherOrderMapper;
    @Mock private VoucherFeignClient voucherFeignClient;
    @InjectMocks private VoucherOrderServiceImpl service;

    @BeforeEach
    void stubPage() {
        // ServiceImpl 的 baseMapper 由 MyBatis-Plus 在容器内注入，纯 Mockito 环境下为 null，
        // 需手工回填——否则 count()/list() 走 baseMapper 时 NPE（不改生产代码）
        ReflectionTestUtils.setField(service, "baseMapper", voucherOrderMapper);

        // 3 条订单，共享 2 个 voucherId（1、2）——N+1 实现会是 3 次调用
        when(voucherOrderMapper.selectCount(any())).thenReturn(3L);
        when(voucherOrderMapper.selectList(any()))
                .thenReturn(List.of(order(9001L, 1L), order(9002L, 2L), order(9003L, 1L)));
        when(voucherFeignClient.getVouchersByIds(any()))
                .thenReturn(Result.ok(List.of(voucherMap(1L, "券A"), voucherMap(2L, "券B"))));
    }

    @Test
    void 分页查询每页只发一次批量券请求_且不再逐条调用() {
        Result r = service.queryMyOrders(7L, null, null, null, 1, 5);

        assertTrue(r.getSuccess());
        assertEquals(3L, r.getTotal());
        @SuppressWarnings("unchecked")
        List<OrderQueryVO> vos = (List<OrderQueryVO>) r.getData();
        assertEquals(3, vos.size());
        assertEquals("券A", vos.get(0).getVoucherTitle());

        // A9 核心：去重后一次批量（voucherIds=[1,2]），而不是每条订单一次。
        // 单发方法 getVoucherById 已按 SPEC-07 从契约中删除，故逐条调用在编译期即不可表达；
        // 这里补一条总调用次数断言，锁死"每页恰好一次远程调用"。
        verify(voucherFeignClient, times(1)).getVouchersByIds(List.of(1L, 2L));
        verifyNoMoreInteractions(voucherFeignClient);
    }

    private static VoucherOrder order(Long id, Long voucherId) {
        VoucherOrder o = new VoucherOrder();
        o.setId(id);
        o.setUserId(7L);
        o.setVoucherId(voucherId);
        o.setStatus(2);
        return o;
    }

    private static Map<String, Object> voucherMap(Long id, String title) {
        return Map.of("id", id, "title", title, "payValue", 100L, "actualValue", 1000L);
    }
}
