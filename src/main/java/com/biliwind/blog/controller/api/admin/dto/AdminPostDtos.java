package com.biliwind.blog.controller.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class AdminPostDtos {

        private AdminPostDtos() {
        }

        public record PostCreateRequest(
                        @NotBlank(message = "slug 不能为空") String slug,
                        @NotEmpty(message = "title 不能为空") Map<String, String> title,
                        Map<String, String> summary,
                        Map<String, String> aiSummary,
                        @NotEmpty(message = "contentMarkdown 不能为空") Map<String, String> contentMarkdown,
                        Short status,
                        Short visibility,
                        String password,
                        String seoTitle,
                        String seoKeywords,
                        String seoDescription,
                        Short renderType,
                        Short editorType,
                        Long categoryId,
                        List<Long> tagIds) {
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
                        @NotNull(message = "version 不能为空") Integer version,
                        Long categoryId,
                        List<Long> tagIds) {
        }

        public record AdminPostItem(
                        Long id,
                        String slug,
                        Map<String, String> title,
                        Short status,
                        Short visibility,
                        Short renderType,
                        Integer version,
                        Long userId,
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
                        String seoTitle,
                        String seoKeywords,
                        String seoDescription,
                        Short renderType,
                        Short editorType,
                        Integer currentRevisionNumber,
                        Integer version,
                        Long userId,
                        Long categoryId,
                        List<Long> tagIds,
                        OffsetDateTime publishedAt,
                        OffsetDateTime createdAt,
                        OffsetDateTime updatedAt) {
        }

        public record PageResult<T>(
                        List<T> items,
                        long total,
                        int page,
                        int pageSize) {
        }
}
