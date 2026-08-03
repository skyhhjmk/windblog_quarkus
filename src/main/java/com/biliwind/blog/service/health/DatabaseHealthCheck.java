package com.biliwind.blog.service.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class DatabaseHealthCheck implements HealthCheck {

    @Inject
    EntityManager entityManager;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("database");

        try {
            // 执行最简单的 SQL，能返回结果即说明数据库连通
            Query query = entityManager.createNativeQuery("SELECT 1");
            query.getSingleResult();
            return builder.up().build();
        } catch (Exception e) {
            return builder.down()
                    .withData("error", "数据库连接不可用")
                    .build();
        }
    }
}
