package com.hmdp.agent.flow;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/** 投诉要素草稿（Redis JSON，TTL 对齐记忆 30min） */
@Data
public class ComplaintDraft {

    private int rounds;
    /** ORDER / VOUCHER / MERCHANT_SERVICE / ACCOUNT_SECURITY / OTHER（null=未定） */
    private String category;
    /** 涉及对象 refs：orderId/voucherId/shopId（可空） */
    private Map<String, Object> refs = new HashMap<>();
    /** 发生时间（自由文本） */
    private String time;
    /** 用户诉求（原话或规整） */
    private String demand;
}
