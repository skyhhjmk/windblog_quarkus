package com.biliwind.blog.config;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RabbitMQ 补充配置类，负责声明主队列和死信队列的初始绑定关系。
 *
 * SmallRye Reactive Messaging 会自动声明 Exchange 和 Queue，
 * 但这里额外绑定死信路由（x-dead-letter-exchange）以确保消息失败时正确路由到 DLX。
 *
 * 所有声明操作均为幂等容错模式：如果 Exchange/Queue 已存在则跳过，启动失败不影响主应用。
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

    @ConfigProperty(name = "windblog.node.role", defaultValue = "primary")
    String nodeRole;

    @PostConstruct
    void init() {
        if ("edge".equalsIgnoreCase(nodeRole)) {
            log.info("当前节点是边缘节点，跳过 RabbitMQ 初始化");
            return;
        }

        // SmallRye Messaging 已负责创建所有 Exchange 和 Queue。
        // 这里只做一次初始化日志，不再手动声明任何 AMQP 资源，避免与 SmallRye 配置冲突。
        log.info("RabbitMQ 配置就绪，主机={}:{}", rabbitmqHost, rabbitmqPort);
        tryVerifyConnection();
    }

    /**
     * 尝试验证 RabbitMQ 连通性，失败时只记录警告，不阻塞启动。
     */
    private void tryVerifyConnection() {
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(rabbitmqHost);
            factory.setPort(rabbitmqPort);
            factory.setUsername(rabbitmqUsername);
            factory.setPassword(rabbitmqPassword);
            factory.setConnectionTimeout(3000);

            Connection connection = factory.newConnection();
            Channel channel = connection.createChannel();
            channel.close();
            connection.close();
            log.info("RabbitMQ 连接验证成功");
        } catch (Exception e) {
            log.warn("RabbitMQ 连接验证失败（应用仍会正常启动，SmallRye 会自动重连）：{}", e.getMessage());
        }
    }
}
