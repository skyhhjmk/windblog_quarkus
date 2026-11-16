package com.biliwind.blog.service;

import com.biliwind.blog.model.dto.ConfigChangedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.pubsub.PubSubCommands;
import io.quarkus.runtime.Startup;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.RedisConnection;
import io.vertx.mutiny.redis.client.Request;
import io.vertx.mutiny.redis.client.Response;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * Propagates committed configuration changes to the L1 cache of every
 * WindBlog node. The normal Redis value cache remains the shared source of
 * truth; Pub/Sub only removes the per-process stale-read window.
 */
@ApplicationScoped
@Startup
public class ConfigChangeBroadcastService {

    private static final Logger LOG = Logger.getLogger(ConfigChangeBroadcastService.class);
    private static final String CHANNEL = "windblog:config:changed";

    @Inject RedisDataSource redisDataSource;
    @Inject Redis redisClient;
    @Inject ObjectMapper objectMapper;
    @Inject ConfigManager configManager;

    private final String nodeId = UUID.randomUUID().toString();
    private PubSubCommands<String> pubSub;
    private volatile RedisConnection subscriptionConnection;

    @PostConstruct
    void subscribe() {
        pubSub = redisDataSource.pubsub(String.class);
        // Register the push handler on the connection before sending SUBSCRIBE so
        // the initial [subscribe, channel, 1] frame is never dropped by Vert.x.
        redisClient.connect()
                .chain(connection -> {
                    subscriptionConnection = connection;
                    connection.handler(this::handleRedisPush);
                    connection.endHandler(() -> LOG.debugf("跨节点配置通知订阅连接已关闭: %s", CHANNEL));
                    connection.exceptionHandler(failure -> LOG.errorf(failure, "跨节点配置通知订阅异常"));
                    return connection.send(Request.cmd(Command.SUBSCRIBE).arg(CHANNEL))
                            .onFailure().invoke(failure -> closeSubscriptionConnection(connection));
                })
                .subscribe().with(
                        ignored -> LOG.infof("已订阅跨节点配置通知频道: %s", CHANNEL),
                        failure -> LOG.warnf(failure, "无法订阅跨节点配置通知；本节点将继续使用 Redis/数据库回退"));
    }

    @PreDestroy
    void unsubscribe() {
        RedisConnection connection = subscriptionConnection;
        subscriptionConnection = null;
        if (connection != null) {
            connection.closeAndForget();
        }
    }

    private void closeSubscriptionConnection(RedisConnection connection) {
        if (subscriptionConnection == connection) {
            subscriptionConnection = null;
        }
        connection.closeAndForget();
    }

    private void handleRedisPush(Response response) {
        if (response == null || response.size() < 3) {
            return;
        }
        String type = response.get(0).toString();
        if ("message".equalsIgnoreCase(type)) {
            applyRemoteChange(response.get(2).toString());
        }
    }

    /** Publishes only after the database transaction that changed the setting commits. */
    void broadcastCommittedChange(@Observes(during = TransactionPhase.AFTER_SUCCESS) ConfigChangedEvent event) {
        if (pubSub == null) return;
        try {
            ObjectNode message = objectMapper.createObjectNode();
            message.put("source", nodeId);
            message.put("key", event.key);
            message.set("value", event.newValue);
            pubSub.publish(CHANNEL, objectMapper.writeValueAsString(message));
        } catch (Exception failure) {
            LOG.warnf(failure, "发布跨节点配置变更失败: %s", event.key);
        }
    }

    private void applyRemoteChange(String payload) {
        try {
            JsonNode message = objectMapper.readTree(payload);
            if (nodeId.equals(message.path("source").asText()) || !message.hasNonNull("key")) return;
            configManager.applyRemoteConfigChange(message.get("key").asText(), message.get("value"));
        } catch (Exception failure) {
            LOG.warnf(failure, "忽略无法解析的跨节点配置通知");
        }
    }
}
