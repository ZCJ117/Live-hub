package com.hmdp.rag.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessProducer {

    public static final String TOPIC = "rag-document-process";

    private final RocketMQTemplate rocketMQTemplate;

    public void sendProcessMessage(Long documentId) {
        rocketMQTemplate.convertAndSend(TOPIC, documentId);
        log.info("Sent document process message: documentId={}", documentId);
    }
}
