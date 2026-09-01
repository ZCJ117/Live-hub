package com.hmdp.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 业务埋点事件表（D1.8 埋点字典，服务端直写）
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("track_event")
public class TrackEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** m5_session_start / m5_first_token / m5_tool_call 等 */
    private String eventName;

    private Long sessionId;
    private Long userId;

    /** 事件属性 JSON */
    private String propsJson;

    /** 服务端时间（口径基准） */
    private LocalDateTime serverTime;
}
