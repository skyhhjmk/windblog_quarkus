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
                        List<Long> categoryIds,
                        List<Long> tagIds,
                        List<String> visibilityRegions,
                        List<String> contentDeclarations,
                        String repostPolicyCode,
                        Map<String, String> seoTitleTranslations,
                        Map<String, String> seoKeywordsTranslations,
                        Map<String, String> seoDescriptionTranslations,
                        Short articleMode,
                        com.biliwind.blog.model.TutorialMeta tutorialMeta,
                        com.biliwind.blog.model.ResourceMeta resourceMeta,
                        String authorName,
                        Long userId,
                        Boolean allowComment) {
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
                        List<Long> categoryIds,
                        List<Long> tagIds,
                        List<String> visibilityRegions,
                        List<String> contentDeclarations,
                        String repostPolicyCode,
                        Map<String, String> seoTitleTranslations,
                        Map<String, String> seoKeywordsTranslations,
                        Map<String, String> seoDescriptionTranslations,
                        Boolean force,
                        Short articleMode,
                        com.biliwind.blog.model.TutorialMeta tutorialMeta,
                        com.biliwind.blog.model.ResourceMeta resourceMeta,
                        String authorName,
                        Long userId,
                        OffsetDateTime publishedAt,
                        Boolean allowComment) {
        }

        public record PostTranslationRequest(
                        String sourceLanguage,
                        String targetLanguage,
                        String title,
                        String summary,
                        String contentMarkdown,
                        String seoTitle,
                        String seoDescription,
                        String seoKeywords) {
        }

        public record PostTranslationResponse(
                        String sourceLanguage,
                        String targetLanguage,
                        String title,
                        String summary,
                        String contentMarkdown,
                        String seoTitle,
                        String seoDescription,
                        String seoKeywords,
                        String slug) {
        }

        public record PostSaveTranslationRequest(
                        String targetLanguage,
                        String title,
                        String summary,
                        String contentMarkdown,
                        String seoTitle,
                        String seoDescription,
                        String seoKeywords) {
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
                        String authorName,
                        Long categoryId,
                        List<Long> categoryIds,
                        List<Long> tagIds,
                        List<String> visibilityRegions,
                        Integer publishedRevisionNumber,
                        Boolean hasPublishedRevision,
                        OffsetDateTime publishedAt,
                        OffsetDateTime createdAt,
                        OffsetDateTime updatedAt,
                        Short articleMode,
                        Boolean allowComment) {
        }

        public record PostQuickEditRequest(
                        String slug,
                        Map<String, String> title,
                        Short status,
                        Short visibility,
                        List<String> visibilityRegions,
                        String password,
                        Long categoryId,
                        List<Long> categoryIds,
                        List<Long> tagIds,
                        String authorName,
                        Long userId,
                        OffsetDateTime publishedAt,
                        Short articleMode,
                        Boolean allowComment,
                        @NotNull(message = "version 不能为空") Integer version) {
        }

    public record EmailDispatchRequest(
            Boolean sendArticleUpdate,
            Long channelGroupId,
            Long channelId) {
    }

    public record ReviewDecisionRequest(String note) {
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
                        String authorName,
                        Long categoryId,
                        List<Long> categoryIds,
                        List<Long> tagIds,
                        List<String> visibilityRegions,
                        List<String> contentDeclarations,
                        String repostPolicyCode,
                        Integer publishedRevisionNumber,
                        Boolean hasPublishedRevision,
                        OffsetDateTime publishedAt,
                        OffsetDateTime createdAt,
                        OffsetDateTime updatedAt,
                        Map<String, String> seoTitleTranslations,
                        Map<String, String> seoKeywordsTranslations,
                        Map<String, String> seoDescriptionTranslations,
                        Short articleMode,
                        com.biliwind.blog.model.TutorialMeta tutorialMeta,
                        com.biliwind.blog.model.ResourceMeta resourceMeta,
                        Boolean allowComment) {
        }

    public record PostRevisionItem(
            Long id,
            int revisionNumber,
            Map<String, String> title,
            short editorType,
            Long createdBy,
            String createdByName,
            Boolean isPublishedRevision,
            OffsetDateTime createdAt) {
    }

        public record PageResult<T>(
                        List<T> items,
                        long total,
                        int page,
                        int pageSize) {
        }
}
