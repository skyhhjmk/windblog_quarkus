package com.biliwind.blog.service.ai;

import com.fasterxml.jackson.databind.JsonNode;

/** Parses and validates the provider's structured moderation decision. */
final class AiModerationResultParser {
    private AiModerationResultParser() {
    }

    static AiResult parse(JsonNode json, String rawResponse) {
        if (json == null || !json.isObject()) {
            throw new IllegalArgumentException("AI 审核响应不是 JSON 对象");
        }

        JsonNode decision = json.has("isSafe") ? json.get("isSafe")
                : json.has("safe") ? json.get("safe") : json.get("passed");
        if (decision == null || !decision.isBoolean()) {
            throw new IllegalArgumentException("AI 审核响应缺少布尔类型的 isSafe 判定");
        }

        AiResult result = new AiResult();
        result.isSafe = decision.asBoolean();
        JsonNode score = json.get("score");
        if (score != null && !score.isNull()) {
            if (!score.isIntegralNumber() || !score.canConvertToInt()) {
                throw new IllegalArgumentException("AI 审核响应评分不是整数");
            }
            int value = score.asInt();
            if (value < 0 || value > 100) {
                throw new IllegalArgumentException("AI 审核响应评分超出 0 到 100 的范围");
            }
            result.score = value;
        }
        JsonNode reason = json.get("reason");
        if (reason != null && reason.isTextual()) {
            result.reason = reason.asText();
        }
        result.rawResponse = rawResponse;
        return result;
    }
}
