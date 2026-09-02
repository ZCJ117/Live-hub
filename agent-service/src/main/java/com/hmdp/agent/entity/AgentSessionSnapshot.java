package com.hmdp.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 会话回放快照（FR-13 T5.3）：静态快照渲染，工具过程不回放 */
@Data
@Accessors(chain = true)
@TableName("agent_session_snapshot")
public class AgentSessionSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private String snapshotJson;

    private LocalDateTime createTime;
}
