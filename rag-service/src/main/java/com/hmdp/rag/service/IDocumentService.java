package com.hmdp.rag.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.rag.dto.UploadResponse;
import com.hmdp.rag.entity.Document;
import org.springframework.web.multipart.MultipartFile;

public interface IDocumentService {
    UploadResponse upload(MultipartFile file, Long kbId, Long userId);
    Page<Document> listByKb(Long kbId, int pageNum, int pageSize);
    Document getStatus(Long documentId);
    void delete(Long documentId);
}
