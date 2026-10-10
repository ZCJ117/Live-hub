package com.hmdp.voucher.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.voucher.service.impl.VoucherServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 活跃秒杀券口径（SPEC-14 P0-3 / A2 契约）
 *
 * <p>入口的时间窗判定必须与对账侧 {@code listActiveSeckillVoucherIds} 同口径，
 * 否则会出现「入口认为活跃、对账认为不活跃」的分裂。
 * 本类把该口径锁成 SQL 文本断言：活跃 ⇔ begin_time &lt;= now &lt;= end_time（闭区间）。
 */
@ExtendWith(MockitoExtension.class)
class SeckillWindowActiveContractTest {

    static {
        // 纯 Mockito 单测无 MyBatis 上下文，lambda wrapper 的列名解析依赖 TableInfoHelper
        // 缓存（平时由 MyBatis 扫描实体填充），必须手动预热，否则抛 can not find lambda cache
        TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new MybatisConfiguration(), ""),
                SeckillVoucher.class);
    }

    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private ISeckillVoucherService seckillVoucherService;
    @InjectMocks private VoucherServiceImpl voucherService;

    @Test
    @SuppressWarnings("unchecked")
    void 活跃券口径是闭区间_begin小于等于now且end大于等于now() {
        when(seckillVoucherService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        voucherService.listActiveSeckillVoucherIds();

        ArgumentCaptor<LambdaQueryWrapper<SeckillVoucher>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(seckillVoucherService).list(captor.capture());
        String sql = captor.getValue().getSqlSegment();

        assertTrue(sql.contains("begin_time <= "),
                "开始边界必须是 <=（闭区间），实际 SQL: " + sql);
        assertTrue(sql.contains("end_time >= "),
                "结束边界必须是 >=（闭区间），实际 SQL: " + sql);
    }
}
