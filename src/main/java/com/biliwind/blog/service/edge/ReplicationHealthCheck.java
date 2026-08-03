package com.biliwind.blog.service.edge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

@Readiness
@ApplicationScoped
public class ReplicationHealthCheck implements HealthCheck {
    private static final Logger log = LoggerFactory.getLogger(ReplicationHealthCheck.class);

    @Inject
    DataSource dataSource;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder responseBuilder = HealthCheckResponse.named("PostgreSQL Replication Health");

        try {
            int activeReplicaCount = countActiveReplicas();

            if (activeReplicaCount == 0) {
                return responseBuilder.up().withData("status", "No active replicas").build();
            }

            responseBuilder.up().withData("active_replicas", activeReplicaCount);
            return responseBuilder.build();
        } catch (Exception e) {
            log.error("Failed to check replication status", e);
            return responseBuilder.down().withData("error", "复制状态检查失败").build();
        }
    }

    private int countActiveReplicas() throws Exception {
        String sql = "SELECT usename, application_name, client_addr, state, sync_state FROM pg_stat_replication";
        int activeReplicaCount = 0;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                activeReplicaCount = activeReplicaCount + 1;
            }
        }

        return activeReplicaCount;
    }
}
