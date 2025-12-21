package com.hmdp.config;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.Resource;
import java.io.IOException;

@Slf4j
@Configuration
@EnableRabbit
// NOTE RabbitMQ 配置类
//  主要功能是配置连接工厂、消息转换器、交换机、队列及其绑定关系，
//  并设置消息监听器处理优惠券订单消息。
public class RabbitMQConfig {

    @Resource
    private IVoucherOrderService voucherOrderService;

    // RabbitMQ 连接配置
    //NOTE 这里配置了连接 RabbitMQ 所需的主机、端口、用户名、密码和虚拟主机信息。
    //NOTE 使用 CachingConnectionFactory 创建连接工厂
    @Bean
    public ConnectionFactory connectionFactory() {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory();
        connectionFactory.setHost("localhost");
        connectionFactory.setPort(5672);
        connectionFactory.setUsername("guest");
        connectionFactory.setPassword("guest");
        connectionFactory.setVirtualHost("/");
        return connectionFactory;
    }

    // 消息转换器
    // NOTE 使用Jackson库将消息转换为JSON格式，便于在应用程序中处理复杂对象。
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // RabbitTemplate 配置 - 兼容旧版本
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter());

        // 设置 mandatory=true 确保消息可路由
        rabbitTemplate.setMandatory(true);

        // 配置返回回调
        rabbitTemplate.setReturnsCallback(returns -> {
            log.error("消息路由失败，触发 Return Callback");
            log.debug("Exchange: {}", returns.getExchange());
            log.debug("RoutingKey: {}", returns.getRoutingKey());
            log.debug("ReplyCode: {}", returns.getReplyCode());
            log.debug("ReplyText: {}", returns.getReplyText());
            log.debug("Message: {}", new String(returns.getMessage().getBody()));
        });

        // 配置确认回调（可选）
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (ack) {
                log.debug("消息发送成功");
            } else {
                log.error("消息发送失败: {}", cause);
            }
        });

        return rabbitTemplate;
    }

    // 声明交换机
    @Bean
    public DirectExchange directExchange() {
        return new DirectExchange("hmdianping.direct", true, false);
    }

    // 声明队列
    @Bean
    public Queue seckillQueue() {
        // 使用 QueueBuilder 来创建队列，更安全的方式
        return new Queue("direct.seckill.queue", true, false, false);
    }

    // 绑定队列和交换机
    @Bean
    public Binding seckillBinding() {
        return BindingBuilder.bind(seckillQueue())
                .to(directExchange())
                .with("direct.seckill");
    }

    // 消息监听器
    @RabbitListener(queues = "direct.seckill.queue")
    public void receiveMessage(Message message, Channel channel, VoucherOrder voucherOrder) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            // 处理优惠券订单
            voucherOrderService.handleVoucherOrder(voucherOrder);
            // 手动确认消息
            channel.basicAck(deliveryTag, false);
            log.debug("消息处理成功: {}", voucherOrder);
        } catch (Exception e) {
            log.error("消息处理失败: {}", e.getMessage());
            try {
                // 处理失败，拒绝消息并重新入队
                channel.basicNack(deliveryTag, false, true);
            } catch (IOException ex) {
                log.error("消息拒绝失败: {}", ex.getMessage());
            }
        }
    }
}
// NOTE 高频面试题：
// NOTE 1. RabbitMQ 如何保证消息的可靠性传递？
// 答：这里使用了多种机制来保证消息的可靠性传递，包括：
// -生产者确认（Publisher Confirms）：确保消息成功发送到交换机。
// -消息持久化：队列和消息都设置为持久化，防止消息丢失。
// -返回回调（Return Callback）：当消息无法路由到队列时，生产者会收到通知。
// -消费者手动确认（Manual Acknowledgments）：确保消息被成功处理后才从队列中移除。

//NOTE 2. RabbitMQTemplate 中的 mandatory 参数有什么作用？
//答：mandatory 参数用于指定当消息无法路由到任何队列时，是否将消息返回给生产者。
// 如果设置为 true，当消息无法路由时，RabbitMQ 会触发 Return Callback，
// 允许生产者处理未路由的消息；如果设置为 false，消息将被丢弃。

// NOTE 3. 如何处理 RabbitMQ 中的死信队列？
// 答：死信队列（Dead Letter Queue, DLQ）用于存储无法被正常处理的消息。
// 可以通过以下方式处理死信队列：
// -配置死信交换机和死信队列，将无法处理的消息路由

// NOTE 4. RabbitMQ 中的消息确认机制是如何工作的？
// 答：RabbitMQ 提供了两种消息确认机制：
// -生产者确认（Publisher Confirms）：生产者在发送消息后会收到确认，确保消息已成功到达交换机。
// -消费者确认（Consumer Acknowledgments）：消费者在处理完消息后需要发送确认，确保消息已被成功处理。
// 这两种机制共同确保了消息的可靠传递和处理。