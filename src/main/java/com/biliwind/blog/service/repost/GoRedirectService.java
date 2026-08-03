package com.biliwind.blog.service.repost;

import com.biliwind.blog.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import io.vertx.ext.web.RoutingContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 处理 go 短链跳转、限速、封禁和点击证据链。
 */
@ApplicationScoped
public class GoRedirectService {

    @ConfigProperty(name = "windblog.site.primary-domain", defaultValue = "localhost")
    String primaryDomain;

    @ConfigProperty(name = "windblog.go.redirect-status", defaultValue = "302")
    String redirectStatus;

    @ConfigProperty(name = "windblog.node.id", defaultValue = "primary")
    String sourceNodeId;

    @Inject
    AffiliateTokenService affiliateTokenService;

    @Inject
    RepostDomainService repostDomainService;

    @Inject
    RepostDetectionService repostDetectionService;

    @Inject
    com.biliwind.blog.service.security.SecurityRateLimitService securityRateLimitService;

    @Inject
    com.biliwind.blog.service.security.ClientIpResolver clientIpResolver;

    @Inject
    RoutingContext routingContext;

    @Transactional
    public Response redirect(String rawToken, HttpHeaders httpHeaders) {
        AffiliateToken affiliateToken = affiliateTokenService.findByRawToken(rawToken);
        if (affiliateToken == null) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }

        if (affiliateToken.status != 1) {
            throw new WebApplicationException(Response.Status.GONE);
        }

