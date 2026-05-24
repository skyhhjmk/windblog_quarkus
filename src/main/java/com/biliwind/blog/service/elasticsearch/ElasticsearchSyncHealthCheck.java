package com.biliwind.blog.service.elasticsearch;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class ElasticsearchSyncHealthCheck implements HealthCheck {

    @Inject
    ElasticsearchConnectionManager connectionManager;

    @Inject
    ElasticsearchPostSearchService postSearchService;
    @Inject
    com.biliwind.blog.service.edge.NodeRoleService nodeRoleService;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("elasticsearch-sync");
        if (nodeRoleService.isEdgeNode()) {
            return builder.up()
                    .withData("disabled", true)
                    .withData("reason", "edge node does not use Elasticsearch")
                    .build();
        }

        boolean connectionOk = connectionManager.isAvailable();
        boolean indexOk = postSearchService.isAvailable();
        boolean allOk = connectionOk && indexOk;

        builder.withData("connectionAvailable", connectionOk)
                .withData("indexInitialized", indexOk)
                .withData("healthStatus", connectionManager.getHealthStatus().name());

        if (allOk) {
            return builder.up().build();
        } else {
            String reason;
            if (!connectionOk) {
                reason = "ES connection unavailable";
            } else if (!indexOk) {
                reason = "ES index not initialized";
            } else {
                reason = "Unknown issue";
            }
            return builder.down()
                    .withData("reason", reason)
                    .build();
        }
    }
}
