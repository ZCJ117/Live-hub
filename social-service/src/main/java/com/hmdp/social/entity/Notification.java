package com.hmdp.social.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 站内信（FR-09 工单通知，PRD 附录 B：social-service 站内信通道） */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_notification")
public class Notification implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    /** TICKET=工单通知（预留扩展） */
    private String type;
    private String title;
    private String content;
    /** 关联业务ID（工单ID） */
    private Long relatedId;
    private LocalDateTime createTime;
}
