package com.biliwind.blog.controller.api.admin.dto;

import java.time.OffsetDateTime;
import java.util.Map;

public class AdminCategoryDtos {

    public record AdminCategoryItem(
            Long id,
            Long parentId,
            String slug,
            Map<String, String> name,
            Map<String, String> description,
            String path,
            OffsetDateTime createdAt) {
    }

    public record CategoryCreateRequest(
            Long parentId,
            String slug,
            Map<String, String> name,
            Map<String, String> description) {
    }

    public record CategoryUpdateRequest(
            Long parentId,
            String slug,
            Map<String, String> name,
            Map<String, String> description) {
    }
}
