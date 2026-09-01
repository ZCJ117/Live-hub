package com.hmdp.rag.mq;

import com.hmdp.rag.pipeline.DocumentPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

//NOTE 1,5 DocumentProcessConsumer 消费 负责接收文档处理消息，并调用 DocumentPipeline 进行处理。

@Component
@RocketMQMessageListener(
        topic = DocumentProcessProducer.TOPIC,
        consumerGroup = "rag-doc-process-consumer-group",
        maxReconsumeTimes = 3
)
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessConsumer implements RocketMQListener<Long> {

    private final DocumentPipeline documentPipeline;

    @Override
    public void onMessage(Long documentId) {
        log.info("Received document process message: documentId={}", documentId);
        try {
            // 调用 DocumentPipeline 处理文档,核心处理
            documentPipeline.process(documentId);
        } catch (Exception e) {
            log.error("Document processing failed: documentId={}", documentId, e);
            throw new RuntimeException("Document processing failed: " + documentId, e);
        }
    }
}
