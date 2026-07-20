package com.hmdp.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.rag.config.RagProperties;
import com.hmdp.rag.dto.UploadResponse;
import com.hmdp.rag.entity.Document;
import com.hmdp.rag.entity.KnowledgeBase;
import com.hmdp.exception.BusinessException;
import com.hmdp.rag.mq.DocumentProcessProducer;
import com.hmdp.rag.repository.DocumentChunkRepository;
import com.hmdp.rag.repository.DocumentMapper;
import com.hmdp.rag.repository.KnowledgeBaseMapper;
import com.hmdp.rag.service.IDocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Slf4j
public class DocumentServiceImpl implements IDocumentService {

    private final DocumentMapper documentMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final DocumentChunkRepository chunkRepo;
    private final RagProperties ragProperties;

    @Autowired(required = false)
    private DocumentProcessProducer producer;

    public DocumentServiceImpl(DocumentMapper documentMapper,
                                KnowledgeBaseMapper kbMapper,
                                DocumentChunkRepository chunkRepo,
                                RagProperties ragProperties) {
        this.documentMapper = documentMapper;
        this.kbMapper = kbMapper;
        this.chunkRepo = chunkRepo;
        this.ragProperties = ragProperties;
    }

    @Override
    public UploadResponse upload(MultipartFile file, Long kbId, Long userId) {
        KnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb == null) {
            throw new BusinessException("知识库不存在");
        }

        String originalName = file.getOriginalFilename();
        String fileType = getFileType(originalName);
        String storedName = UUID.randomUUID() + "_" + originalName;

        Path uploadDir = Paths.get(ragProperties.getUpload().getDir());
        try {
            Files.createDirectories(uploadDir);
            Path filePath = uploadDir.resolve(storedName);
            file.transferTo(filePath);

            Document doc = new Document();
            doc.setKbId(kbId);
            doc.setFilename(originalName);
            doc.setFileType(fileType);
            doc.setFileSize(file.getSize());
            doc.setFilePath(filePath.toString());
            doc.setStatus("PROCESSING");
            doc.setUploadedBy(userId);
            doc.setCreatedAt(LocalDateTime.now());
            documentMapper.insert(doc);

            if (producer != null) {
                producer.sendProcessMessage(doc.getId());
            } else {
                log.warn("RocketMQ unavailable, document {} stays in PROCESSING state", doc.getId());
            }

            log.info("Document uploaded: id={}, name={}, kbId={}", doc.getId(), originalName, kbId);
            return new UploadResponse(doc.getId(), "PROCESSING", "文档已上传，正在处理中");
        } catch (IOException e) {
            log.error("Failed to save uploaded file: {}", originalName, e);
            throw new BusinessException("文件保存失败: " + e.getMessage());
        }
    }

    @Override
    public Page<Document> listByKb(Long kbId, int pageNum, int pageSize) {
        Page<Document> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Document::getKbId, kbId)
                .orderByDesc(Document::getCreatedAt);
        return documentMapper.selectPage(page, wrapper);
    }

    @Override
    public Document getStatus(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw new BusinessException("文档不存在");
        }
        return doc;
    }

    @Override
    public void delete(Long documentId) {
        Document doc = getStatus(documentId);
        chunkRepo.deleteByDocumentId(documentId);
        documentMapper.deleteById(documentId);
        try {
            Files.deleteIfExists(Paths.get(doc.getFilePath()));
        } catch (IOException e) {
            log.warn("Failed to delete file: {}", doc.getFilePath(), e);
        }
        log.info("Document deleted: id={}", documentId);
    }

    private String getFileType(String filename) {
        if (filename == null) return "unknown";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".docx")) return "docx";
        if (lower.endsWith(".doc")) return "doc";
        if (lower.endsWith(".xlsx")) return "xlsx";
        if (lower.endsWith(".xls")) return "xls";
        if (lower.endsWith(".txt")) return "txt";
        if (lower.endsWith(".md")) return "md";
        return "unknown";
    }
}
