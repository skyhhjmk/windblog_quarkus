package com.biliwind.blog.service;

import java.util.Map;

public record CodexCreatorIntegrationEvent(String eventType, String aggregateType,
                                           String aggregateId, Map<String, Object> input,
                                           String idempotencyKey, String traceId,
                                           String profileId, String promptVersion) {
}
