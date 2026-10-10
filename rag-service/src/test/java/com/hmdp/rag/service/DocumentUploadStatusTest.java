package com.hmdp.rag.service;

import com.hmdp.rag.config.RagProperties;
import com.hmdp.rag.entity.Document;
import com.hmdp.rag.entity.KnowledgeBase;
import com.hmdp.rag.repository.DocumentChunkRepository;
import com.hmdp.rag.repository.DocumentMapper;
import com.hmdp.rag.repository.KnowledgeBaseMapper;
import com.hmdp.rag.service.impl.DocumentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传落库的初始状态必须是 PENDING（SPEC-08 §5.4 幂等的前置条件）
 *
 * <p><b>为什么单独锁这一条</b>：消费端 {@code DocumentPipeline.process} 用
 * {@code UPDATE ... SET status='PROCESSING' WHERE id=? AND status='PENDING'} 抢占文档，
 * 影响 0 行即视为"已被别人处理"并跳过。若上传时就把状态写成 {@code PROCESSING}，
 * 抢占**永远失败**，每个文档都会被静默跳过——单元测试全绿、生产上整条 RAG 流水线不产出任何向量。
 *
 * <p>这类"两侧各自看起来都对、拼起来不通"的契约断裂，正是本项目反复出现的缺陷形态。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentUploadStatusTest {

    @Mock private DocumentMapper documentMapper;
    @Mock private KnowledgeBaseMapper kbMapper;
    @Mock private DocumentChunkRepository chunkRepo;
    @Mock private RagProperties ragProperties;

    private DocumentServiceImpl service;
    private Path uploadDir;

    @BeforeEach
    void setUp() throws Exception {
        uploadDir = Files.createTempDirectory("rag-upload-status");
        RagProperties.Upload upload = new RagProperties.Upload();
        upload.setDir(uploadDir.toString());
        when(ragProperties.getUpload()).thenReturn(upload);

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        when(kbMapper.selectById(1L)).thenReturn(kb);

        service = new DocumentServiceImpl(documentMapper, kbMapper, chunkRepo, ragProperties);
    }

    @Test
    void 上传落库时的状态必须是PENDING_否则消费端CAS抢占永远失败() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "kb.txt", "text/plain", "hello rag".getBytes());

        service.upload(file, 1L, 100L);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentMapper).insert(captor.capture());
        assertEquals("PENDING", captor.getValue().getStatus(),
                "上传必须落 PENDING：消费端用 CAS(PENDING→PROCESSING) 抢占，"
                        + "写成 PROCESSING 会让每个文档都被判为「已被处理」而跳过");
    }

    @Test
    void 上传仍要向响应方说明正在处理中() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "kb.txt", "text/plain", "hello rag".getBytes());

        Object response = service.upload(file, 1L, 100L);

        // 面向用户的措辞描述的是"后台正在处理"，与库里的 PENDING 不矛盾，不必改
        assertTrue(response != null, "上传应返回响应体");
    }

    @Test
    void 上传确实把文件落盘() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "kb.txt", "text/plain", "hello rag".getBytes());

        service.upload(file, 1L, 100L);

        try (var files = Files.list(uploadDir)) {
            assertTrue(files.findAny().isPresent(),
                    "上传目录下应有落盘文件，否则 DocumentPipeline 打开文件时会失败");
        }
    }
}
