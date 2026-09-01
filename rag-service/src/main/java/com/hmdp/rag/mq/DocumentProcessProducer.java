package com.hmdp.rag.mq;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * 文档处理消息生产者
 *
 * 仅在 RocketMQTemplate Bean 存在时创建。
 * RocketMQ 未启动时，文档上传正常入库但不会触发异步处理。
 *
 * NOTE 1,4 DocumentProcessProducer 负责发送文档处理消息到 RocketMQ，触发异步处理逻辑。
 */
@Component
@ConditionalOnBean(RocketMQTemplate.class)
@Slf4j
public class DocumentProcessProducer {

    public static final String TOPIC = "rag-document-process";

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    public void sendProcessMessage(Long documentId) {
        rocketMQTemplate.convertAndSend(TOPIC, documentId);
        log.info("Sent document process message: documentId={}", documentId);
    }
}
