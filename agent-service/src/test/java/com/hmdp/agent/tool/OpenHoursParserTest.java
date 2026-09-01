package com.hmdp.agent.tool;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 营业时间推导单测（FR-07：营业状态以数据推导为准，LLM 禁止常识推测）
 */
class OpenHoursParserTest {

    private LocalDateTime at(int month, int day, int hour, int minute) {
        return LocalDateTime.of(2026, month, day, hour, minute);
    }

    @Test
    void open_during_normal_hours() {
        assertEquals("营业中", OpenHoursParser.status("09:00-22:00", at(9, 1, 12, 0)));
    }

    @Test
    void boundary_inclusive_at_open_and_close() {
        // 边界为闭区间：开门瞬间与打烊瞬间均视为营业中
        assertEquals("营业中", OpenHoursParser.status("09:00-22:00", at(9, 1, 9, 0)));
        assertEquals("营业中", OpenHoursParser.status("09:00-22:00", at(9, 1, 22, 0)));
        assertEquals("已打烊", OpenHoursParser.status("09:00-22:00", at(9, 1, 8, 59)));
        assertEquals("已打烊", OpenHoursParser.status("09:00-22:00", at(9, 1, 22, 1)));
    }

    @Test
    void closed_outside_normal_hours() {
        assertEquals("已打烊", OpenHoursParser.status("09:00-22:00", at(9, 1, 23, 0)));
    }

    @Test
    void cross_midnight_range() {
        assertEquals("营业中", OpenHoursParser.status("20:00-02:00", at(9, 1, 23, 0)));
        assertEquals("营业中", OpenHoursParser.status("20:00-02:00", at(9, 2, 1, 0)));
        assertEquals("已打烊", OpenHoursParser.status("20:00-02:00", at(9, 1, 19, 0)));
    }

    @Test
    void multi_segment_hours() {
        assertEquals("营业中", OpenHoursParser.status("08:00-11:00,13:00-22:00", at(9, 1, 14, 0)));
        assertEquals("已打烊", OpenHoursParser.status("08:00-11:00,13:00-22:00", at(9, 1, 12, 30)));
    }

    @Test
    void blank_or_invalid_is_unknown() {
        assertEquals("未知", OpenHoursParser.status(null, at(9, 1, 12, 0)));
        assertEquals("未知", OpenHoursParser.status("", at(9, 1, 12, 0)));
        assertEquals("未知", OpenHoursParser.status("随便写的", at(9, 1, 12, 0)));
    }
}
