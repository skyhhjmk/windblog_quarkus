package com.biliwind.blog.filter;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Provider
@Priority(Priorities.HEADER_DECORATOR)
@ApplicationScoped
public class SecurityHeadersFilter implements ContainerResponseFilter {

    @ConfigProperty(name = "security.headers.hsts.enabled", defaultValue = "false")
    boolean hstsEnabled;

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        responseContext.getHeaders().putSingle("X-Content-Type-Options", "nosniff");
        responseContext.getHeaders().putSingle("Referrer-Policy", "strict-origin-when-cross-origin");
        responseContext.getHeaders().putSingle("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        responseContext.getHeaders().putSingle("X-Frame-Options", "DENY");
        responseContext.getHeaders().putSingle(
                "Content-Security-Policy-Report-Only",
                "default-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; img-src 'self' https: data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self' https:"
        );
        if (hstsEnabled) {
            responseContext.getHeaders().putSingle("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
    }
}