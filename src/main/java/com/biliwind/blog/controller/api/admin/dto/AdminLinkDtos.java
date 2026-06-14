package com.biliwind.blog.controller.api.admin.dto;

import java.time.OffsetDateTime;

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
                        String seoDescription) {
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
                        String seoDescription) {
        }

        public record AdminLinkMonitorLogItem(
                        Long id,
                        Long linkId,
                        String linkName,
                        OffsetDateTime checkTime,
                        Boolean ok,
                        Integer loadTimeMs,
                        Boolean backlinkFound,
                        Integer statusCode,
                        String checkBatchId,
                        String nodeId,
                        String nodeName,
                        String errorMessage) {
        }

    public record LinkApplicationReviewRequest(
            boolean approved,
            String note) {
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
}
