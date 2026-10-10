package com.hmdp.rag.pipeline;

import com.hmdp.rag.embedding.IEmbeddingClient;
import com.hmdp.rag.entity.Document;
import com.hmdp.rag.entity.KnowledgeBase;
import com.hmdp.rag.parser.IDocumentParser;
import com.hmdp.rag.repository.DocumentChunkRepository;
import com.hmdp.rag.repository.DocumentMapper;
import com.hmdp.rag.repository.KnowledgeBaseMapper;
import com.hmdp.rag.splitter.ITextSplitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SPEC-08 §5.4（A3）——DocumentPipeline 消费幂等：同一 documentId 重复投递 embedding 只调用 1 次。
 *
 * <p>纯 Mockito 单测，不启 Spring、不连 PgVector。幂等语义由
 * {@link DocumentMapper#casToProcessing(Long)} 的乐观更新 CAS 承担：只有 PENDING 能被抢占，
 * 抢占失败（返回 0）即代表该文档已被其他消费者处理或处理中，直接放弃。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentPipelineIdempotencyTest {

    private static final Long DOC_ID = 42L;
    private static final Long KB_ID = 1L;

    @Mock
    private IEmbeddingClient embeddingClient;
    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private KnowledgeBaseMapper kbMapper;
    @Mock
    private IDocumentParser parser;
    @Mock
    private ITextSplitter splitter;
    @Mock
    private DocumentChunkRepository chunkRepo;

    private DocumentPipeline pipeline;
    private Path tempFile;

    @BeforeEach
    void setUp() throws Exception {
        // DocumentPipeline 由 @RequiredArgsConstructor 生成 6 参构造器
        pipeline = new DocumentPipeline(parser, splitter, embeddingClient, chunkRepo, documentMapper, kbMapper);

        tempFile = Files.createTempFile("rag-pipeline-it", ".txt");
        Files.writeString(tempFile, "hello world", StandardCharsets.UTF_8);

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(KB_ID);
        kb.setChunkSize(500);
        kb.setChunkOverlap(50);
        when(kbMapper.selectById(KB_ID)).thenReturn(kb);

        when(parser.parse(any(), any())).thenReturn("hello world");
        when(splitter.split(any(), anyInt(), anyInt())).thenReturn(List.of("hello world"));
        when(embeddingClient.embed(any())).thenReturn(List.of(new float[]{0.1f, 0.2f}));
    }

    /** 每次返回全新对象：业务体里会 setStatus/setChunkCount，复用同一引用会让测试自己制造假象。 */
    private Document freshDocument(String status) {
        Document doc = new Document();
        doc.setId(DOC_ID);
        doc.setKbId(KB_ID);
        doc.setFilename(new File(tempFile.toString()).getName());
        doc.setFilePath(tempFile.toString());
        doc.setStatus(status);
        return doc;
    }

    @Test
    void 同一documentId重复投递embedding只调用1次() {
        when(documentMapper.selectById(anyLong())).thenAnswer(inv -> freshDocument("PENDING"));
        // 第一次抢占成功、第二次已被别人抢走（status 已被推离 PENDING，CAS 影响 0 行）
        when(documentMapper.casToProcessing(DOC_ID)).thenReturn(1, 0);

        pipeline.process(DOC_ID);
        pipeline.process(DOC_ID);

        verify(embeddingClient, times(1)).embed(any());
    }

    @Test
    void 状态为PROCESSING时抢占失败不生成embedding() {
        when(documentMapper.selectById(anyLong())).thenAnswer(inv -> freshDocument("PROCESSING"));
        when(documentMapper.casToProcessing(DOC_ID)).thenReturn(0);

        pipeline.process(DOC_ID);

        verify(embeddingClient, never()).embed(any());
    }

    @Test
    void 状态为COMPLETED时抢占失败不生成embedding() {
        when(documentMapper.selectById(anyLong())).thenAnswer(inv -> freshDocument("COMPLETED"));
        when(documentMapper.casToProcessing(DOC_ID)).thenReturn(0);

        pipeline.process(DOC_ID);

        verify(embeddingClient, never()).embed(any());
    }

    @Test
    void 状态为FAILED时抢占失败不生成embedding() {
        when(documentMapper.selectById(anyLong())).thenAnswer(inv -> freshDocument("FAILED"));
        when(documentMapper.casToProcessing(DOC_ID)).thenReturn(0);

        pipeline.process(DOC_ID);

        verify(embeddingClient, never()).embed(any());
    }

    @Test
    void 抢占成功时确实走完embed与落库流程() {
        when(documentMapper.selectById(anyLong())).thenAnswer(inv -> freshDocument("PENDING"));
        when(documentMapper.casToProcessing(DOC_ID)).thenReturn(1);

        pipeline.process(DOC_ID);

        verify(documentMapper).casToProcessing(DOC_ID);
        verify(embeddingClient).embed(any());
        verify(chunkRepo).batchInsert(any(), any());
        verify(documentMapper).updateById(any());
    }

    @Test
    void 文档不存在时不生成embedding() {
        when(documentMapper.selectById(anyLong())).thenReturn(null);

        pipeline.process(DOC_ID);

        verify(embeddingClient, never()).embed(any());
    }
}
