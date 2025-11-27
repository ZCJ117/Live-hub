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

import javax.annotation.Resource;
import java.io.IOException;

@Slf4j
@Configuration
@EnableRabbit
public class RabbitMQConfig {

    @Resource
    private IVoucherOrderService voucherOrderService;

    // RabbitMQ 连接配置
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

        // 兼容旧版本的返回回调配置
        rabbitTemplate.setReturnCallback((message, replyCode, replyText, exchange, routingKey) -> {
            log.error("消息路由失败，触发 Return Callback");
            log.debug("Exchange: {}", exchange);
            log.debug("RoutingKey: {}", routingKey);
            log.debug("ReplyCode: {}", replyCode);
            log.debug("ReplyText: {}", replyText);
            log.debug("Message: {}", new String(message.getBody()));
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