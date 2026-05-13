package com.biliwind.blog.service.edge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Readiness
@ApplicationScoped
public class ReplicationHealthCheck implements HealthCheck {
    private static final Logger log = LoggerFactory.getLogger(ReplicationHealthCheck.class);

    @Inject
    EntityManager entityManager;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder responseBuilder = HealthCheckResponse.named("PostgreSQL Replication Health");

        try {
            // Check if there are any active replication slots or stats
            List<?> stats = entityManager.createNativeQuery("SELECT usename, application_name, client_addr, state, sync_state FROM pg_stat_replication").getResultList();

            if (stats.isEmpty()) {
                // If no replications, it might be just a standalone node (which is fine but worth noting)
                return responseBuilder.up().withData("status", "No active replicas").build();
            }

            responseBuilder.up().withData("active_replicas", stats.size());
            return responseBuilder.build();
        } catch (Exception e) {
            log.error("Failed to check replication status", e);
            return responseBuilder.down().withData("error", e.getMessage()).build();
        }
    }
}
