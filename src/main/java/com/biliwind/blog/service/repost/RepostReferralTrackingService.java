package com.biliwind.blog.service.repost;

import com.biliwind.blog.model.RepostLicense;
import com.biliwind.blog.model.RepostReferralEvent;
import com.biliwind.blog.service.security.ClientIpResolver;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.HttpHeaders;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Records GET visits that arrive through the tracked original URL embedded in a
 * repost attribution block.
 */
@ApplicationScoped
public class RepostReferralTrackingService {

    private static final Duration DEDUPE_WINDOW = Duration.ofMinutes(30);

    @ConfigProperty(name = "windblog.site.primary-domain", defaultValue = "localhost")
    String primaryDomain;

    @ConfigProperty(name = "windblog.node.id", defaultValue = "primary")
    String sourceNodeId;

    @Inject
    RepostDomainService repostDomainService;

    @Inject
    ClientIpResolver clientIpResolver;

    @Inject
    RoutingContext routingContext;

    @Transactional
    public boolean record(String licenseCode, HttpHeaders headers) {
        if (licenseCode == null || licenseCode.isBlank()) {
            return false;
        }
        String normalizedCode = licenseCode.trim();
        RepostLicense license = RepostLicense.find(
                "code = ?1 and status = 1", normalizedCode).firstResult();
        if (license == null) {
            return false;
        }

        String refererUrl = header(headers, "Referer");
        String refererDomain = repostDomainService.normalizeDomainFromUrl(refererUrl);
        String ipHash = hashNullable(resolveClientIp());
        String userAgentHash = hashNullable(header(headers, "User-Agent"));

        if (isDuplicate(license.id, ipHash, userAgentHash)) {
            return false;
        }

        RepostReferralEvent event = new RepostReferralEvent();
        event.repostLicense = license;
        event.article = license.article;
        event.licenseCode = normalizedCode;
        event.refererUrl = refererUrl;
        event.refererDomain = refererDomain;
        event.refererCategory = classify(license, refererDomain);
        event.ipHash = ipHash;
        event.userAgentHash = userAgentHash;
        event.sourceNodeId = sourceNodeId;
        event.viewedAt = OffsetDateTime.now();
        event.persist();
        return true;
    }

    String classify(RepostLicense license, String refererDomain) {
        if (refererDomain == null || refererDomain.isBlank()) {
            return "unknown_referrer";
        }
        if (repostDomainService.sameDomain(primaryDomain, refererDomain)) {
            return "first_party_article";
        }
        if (license != null && repostDomainService.sameDomain(license.allowedDomain, refererDomain)) {
            return "authorized_repost";
        }
        return "suspicious_external";
    }

    private boolean isDuplicate(Long licenseId, String ipHash, String userAgentHash) {
        if (ipHash == null && userAgentHash == null) {
            return false;
        }
        OffsetDateTime cutoff = OffsetDateTime.now().minus(DEDUPE_WINDOW);
        if (ipHash != null && userAgentHash != null) {
            return RepostReferralEvent.count(
                    "repostLicense.id = ?1 and ipHash = ?2 and userAgentHash = ?3 and viewedAt >= ?4",
                    licenseId, ipHash, userAgentHash, cutoff) > 0;
        }
        if (ipHash != null) {
            return RepostReferralEvent.count(
                    "repostLicense.id = ?1 and ipHash = ?2 and viewedAt >= ?3",
                    licenseId, ipHash, cutoff) > 0;
        }
        return RepostReferralEvent.count(
                "repostLicense.id = ?1 and userAgentHash = ?2 and viewedAt >= ?3",
                licenseId, userAgentHash, cutoff) > 0;
    }

    private String resolveClientIp() {
        if (routingContext == null) {
            return null;
        }
        try {
            return clientIpResolver.resolve(routingContext).clientIp();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String header(HttpHeaders headers, String name) {
        if (headers == null) {
            return null;
        }
        String value = headers.getHeaderString(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String hashNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception ignored) {
            return null;
        }
    }
}
