package com.hmdp.agent.tool;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 营业时间推导（FR-07 边界：营业状态以数据推导为准，LLM 不得常识推测）
 * 支持 "09:00-22:00" 与多段 "08:00-11:00,13:00-22:00"，支持跨午夜 "20:00-02:00"
 */
public final class OpenHoursParser {

    private OpenHoursParser() {
    }

    public static String status(String openHours, LocalDateTime now) {
        if (openHours == null || openHours.isBlank()) {
            return "未知";
        }
        try {
            LocalTime t = now.toLocalTime();
            boolean anyParseable = false;
            for (String seg : openHours.split("[,，]")) {
                String[] range = seg.trim().split("-");
                if (range.length != 2) {
                    continue;
                }
                LocalTime start;
                LocalTime end;
                try {
                    start = LocalTime.parse(range[0].trim());
                    end = LocalTime.parse(range[1].trim());
                } catch (Exception ignored) {
                    continue; // 单段不可解析 → 跳过该段
                }
                anyParseable = true;
                boolean open = start.isAfter(end)                    // 跨午夜
                        ? (!t.isBefore(start) || !t.isAfter(end))
                        : (!t.isBefore(start) && !t.isAfter(end));
                if (open) {
                    return "营业中";
                }
            }
            return anyParseable ? "已打烊" : "未知";
        } catch (Exception e) {
            return "未知";
        }
    }
}
