package com.biliwind.blog.edge;

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
public class SubscriptionHealthCheck implements HealthCheck {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionHealthCheck.class);

    @Inject
    EntityManager entityManager;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder responseBuilder = HealthCheckResponse.named("PostgreSQL Subscription Health");

        try {
            // Check subscription status
            List<?> stats = entityManager.createNativeQuery("SELECT subname, last_msg_send_time, last_msg_receipt_time, latest_end_lsn FROM pg_stat_subscription").getResultList();

            if (stats.isEmpty()) {
                return responseBuilder.down().withData("status", "No active subscriptions found").build();
            }

            responseBuilder.up().withData("active_subscriptions", stats.size());
            return responseBuilder.build();
        } catch (Exception e) {
            log.error("Failed to check subscription status", e);
            // On edge node, failure to check subscription might mean it's not configured yet
            return responseBuilder.up().withData("warning", "Could not query pg_stat_subscription: " + e.getMessage()).build();
        }
    }
}
