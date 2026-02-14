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
            OffsetDateTime createdAt) {
    }

    public record LinkCreateRequest(
            String name,
            String url,
            String description,
            String image,
            String icon,
            Integer sortOrder,
            Short status) {
    }

    public record LinkUpdateRequest(
            String name,
            String url,
            String description,
            String image,
            String icon,
            Integer sortOrder,
            Short status) {
    }
}
