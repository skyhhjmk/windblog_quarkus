package com.biliwind.blog.controller.api.admin.dto;

import java.util.List;

public record AiTestRequest(String prompt, String systemPrompt, boolean enableDeepThinking, List<String> imageUrls,
                            boolean stream) {
    public AiTestRequest {
        // Handle defaults if needed, though records don't have easy defaults without custom static factor methods
    }
}
