package com.biliwind.blog.controller.api.admin.dto;

import java.time.OffsetDateTime;
import java.util.Map;

public class AdminTagDtos {

    public record AdminTagItem(
            Long id,
            String slug,
            Map<String, String> name,
            Map<String, String> description,
            OffsetDateTime createdAt) {
    }

    public record TagCreateRequest(
            String slug,
            Map<String, String> name,
            Map<String, String> description) {
    }

    public record TagUpdateRequest(
            String slug,
            Map<String, String> name,
            Map<String, String> description) {
    }
}
