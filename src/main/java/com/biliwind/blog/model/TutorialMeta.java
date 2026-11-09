package com.biliwind.blog.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * 教程模式专属元数据
 */
@RegisterForReflection
public record TutorialMeta(
        Map<String, String> difficulty,
        Integer estimatedMinutes,
        Map<String, List<String>> prerequisites,
        Map<String, String> targetAudience
) {
    public TutorialMeta {
        if (difficulty == null) {
            difficulty = Map.of();
        }
        if (prerequisites == null) {
            prerequisites = Map.of();
        }
        if (targetAudience == null) {
            targetAudience = Map.of();
        }
    }
}
