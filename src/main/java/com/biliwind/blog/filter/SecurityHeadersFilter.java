package com.biliwind.blog.filter;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import jakarta.inject.Inject;
import com.biliwind.blog.service.AnalyticsTrackingSettings;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Temporarily emits only HSTS; other security response headers are disabled. */
@Provider
@Priority(Priorities.HEADER_DECORATOR)
@ApplicationScoped
public class SecurityHeadersFilter implements ContainerResponseFilter {

    @ConfigProperty(name = "security.headers.hsts.enabled", defaultValue = "false")
    boolean hstsEnabled;

    // Qute accesses this bean through Arc at runtime; retain it during Quarkus unused-bean removal.
    @Inject
    AnalyticsTrackingSettings analyticsTracking;

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        if (hstsEnabled) {
            responseContext.getHeaders().putSingle("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
    }
}
