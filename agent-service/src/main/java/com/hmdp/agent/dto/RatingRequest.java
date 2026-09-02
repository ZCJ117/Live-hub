package com.hmdp.agent.dto;

import lombok.Data;

import java.util.List;

/** 会话评价请求（FR-12 T5.1）：满意/不满意 + 不满意多选标签 */
@Data
public class RatingRequest {

    /** 5=满意，1=不满意（PRD 6.3 口径 rating≥4 计满意；不支持中间档） */
    private Integer score;

    /** 不满意原因标签（多选，白名单：没解决问题/答非所问/操作太复杂/其他） */
    private List<String> tags;
}
