package com.biliwind.blog.service.health;

import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class RedisHealthCheck implements HealthCheck {

    // 用 Instance 包裹，避免 Redis 不可用时导致整个应用启动失败
    @Inject
    Instance<RedisDataSource> redisDataSourceInstance;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("redis");

        if (redisDataSourceInstance.isUnsatisfied()) {
            return builder.down().withData("error", "Redis DataSource not configured").build();
        }

        try {
            RedisDataSource redisDataSource = redisDataSourceInstance.get();
            // 发送 PING，能拿到响应说明 Redis 连通正常
            String pingResponse = redisDataSource.value(String.class).get("__health_ping__");
            // get 没抛异常就代表 Redis 可达（key 不存在返回 null 也是正常的）
            return builder.up()
                    .withData("connectionOk", true)
                    .build();
        } catch (Exception e) {
            return builder.down()
                    .withData("error", "Redis 连接不可用")
                    .build();
        }
    }
}
