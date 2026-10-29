package com.biliwind.blog.controller.api.admin.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class AdminTranslationDtos {

    private AdminTranslationDtos() {
    }

    public record TranslationItem(
            Long id,
            String slug,
            Map<String, String> title,
            Short status,
            Boolean hasPublishedRevision,
            Boolean currentRevisionPublished,
            Integer revisionNumber,
            List<String> languages,
            String sourceLanguage,
            Boolean translated,
            OffsetDateTime publishedAt,
            OffsetDateTime updatedAt) {
    }

    public record TranslationPage(
            List<TranslationItem> items,
            long total,
            int page,
            int pageSize,
            String targetLanguage) {
    }

    public record TranslatePostRequest(
            String targetLanguage,
            String sourceLanguage,
            Boolean overwrite) {
    }

    public record TranslatePostResult(
            Long postId,
            String slug,
            String sourceLanguage,
            String targetLanguage,
            boolean translated,
            boolean skipped,
            boolean published,
            Integer revisionNumber,
            String message,
            String title,
            String summary,
            String contentMarkdown) {
    }

    public record BatchTranslateRequest(
            List<Long> postIds,
            String targetLanguage,
            String sourceLanguage,
            Boolean overwrite) {
    }

    public record SubmitTranslationTasksRequest(
            List<Long> postIds,
            String targetLanguage,
            String sourceLanguage,
            Boolean overwrite) {
    }

    public record TranslationTaskView(
            Long id,
            Long postId,
            String targetLanguage,
            String sourceLanguage,
            boolean overwrite,
            String status,
            String message,
            Integer revisionNumber,
            boolean published,
            int attempts,
            String batchId,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt) {
    }

    public record TranslationBatchView(
            String batchId,
            List<TranslationTaskView> tasks,
            int total,
            int queued,
            int running,
            int succeeded,
            int skipped,
            int failed) {
    }

    public record BatchTranslateResult(
            int requested,
            int translated,
            int skipped,
            int failed,
            List<TranslatePostResult> results,
            List<String> errors) {
    }
}
