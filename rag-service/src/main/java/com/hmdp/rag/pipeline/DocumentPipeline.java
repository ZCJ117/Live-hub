package com.hmdp.rag.pipeline;

import com.hmdp.rag.embedding.GlmEmbeddingClient;
import com.hmdp.rag.embedding.IEmbeddingClient;
import com.hmdp.rag.entity.Document;
import com.hmdp.rag.entity.DocumentChunk;
import com.hmdp.rag.entity.KnowledgeBase;
import com.hmdp.rag.parser.IDocumentParser;
import com.hmdp.rag.repository.DocumentChunkRepository;
import com.hmdp.rag.repository.DocumentMapper;
import com.hmdp.rag.repository.KnowledgeBaseMapper;
import com.hmdp.rag.splitter.ITextSplitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentPipeline {

    private final IDocumentParser parser;
    private final ITextSplitter splitter;
    private final IEmbeddingClient embeddingClient;
    private final DocumentChunkRepository chunkRepo;
    private final DocumentMapper documentMapper;
    private final KnowledgeBaseMapper kbMapper;

    public void process(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            log.error("Document not found: id={}", documentId);
            return;
        }

        try {
            KnowledgeBase kb = kbMapper.selectById(doc.getKbId());
            if (kb == null) {
                failDocument(doc, "知识库不存在");
                return;
            }

            int chunkSize = kb.getChunkSize() != null ? kb.getChunkSize() : 500;
            int overlap = kb.getChunkOverlap() != null ? kb.getChunkOverlap() : 50;

            String text;
            try (InputStream is = new FileInputStream(doc.getFilePath())) {
                text = parser.parse(is, doc.getFilename());
            }
            log.info("Parsed document {}: {} chars", doc.getId(), text.length());

            List<String> chunks = splitter.split(text, chunkSize, overlap);
            log.info("Split into {} chunks", chunks.size());

            List<float[]> embeddings = embeddingClient.embed(chunks);
            log.info("Generated {} embeddings", embeddings.size());

            List<DocumentChunk> chunkEntities = new ArrayList<>();
            List<String> embeddingStrs = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunk chunk = new DocumentChunk();
                chunk.setDocumentId(doc.getId());
                chunk.setKbId(doc.getKbId());
                chunk.setChunkIndex(i);
                chunk.setContent(chunks.get(i));
                chunk.setMetadata("{}");
                chunk.setCreatedAt(LocalDateTime.now());
                chunkEntities.add(chunk);
                embeddingStrs.add(GlmEmbeddingClient.toPgVectorString(embeddings.get(i)));
            }
            chunkRepo.batchInsert(chunkEntities, embeddingStrs);

            doc.setStatus("COMPLETED");
            doc.setChunkCount(chunks.size());
            documentMapper.updateById(doc);
            log.info("Document {} processed: {} chunks stored", doc.getId(), chunks.size());

        } catch (Exception e) {
            log.error("Document processing failed: id={}", documentId, e);
            failDocument(doc, e.getMessage());
        }
    }

    private void failDocument(Document doc, String error) {
        doc.setStatus("FAILED");
        doc.setErrorMsg(error != null ? error.substring(0, Math.min(error.length(), 500)) : "未知错误");
        documentMapper.updateById(doc);
    }
}
