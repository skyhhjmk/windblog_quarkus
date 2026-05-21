package com.biliwind.blog.controller.api.admin.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class AdminPostDtos {

        private AdminPostDtos() {
        }

        public record PostCreateRequest(
                String slug,
                        @NotEmpty(message = "title 不能为空") Map<String, String> title,
                        Map<String, String> summary,
                        Map<String, String> aiSummary,
                        Map<String, String> contentMarkdown,
                        Short status,
                        Short visibility,
                        String password,
                        String seoTitle,
                        String seoKeywords,
                        String seoDescription,
                        Short renderType,
                        Short editorType,
                        Short aiSummaryStatus,
                        Long pointsPrice,
                        Integer freeLines,
                        Long categoryId,
                List<Long> tagIds,
                List<String> visibilityRegions) {
        }

        public record PostUpdateRequest(
                        String slug,
                        Map<String, String> title,
                        Map<String, String> summary,
                        Map<String, String> aiSummary,
                        Map<String, String> contentMarkdown,
                        Short status,
                        Short visibility,
                        String password,
                        String seoTitle,
                        String seoKeywords,
                        String seoDescription,
                        Short renderType,
                        Short editorType,
                        Short aiSummaryStatus,
                        @NotNull(message = "version 不能为空") Integer version,
                        Long pointsPrice,
                        Integer freeLines,
                        Long categoryId,
                        List<Long> tagIds,
                        List<String> visibilityRegions) {
        }

        public record AdminPostItem(
                        Long id,
                        String slug,
                        Map<String, String> title,
                        Short status,
                        Short visibility,
                        Short renderType,
                        Short aiSummaryStatus,
                        Integer version,
                        Long userId,
                        String userName,
                        Long categoryId,
                        List<Long> tagIds,
                        OffsetDateTime publishedAt,
                        OffsetDateTime createdAt,
                        OffsetDateTime updatedAt) {
        }

        public record AdminPostDetail(
                        Long id,
                        String slug,
                        Map<String, String> title,
                        Map<String, String> summary,
                        Map<String, String> aiSummary,
                        Map<String, String> contentMarkdown,
                        Short status,
                        Short visibility,
                        String password,
                        Boolean hasPassword,
                        String seoTitle,
                        String seoKeywords,
                        String seoDescription,
                        Short renderType,
                        Short editorType,
                        Short aiSummaryStatus,
                        Integer currentRevisionNumber,
                        Integer version,
                        Long pointsPrice,
                        Integer freeLines,
                        Long userId,
                        String userName,
                        Long categoryId,
                        List<Long> tagIds,
                        List<String> visibilityRegions,
                        OffsetDateTime publishedAt,
                        OffsetDateTime createdAt,
                        OffsetDateTime updatedAt) {
        }

    public record PostRevisionItem(
            Long id,
            int revisionNumber,
            Map<String, String> title,
            short editorType,
            Long createdBy,
            String createdByName,
            OffsetDateTime createdAt) {
    }

        public record PageResult<T>(
                        List<T> items,
                        long total,
                        int page,
                        int pageSize) {
        }
}
