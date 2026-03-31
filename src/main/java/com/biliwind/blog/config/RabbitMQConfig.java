package com.biliwind.blog.config;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

/**
 * RabbitMQ 配置类，负责声明死信交换机和队列
 */
@ApplicationScoped
@Startup
public class RabbitMQConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQConfig.class);

    @ConfigProperty(name = "rabbitmq-host", defaultValue = "localhost")
    String rabbitmqHost;

    @ConfigProperty(name = "rabbitmq-port", defaultValue = "5672")
    int rabbitmqPort;

    @ConfigProperty(name = "rabbitmq-username", defaultValue = "guest")
    String rabbitmqUsername;

    @ConfigProperty(name = "rabbitmq-password", defaultValue = "guest")
    String rabbitmqPassword;

    // 死信交换机名称
    private static final String DLX_EXCHANGE_NAME = "ai-tasks-dlx";
    
    // 死信队列名称
    private static final String DLX_QUEUE_NAME = "ai-summary-dead-letter";
    
    // 死信队列路由键
    private static final String DLX_ROUTING_KEY = "summary-dead";

    @PostConstruct
    void init() {
        setupDeadLetterQueue();
    }

    /**
     * 设置死信队列和死信交换机
     */
    private void setupDeadLetterQueue() {
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(rabbitmqHost);
            factory.setPort(rabbitmqPort);
            factory.setUsername(rabbitmqUsername);
            factory.setPassword(rabbitmqPassword);

            Connection connection = factory.newConnection();
            Channel channel = connection.createChannel();

            // 声明死信交换机（如果已存在且类型不同，会抛出异常）
            try {
                channel.exchangeDeclare(DLX_EXCHANGE_NAME, "direct", true);
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("PRECONDITION_FAILED")) {
                    log.warn("死信交换机 {} 已存在且类型不匹配。请手动删除该交换机后重启应用，或忽略此警告（如果不使用死信功能）。错误信息：{}", 
                            DLX_EXCHANGE_NAME, e.getMessage());
                } else {
                    throw e;
                }
            }

            // 声明死信队列
            try {
                channel.queueDeclare(DLX_QUEUE_NAME, true, false, false, null);
            } catch (Exception e) {
                log.warn("死信队列 {} 可能已存在，继续执行。错误信息：{}", DLX_QUEUE_NAME, e.getMessage());
            }

            // 绑定死信队列到死信交换机
            try {
                channel.queueBind(DLX_QUEUE_NAME, DLX_EXCHANGE_NAME, DLX_ROUTING_KEY);
            } catch (Exception e) {
                log.warn("死信队列绑定可能已存在，继续执行。错误信息：{}", e.getMessage());
            }

            log.info("RabbitMQ 死信队列设置完成：交换机={}, 队列={}, 路由键={}", 
                    DLX_EXCHANGE_NAME, DLX_QUEUE_NAME, DLX_ROUTING_KEY);

            channel.close();
            connection.close();
        } catch (Exception e) {
            log.error("RabbitMQ 死信队列设置失败", e);
            throw new RuntimeException("RabbitMQ 死信队列设置失败：" + e.getMessage(), e);
        }
    }
}
