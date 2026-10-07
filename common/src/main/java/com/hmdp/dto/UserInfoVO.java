package com.hmdp.dto;

import lombok.Data;

/**
 * 用户详情脱敏投影（SPEC-06 §5.4）
 *
 * <p>刻意**不含** {@code credits}（积分）、{@code birthday}（生日）、{@code gender}（性别）——
 * 原实现直接返回 UserInfo 实体，任意登录用户可枚举他人这些隐私字段。
 * {@code UserInfo} 本身不含手机号/邮箱，故无需额外处理。
 */
@Data
public class UserInfoVO {
    private Long userId;
    private String city;
    private String introduce;
    private Integer fans;
    private Integer followee;
    private Boolean level;
}
