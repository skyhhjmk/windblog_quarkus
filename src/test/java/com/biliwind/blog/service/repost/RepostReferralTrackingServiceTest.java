package com.biliwind.blog.service.repost;

import com.biliwind.blog.model.RepostLicense;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepostReferralTrackingServiceTest {

    @Test
    void shouldClassifyReferrerDomainsForConversionTracking() {
        RepostReferralTrackingService service = new RepostReferralTrackingService();
        service.repostDomainService = new RepostDomainService();
        service.primaryDomain = "windblog.example";

        RepostLicense license = new RepostLicense();
        license.allowedDomain = "partner.example";

        assertEquals("unknown_referrer", service.classify(license, null));
        assertEquals("unknown_referrer", service.classify(license, " "));
        assertEquals("first_party_article", service.classify(license, "www.windblog.example"));
        assertEquals("authorized_repost", service.classify(license, "partner.example"));
        assertEquals("suspicious_external", service.classify(license, "unknown.example"));
        assertEquals("suspicious_external", service.classify(null, "unknown.example"));
    }
}
