package com.biliwind.blog.filter;

import com.biliwind.blog.context.CspNonceContext;
import com.biliwind.blog.service.AnalyticsTrackingSettings;
import com.biliwind.blog.service.security.SecurityHttpSettings;
import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Emits database-managed HTTP security headers: CSP (enforced or Report-Only),
 * HSTS, X-Frame-Options, X-Content-Type-Options, Referrer-Policy, and Permissions-Policy.
 */
@Provider
@Priority(Priorities.HEADER_DECORATOR)
@ApplicationScoped
public class SecurityHeadersFilter implements ContainerResponseFilter {

    @Inject
    SecurityHttpSettings securityHttpSettings;

    @Inject
    CspNonceContext cspNonceContext;

    // Qute accesses this bean through Arc at runtime; retain it during Quarkus unused-bean removal.
    @Inject
    AnalyticsTrackingSettings analyticsTracking;

    @Inject
    SettingsReader settings;

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        SecurityHttpSettings.Snapshot policy = securityHttpSettings == null
                ? SecurityHttpSettings.defaults()
                : securityHttpSettings.current();
        MultivaluedMap<String, Object> headers = responseContext.getHeaders();

        applyBaselineHeaders(headers, policy);

        if (!policy.cspEnabled()) {
            return;
        }
        if (responseContext.getStatus() == Response.Status.NOT_MODIFIED.getStatusCode()) {
            return;
        }

        String cspHeader = policy.cspEnforce() ? "Content-Security-Policy" : "Content-Security-Policy-Report-Only";
        String frameAncestors = policy.frameAncestorsDirective();

        if (isAmpRequest(requestContext)) {
            headers.putSingle(cspHeader,
                    "default-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors " + frameAncestors + "; "
                            + "script-src https://cdn.ampproject.org; style-src 'unsafe-inline'; "
                            + "img-src 'self' https:; connect-src 'self'; font-src 'self' https:");
            return;
        }

        String nonce = cspNonceContext.getNonce();
        String imageSources = appendConfiguredSources("'self' data: blob:", policy.cspImgSources());
        String connectSources = appendConfiguredSources("'self'", policy.cspConnectSources());
        AnalyticsTrackingSettings.Settings tracking = analyticsTracking == null
                ? AnalyticsTrackingSettings.Settings.disabled()
                : analyticsTracking.current();
        String scriptSources = tracking.enabled()
                ? "'self' 'nonce-" + nonce + "' 'wasm-unsafe-eval' " + tracking.origin()
                : "'self' 'nonce-" + nonce + "' 'wasm-unsafe-eval'";
        String frameSources = "'self'" + (tracking.enabled() ? " " + tracking.origin() : "");
        if (tracking.enabled()) {
            connectSources = connectSources + " " + tracking.origin();
        }
        if (isAdsenseEnabled()) {
            scriptSources = scriptSources + " https://pagead2.googlesyndication.com";
            connectSources = connectSources
                    + " https://pagead2.googlesyndication.com https://googleads.g.doubleclick.net"
                    + " https://ep1.adtrafficquality.google https://ep2.adtrafficquality.google";
            frameSources = frameSources
                    + " https://googleads.g.doubleclick.net https://tpc.googlesyndication.com"
                    + " https://ep2.adtrafficquality.google";
            imageSources = imageSources
                    + " https://pagead2.googlesyndication.com https://googleads.g.doubleclick.net"
                    + " https://tpc.googlesyndication.com";
        }
        String csp = "default-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors "
                + frameAncestors + "; "
                + "img-src " + imageSources + "; style-src 'self' 'nonce-" + nonce + "'; style-src-attr 'unsafe-inline'; "
                + "script-src " + scriptSources + "; connect-src " + connectSources + "; font-src 'self'; "
                + "frame-src " + frameSources;
        if (policy.cspTrustedTypesEnabled()) {
            csp = csp + "; require-trusted-types-for 'script'; trusted-types default windblog-raw-html";
        }
        headers.putSingle(cspHeader, csp);
    }

    private void applyBaselineHeaders(MultivaluedMap<String, Object> headers, SecurityHttpSettings.Snapshot policy) {
        if (policy.hstsEnabled()) {
            headers.putSingle("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        if (!SecurityHttpSettings.DISABLED.equals(policy.xFrameOptions())) {
            headers.putSingle("X-Frame-Options", policy.xFrameOptions());
        }
        if (policy.xContentTypeOptionsNosniff()) {
            headers.putSingle("X-Content-Type-Options", "nosniff");
        }
        if (!SecurityHttpSettings.DISABLED.equals(policy.referrerPolicy())) {
            headers.putSingle("Referrer-Policy", policy.referrerPolicy());
        }
        if (policy.permissionsPolicy() != null && !policy.permissionsPolicy().isBlank()) {
            headers.putSingle("Permissions-Policy", policy.permissionsPolicy());
        }
    }

    private boolean isAdsenseEnabled() {
        return settings != null && settings.flag(SettingKey.GOOGLE_ADSENSE, "enabled");
    }

    private boolean isAmpRequest(ContainerRequestContext requestContext) {
        if (requestContext == null || requestContext.getUriInfo() == null) {
            return false;
        }
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
