package com.biliwind.blog.service;

import com.biliwind.blog.model.PostAiMetadata;
import com.biliwind.blog.service.ai.AiResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

@ApplicationScoped
public class PostAiMetadataService {
    @Inject
    ObjectMapper objectMapper;

    @Transactional
    public void record(Long postId, Long revisionId, String operation, AiResult result,
                       String autoPublishStatus) {
        if (postId == null || operation == null || result == null) return;
        PostAiMetadata metadata = new PostAiMetadata();
        metadata.postId = postId;
        metadata.revisionId = revisionId;
        metadata.taskId = result.taskId;
        metadata.operation = operation;
        metadata.provider = result.provider == null ? "AI_MANAGER" : result.provider;
        metadata.modelId = result.modelId;
        metadata.reasoningEffort = result.reasoningEffort;
        metadata.generationMode = result.generationMode == null ? "MANUAL" : result.generationMode;
        metadata.provenance = json(result.provenance);
        metadata.autoPublishStatus = autoPublishStatus == null ? "DRAFT" : autoPublishStatus;
        metadata.createdAt = OffsetDateTime.now();
        metadata.persist();
    }

    private String json(Map<String, Object> provenance) {
        try {
            return objectMapper.writeValueAsString(provenance == null ? Map.of() : provenance);
        } catch (Exception ignored) {
            return "{}";
        }
    }
}
