package com.biliwind.blog.controller.api.admin.dto;

import java.util.List;

public record AiTestRequest(String prompt, String systemPrompt, boolean enableDeepThinking, List<String> imageUrls) {
}
