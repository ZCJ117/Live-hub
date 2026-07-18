package com.hmdp.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("rag_document")
public class Document {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long kbId;
    private String filename;
    private String fileType;
    private Long fileSize;
    private String filePath;
    private String status;
    private Integer chunkCount;
    private String errorMsg;
    private Long uploadedBy;
    private LocalDateTime createdAt;
}
