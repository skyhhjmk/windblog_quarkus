package com.biliwind.blog.controller.api.admin.dto;

import java.util.List;

public record AdminInstallRequest(
        String username,
        String email,
        String password,
        String siteTitle,
        String siteSubtitle,
        String siteDescription,
        List<String> siteKeywords,
        String siteAuthor,
        String siteUrl
) {
}
