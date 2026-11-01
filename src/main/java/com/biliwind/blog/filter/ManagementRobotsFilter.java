package com.biliwind.blog.filter;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Quarkus management endpoints under {@code /q/} are served by the management
 * router and never pass through JAX-RS response filters. Add the noindex
 * header at the Vert.x routing layer so health/metrics endpoints stay out of
 * search results as well as being disallowed in robots.txt.
 */
@ApplicationScoped
public class ManagementRobotsFilter {

    private static final String MANAGEMENT_PREFIX = "/q/";
    private static final String X_ROBOTS_TAG = "X-Robots-Tag";

    void init(@Observes Filters filters) {
        filters.register(this::handle, 900);
    }

    private void handle(RoutingContext context) {
        String path = context.request().path();
        if (path != null && path.startsWith(MANAGEMENT_PREFIX)) {
            context.response().headers().set(X_ROBOTS_TAG, "noindex");
        }
        context.next();
    }
}
