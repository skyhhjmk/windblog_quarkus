package com.biliwind.blog.service.link;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, user-visible evidence from a single node's backlink inspection. */
public record LinkProbeEvidence(
        String checkedUrl,
        String targetName,
        String siteUrl,
        String siteName,
        List<String> expectedKeywords,
        List<String> matchedKeywords,
        List<String> matchedBacklinkUrls,
        List<String> matchedAnchorTexts,
        boolean keywordFraudDetected,
        List<String> fraudReasons,
        int domParseErrorCount,
        boolean detectorSupported,
        List<String> expectedSiteUrls
) {
    public LinkProbeEvidence {
        expectedKeywords = safeList(expectedKeywords);
        matchedKeywords = safeList(matchedKeywords);
        matchedBacklinkUrls = safeList(matchedBacklinkUrls);
        matchedAnchorTexts = safeList(matchedAnchorTexts);
        fraudReasons = safeList(fraudReasons);
        expectedSiteUrls = safeList(expectedSiteUrls);
    }

    public LinkProbeEvidence(String checkedUrl, String targetName, String siteUrl, String siteName,
                             List<String> expectedKeywords, List<String> matchedKeywords,
                             List<String> matchedBacklinkUrls, List<String> matchedAnchorTexts,
                             boolean keywordFraudDetected, List<String> fraudReasons,
                             int domParseErrorCount, boolean detectorSupported) {
        this(checkedUrl, targetName, siteUrl, siteName, expectedKeywords, matchedKeywords, matchedBacklinkUrls,
                matchedAnchorTexts, keywordFraudDetected, fraudReasons, domParseErrorCount, detectorSupported,
                siteUrl == null || siteUrl.isBlank() ? List.of() : List.of(siteUrl));
    }

    public static LinkProbeEvidence unavailable(String checkedUrl) {
        return new LinkProbeEvidence(checkedUrl, "", "", "", List.of(), List.of(), List.of(), List.of(),
                false, List.of(), 0, false);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("checkedUrl", checkedUrl);
        details.put("targetName", targetName);
        details.put("siteUrl", siteUrl);
        details.put("expectedSiteUrls", expectedSiteUrls);
        details.put("siteName", siteName);
        details.put("expectedKeywords", expectedKeywords);
        details.put("matchedKeywords", matchedKeywords);
        details.put("matchedBacklinkUrls", matchedBacklinkUrls);
        details.put("matchedAnchorTexts", matchedAnchorTexts);
        details.put("keywordFraudDetected", keywordFraudDetected);
        details.put("fraudReasons", fraudReasons);
        details.put("domParseErrorCount", domParseErrorCount);
        details.put("evidenceAvailable", detectorSupported);
        return details;
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
