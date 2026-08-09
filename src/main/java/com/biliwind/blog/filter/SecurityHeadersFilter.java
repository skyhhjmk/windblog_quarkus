package com.biliwind.blog.filter;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Provider
@Priority(Priorities.HEADER_DECORATOR)
@ApplicationScoped
public class SecurityHeadersFilter implements ContainerResponseFilter {

    @ConfigProperty(name = "security.headers.hsts.enabled", defaultValue = "false")
    boolean hstsEnabled;

    @ConfigProperty(name = "security.headers.csp.enforce", defaultValue = "false")
    boolean cspEnforce;

    @ConfigProperty(name = "security.headers.csp.img-sources", defaultValue = "none")
    String cspImageSources;

    @ConfigProperty(name = "security.headers.csp.connect-sources", defaultValue = "none")
    String cspConnectSources;

    @ConfigProperty(name = "security.headers.csp.trusted-types.enabled", defaultValue = "false")
    boolean trustedTypesEnabled;

    @Inject
    com.biliwind.blog.context.CspNonceContext cspNonceContext;

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        responseContext.getHeaders().putSingle("X-Content-Type-Options", "nosniff");
        responseContext.getHeaders().putSingle("Referrer-Policy", "strict-origin-when-cross-origin");
        responseContext.getHeaders().putSingle("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        responseContext.getHeaders().putSingle("X-Frame-Options", "DENY");
        if (isAmpRequest(requestContext)) {
            responseContext.getHeaders().putSingle("Content-Security-Policy",
                    "default-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'; "
                            + "script-src https://cdn.ampproject.org; style-src 'unsafe-inline'; "
                            + "img-src 'self' https:; connect-src 'self'; font-src 'self' https:");
            if (hstsEnabled) {
                responseContext.getHeaders().putSingle("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            }
            return;
        }
        String nonce = cspNonceContext.getNonce();
        String imageSources = appendConfiguredSources("'self'", cspImageSources);
        String connectSources = appendConfiguredSources("'self'", cspConnectSources);
        String csp = "default-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'; "
                + "img-src " + imageSources + "; style-src 'self' 'nonce-" + nonce + "'; "
                + "script-src 'self' 'nonce-" + nonce + "'; connect-src " + connectSources + "; font-src 'self'";
        if (trustedTypesEnabled) {
            csp = csp + "; require-trusted-types-for 'script'; trusted-types default";
        }
        responseContext.getHeaders().putSingle(cspEnforce ? "Content-Security-Policy" : "Content-Security-Policy-Report-Only", csp);
        if (hstsEnabled) {
            responseContext.getHeaders().putSingle("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
    }

    private boolean isAmpRequest(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getPath();
        return path != null && (path.startsWith("amp/") || path.contains("/amp/"));
    }

    private String appendConfiguredSources(String base, String configuredSources) {
        StringBuilder result = new StringBuilder(base);
        if (configuredSources == null || configuredSources.isBlank()
                || "none".equalsIgnoreCase(configuredSources.trim())) {
            return result.toString();
        }
        String[] sourceValues = configuredSources.split(",");
        for (String sourceValue : sourceValues) {
            String source = sourceValue.trim();
            if (!isSafeSourceExpression(source)) {
                continue;
            }
            result.append(' ').append(source);
        }
        return result.toString();
    }

    private boolean isSafeSourceExpression(String source) {
        if (source.isBlank() || source.contains("*") || source.contains("'")) {
            return false;
        }
        return source.startsWith("https://") || source.startsWith("http://localhost")
                || source.startsWith("http://127.0.0.1");
    }
}
