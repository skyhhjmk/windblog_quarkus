package com.biliwind.blog.service.repost;

import com.biliwind.blog.model.AffiliateToken;
import com.biliwind.blog.model.RedirectClickEvent;
import com.biliwind.blog.model.SuspiciousRepost;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 记录外部未知域名传播证据。
 */
@ApplicationScoped
public class RepostDetectionService {

    @Transactional
    public void recordSuspiciousExternalClick(AffiliateToken affiliateToken,
                                              RedirectClickEvent clickEvent,
                                              int riskScore) {
        if (affiliateToken == null) {
            return;
        }
        if (clickEvent == null) {
            return;
        }
        if (clickEvent.refererDomain == null || clickEvent.refererDomain.isBlank()) {
            return;
        }

        SuspiciousRepost suspiciousRepost = SuspiciousRepost.find(
                "affiliateToken.id = ?1 and suspiciousDomain = ?2",
                affiliateToken.id,
                clickEvent.refererDomain
        ).firstResult();

        OffsetDateTime now = clickEvent.clickedAt;
        if (now == null) {
            now = OffsetDateTime.now();
        }

        if (suspiciousRepost == null) {
            suspiciousRepost = new SuspiciousRepost();
            suspiciousRepost.affiliateToken = affiliateToken;
            suspiciousRepost.repostLicense = affiliateToken.repostLicense;
            suspiciousRepost.article = affiliateToken.article;
            suspiciousRepost.suspiciousDomain = clickEvent.refererDomain;
            suspiciousRepost.firstRefererUrl = clickEvent.refererUrl;
            suspiciousRepost.latestRefererUrl = clickEvent.refererUrl;
            suspiciousRepost.clickCount = 1;
            suspiciousRepost.riskScore = riskScore;
            suspiciousRepost.evidenceJson = buildEvidence(clickEvent, riskScore);
            suspiciousRepost.firstSeenAt = now;
            suspiciousRepost.lastSeenAt = now;
            suspiciousRepost.persist();
            return;
        }

        suspiciousRepost.latestRefererUrl = clickEvent.refererUrl;
        suspiciousRepost.clickCount = suspiciousRepost.clickCount + 1;
        if (riskScore > suspiciousRepost.riskScore) {
            suspiciousRepost.riskScore = riskScore;
        }
        suspiciousRepost.evidenceJson = buildEvidence(clickEvent, suspiciousRepost.riskScore);
        suspiciousRepost.lastSeenAt = now;
    }

    public Map<String, Object> buildComplianceEvidence(String checkedUrl,
                                                       boolean hasOriginalLink,
                                                       boolean hasLicenseCode,
                                                       boolean hasGoLink,
                                                       boolean hasAffiliateToken,
                                                       String snippetSummary) {
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("checkedUrl", checkedUrl);
        evidence.put("checkedAt", OffsetDateTime.now().toString());
        evidence.put("hasOriginalLink", hasOriginalLink);
        evidence.put("hasLicenseCode", hasLicenseCode);
        evidence.put("hasGoLink", hasGoLink);
        evidence.put("hasAffiliateToken", hasAffiliateToken);
        evidence.put("snippetSummary", snippetSummary);
        return evidence;
    }

    private Map<String, Object> buildEvidence(RedirectClickEvent clickEvent, int riskScore) {
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("rule", "unknown_external_domain");
        evidence.put("riskScore", riskScore);
        evidence.put("refererUrl", clickEvent.refererUrl);
        evidence.put("refererDomain", clickEvent.refererDomain);
        evidence.put("clickedAt", clickEvent.clickedAt.toString());
        return evidence;
    }
}
