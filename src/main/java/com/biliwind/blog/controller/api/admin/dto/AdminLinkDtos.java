package com.biliwind.blog.controller.api.admin.dto;

import java.time.OffsetDateTime;
import java.util.Map;

public class AdminLinkDtos {

        public record AdminLinkItem(
                        Long id,
                        String name,
                        String url,
                        String description,
                        String image,
                        String icon,
                        Integer sortOrder,
                        short status,
                        String target,
                        short redirectType,
                        boolean showUrl,
                        String email,
                        String note,
                        Short type,
                        String seoTitle,
                        String seoKeywords,
                        String seoDescription,
                        short applicationStatus,
                        String availabilityStatus,
                        String backlinkStatus,
                        OffsetDateTime lastCheckedAt,
                        String placementType,
                        String placementUrl,
                        String placementPageName,
                        String placementDescription,
                        long referencedPostCount,
                        long referenceCount,
                        boolean monitoringEnabled,
                        int monitoringIntervalMinutes,
                        boolean hideWhenBacklinkMissing,
                        boolean hideWhenOffline,
                        String monitoringKeywords,
                        boolean hideWhenKeywordFraudDetected,
                        String keywordFraudStatus,
                        String displayRegion,
                        java.util.List<String> backlinkCheckUrls,
                        boolean notifyOnBacklinkMissing,
                        int backlinkMissingGraceDays,
                        boolean notifyOnOffline,
                        int offlineGraceDays,
                        boolean notifyOnKeywordFraud,
                        int keywordFraudGraceDays,
                        String autoHideMessage,
                        OffsetDateTime createdAt) {
        }

    public record AdminLinkReferenceItem(
            Long id,
            Long postId,
            String postSlug,
            String postTitle,
            String anchorText,
            String normalizedUrl,
            Integer referenceCount,
            OffsetDateTime updatedAt) {
    }

        public record LinkCreateRequest(
                        String name,
                        String url,
                        String description,
                        String image,
                        String icon,
                        Integer sortOrder,
                        Short status,
                        String target,
                        Short redirectType,
                        Boolean showUrl,
                        String email,
                        String note,
                        Short type,
                        String seoTitle,
                        String seoKeywords,
                        String seoDescription,
                        String placementUrl,
                        Boolean monitoringEnabled,
                        Integer monitoringIntervalMinutes,
                        Boolean hideWhenBacklinkMissing,
                        Boolean hideWhenOffline,
                        String monitoringKeywords,
                        Boolean hideWhenKeywordFraudDetected,
                        String displayRegion,
                        java.util.List<String> backlinkCheckUrls,
                        Boolean notifyOnBacklinkMissing,
                        Integer backlinkMissingGraceDays,
                        Boolean notifyOnOffline,
                        Integer offlineGraceDays,
                        Boolean notifyOnKeywordFraud,
                        Integer keywordFraudGraceDays) {
        }

        public record LinkUpdateRequest(
                        String name,
                        String url,
                        String description,
                        String image,
                        String icon,
                        Integer sortOrder,
                        Short status,
                        String target,
                        Short redirectType,
                        Boolean showUrl,
                        String email,
                        String note,
                        Short type,
                        String seoTitle,
                        String seoKeywords,
                        String seoDescription,
                        String placementUrl,
                        Boolean monitoringEnabled,
                        Integer monitoringIntervalMinutes,
                        Boolean hideWhenBacklinkMissing,
                        Boolean hideWhenOffline,
                        String monitoringKeywords,
                        Boolean hideWhenKeywordFraudDetected,
                        String displayRegion,
                        java.util.List<String> backlinkCheckUrls,
                        Boolean notifyOnBacklinkMissing,
                        Integer backlinkMissingGraceDays,
                        Boolean notifyOnOffline,
                        Integer offlineGraceDays,
                        Boolean notifyOnKeywordFraud,
                        Integer keywordFraudGraceDays) {
        }

        public record AdminLinkMonitorLogItem(
                        Long id,
                        Long linkId,
                        String linkName,
                        OffsetDateTime checkTime,
                        String checkSource,
                        Boolean ok,
                        Integer loadTimeMs,
                        Boolean backlinkFound,
                        Integer statusCode,
                        String checkBatchId,
                        String nodeId,
                        String nodeName,
                        String errorMessage,
                        Boolean keywordFraudDetected,
                        Map<String, Object> detectionDetails) {
        }

        public record AdminLinkCheckJobItem(
                        String jobId,
                        Long linkId,
                        String status,
                        OffsetDateTime startedAt,
                        OffsetDateTime completedAt,
                        String errorMessage) {
        }

    public record LinkCheckRequest(
            Boolean sendEmailNotification) {
        }

    public record LinkApplicationReviewRequest(
            boolean approved,
            String note,
            Boolean sendEmailNotification) {
        }

        public record AdminLinkAuditItem(
                        Long id,
                        Long linkId,
                        String linkName,
                        short status,
                        java.math.BigDecimal score,
                        String reason,
                        Boolean autoApproved,
                        OffsetDateTime createdAt) {
        }

    public record LinkMetaResponse(
            String title,
            String description,
            String icon) {
    }

    public record LinkSiteUpdateNoticeRequest(
            String subject,
            String adminNote,
            Boolean sendEmailNotification) {
    }

    public record LinkBatchSiteUpdateNoticeRequest(
            java.util.List<Long> linkIds,
            String subject,
            String adminNote,
            Boolean sendEmailNotification) {
    }

    public record LinkSiteUpdateNoticePreview(
            String siteName,
            String siteSubtitle,
            String siteUrl,
            String siteDescription,
            String siteIcon,
            String defaultSubject,
            String greeting,
            String content,
            String previewHtml,
            long eligibleLinkCount) {
    }

    public record LinkSiteUpdateNoticeResult(
            int requestedCount,
            int queuedCount,
            int skippedCount,
            java.util.List<String> skippedLinks,
            String message) {
    }
}
