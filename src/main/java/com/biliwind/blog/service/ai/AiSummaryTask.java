package com.biliwind.blog.service.ai;

import java.util.Map;

public record AiSummaryTask(
        Long postId,
        Map<String, String> content,
        int priority // 0: High, 1: Medium, 2: Low
) {
}
