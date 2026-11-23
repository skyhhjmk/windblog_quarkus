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
import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Emits HSTS (when enabled) and CSP (Report-Only by default, or enforced when configured). */
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

    // Qute accesses this bean through Arc at runtime; retain it during Quarkus unused-bean removal.
    @Inject
    AnalyticsTrackingSettings analyticsTracking;

    @Inject
    SettingsReader settings;

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        String cspHeader = cspEnforce ? "Content-Security-Policy" : "Content-Security-Policy-Report-Only";
        if (isAmpRequest(requestContext)) {
            responseContext.getHeaders().putSingle(cspHeader,
                    "default-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'; "
                            + "script-src https://cdn.ampproject.org; style-src 'unsafe-inline'; "
                            + "img-src 'self' https:; connect-src 'self'; font-src 'self' https:");
            if (hstsEnabled) {
                responseContext.getHeaders().putSingle("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            }
            return;
        }

        String nonce = cspNonceContext.getNonce();
        String imageSources = appendConfiguredSources("'self' data: blob:", cspImageSources);
        String connectSources = appendConfiguredSources("'self'", cspConnectSources);
        AnalyticsTrackingSettings.Settings tracking = analyticsTracking == null
                ? AnalyticsTrackingSettings.Settings.disabled()
                : analyticsTracking.current();
        String scriptSources = tracking.enabled() ? "'self' 'nonce-" + nonce + "' " + tracking.origin()
                : "'self' 'nonce-" + nonce + "'";
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
        String csp = "default-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'; "
                + "img-src " + imageSources + "; style-src 'self' 'nonce-" + nonce + "'; style-src-attr 'unsafe-inline'; "
                + "script-src " + scriptSources + "; connect-src " + connectSources + "; font-src 'self'; "
                + "frame-src " + frameSources;
        if (trustedTypesEnabled) {
            csp = csp + "; require-trusted-types-for 'script'; trusted-types default windblog-raw-html";
        }
        responseContext.getHeaders().putSingle(cspHeader, csp);

        if (hstsEnabled) {
            responseContext.getHeaders().putSingle("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
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