        AffiliateLink affiliateLink = affiliateToken.affiliateLink;
        if (affiliateLink == null) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }

        if (affiliateLink.status != 1) {
            throw new WebApplicationException(Response.Status.GONE);
        }

        String refererUrl = resolveHeader(httpHeaders, "Referer");
        String refererDomain = repostDomainService.normalizeDomainFromUrl(refererUrl);
        String deviceRiskId = resolveDeviceRiskId(httpHeaders);
        String clientIp = resolveClientIp(httpHeaders);
        String userAgent = resolveHeader(httpHeaders, "User-Agent");

        if (isBlockedDomain(refererDomain)) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }

        if (isBlockedDevice(deviceRiskId)) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }

        String ipHash = hashNullable(clientIp);
        if (isRateLimited(affiliateToken.id, ipHash)) {
            throw new WebApplicationException(Response.Status.TOO_MANY_REQUESTS);
        }

        String refererCategory = classifyReferer(affiliateToken, refererDomain);
        int riskScore = calculateRiskScore(affiliateToken, refererCategory, refererDomain, deviceRiskId);
        RedirectClickEvent clickEvent = createClickEvent(
                affiliateToken,
                refererUrl,
                refererDomain,
                refererCategory,
                ipHash,
                hashNullable(userAgent),
                deviceRiskId,
                riskScore
        );

        if ("suspicious_external".equals(refererCategory)) {
            repostDetectionService.recordSuspiciousExternalClick(affiliateToken, clickEvent, riskScore);
        }

        Response.ResponseBuilder responseBuilder = buildRedirectResponse(affiliateLink.targetUrl);
        responseBuilder.header("X-Robots-Tag", "noindex, nofollow");
        responseBuilder.header("Cache-Control", "no-store");
        return responseBuilder.build();
    }

    public String classifyReferer(AffiliateToken affiliateToken, String refererDomain) {
        if (refererDomain == null || refererDomain.isBlank()) {
            return "unknown_referrer";
        }

        if (repostDomainService.sameDomain(primaryDomain, refererDomain)) {
            return "first_party_article";
        }

        String allowedDomain = affiliateToken.allowedDomain;
        if (allowedDomain == null) {
            RepostLicense repostLicense = affiliateToken.repostLicense;
            if (repostLicense != null) {
                allowedDomain = repostLicense.allowedDomain;
            }
        }

        if (allowedDomain != null) {
            if (repostDomainService.sameDomain(allowedDomain, refererDomain)) {
                return "authorized_repost";
            }
        }

        return "suspicious_external";
    }

    private RedirectClickEvent createClickEvent(AffiliateToken affiliateToken,
                                                String refererUrl,
                                                String refererDomain,
                                                String refererCategory,
                                                String ipHash,
                                                String userAgentHash,
                                                String deviceRiskId,
                                                int riskScore) {
        RedirectClickEvent clickEvent = new RedirectClickEvent();
        clickEvent.eventKey = UUID.randomUUID().toString();
        clickEvent.affiliateToken = affiliateToken;
        clickEvent.repostLicense = affiliateToken.repostLicense;
        clickEvent.refererUrl = refererUrl;
        clickEvent.refererDomain = refererDomain;
        clickEvent.refererCategory = refererCategory;
        clickEvent.ipHash = ipHash;
        clickEvent.userAgentHash = userAgentHash;
        clickEvent.deviceRiskId = deviceRiskId;
        clickEvent.riskScore = riskScore;
        clickEvent.sourceNodeId = sourceNodeId;
        clickEvent.clickedAt = OffsetDateTime.now();
        clickEvent.syncedToPrimary = false;
        clickEvent.persist();
        return clickEvent;
    }

    private int calculateRiskScore(AffiliateToken affiliateToken,
                                   String refererCategory,
                                   String refererDomain,
                                   String deviceRiskId) {
        int riskScore = 0;
        if (affiliateToken.status != 1) {
            riskScore = riskScore + 80;
        }
        if ("suspicious_external".equals(refererCategory)) {
            riskScore = riskScore + 60;
        }
        if (isBlockedDomain(refererDomain)) {
            riskScore = riskScore + 90;
        }
        if (isBlockedDevice(deviceRiskId)) {
            riskScore = riskScore + 90;
        }
        RepostLicense repostLicense = affiliateToken.repostLicense;
        if (repostLicense != null) {
            if (repostLicense.status != 1) {
                riskScore = riskScore + 70;
            }
        }
        return riskScore;
    }

    private boolean isBlockedDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return false;
        }
        long count = BlockedDomain.count("domainName = ?1 and status = 1", domain);
        return count > 0;
    }

    private boolean isBlockedDevice(String deviceRiskId) {
        if (deviceRiskId == null || deviceRiskId.isBlank()) {
            return false;
        }
        long count = RiskDevice.count("deviceRiskId = ?1 and status = 1", deviceRiskId);
        return count > 0;
    }

    private boolean isRateLimited(Long tokenId, String ipHash) {
        String key = String.valueOf(tokenId) + ":" + ipHash;
        return !securityRateLimitService.tryAcquire(
                "go:redirect:" + key, 60, java.time.Duration.ofMinutes(1));
    }

    private Response.ResponseBuilder buildRedirectResponse(String targetUrl) {
        URI targetUri = URI.create(targetUrl);
        if ("307".equals(redirectStatus)) {
            return Response.temporaryRedirect(targetUri).status(307);
        }
        return Response.status(302).location(targetUri);
    }

    private String resolveHeader(HttpHeaders httpHeaders, String headerName) {
        if (httpHeaders == null) {
            return null;
        }
        return httpHeaders.getHeaderString(headerName);
    }

    private String resolveClientIp(HttpHeaders httpHeaders) {
        if (routingContext != null) {
            return clientIpResolver.resolve(routingContext).clientIp();
        }
        return "unknown";
    }

    private String resolveDeviceRiskId(HttpHeaders httpHeaders) {
        String headerValue = resolveHeader(httpHeaders, "X-Device-Risk-Id");
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        return headerValue.trim();
    }

    private String hashNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] digestBytes = messageDigest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte digestByte : digestBytes) {
                builder.append(String.format("%02x", digestByte));
            }
            return builder.toString();
        } catch (Exception exception) {
            return null;
        }
    }

}
