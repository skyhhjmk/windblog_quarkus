package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProviderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 模型级能力元数据（上下文大小、输入模态、输出模态），存储在 AiProviderConfig.config.modelCapabilities 中。
 */
public record AiModelCapability(
        Integer contextWindow,
        List<String> inputModalities,
        List<String> outputModalities
) {
    public static final List<String> SUPPORTED_MODALITIES = List.of("TEXT", "IMAGE", "AUDIO", "VIDEO");

    public boolean supportsInput(String modality) {
        if (modality == null || inputModalities == null) {
            return false;
        }
        String target = modality.trim().toUpperCase(Locale.ROOT);
        return inputModalities.stream().anyMatch(m -> target.equalsIgnoreCase(m));
    }

    public boolean supportsOutput(String modality) {
        if (modality == null || outputModalities == null) {
            return false;
        }
        String target = modality.trim().toUpperCase(Locale.ROOT);
        return outputModalities.stream().anyMatch(m -> target.equalsIgnoreCase(m));
    }

    /**
     * 解析指定提供商配置当前绑定模型（或指定模型名）的能力信息；未配置时返回 null。
     */
    public static AiModelCapability resolve(AiProviderConfig config, String modelName, ObjectMapper objectMapper) {
        if (config == null || config.config == null || config.config.isBlank() || objectMapper == null) {
            return null;
        }
        String targetModel = (modelName != null && !modelName.isBlank()) ? modelName.trim() : config.model;
        if (targetModel == null || targetModel.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(config.config);
            JsonNode capabilities = root.get("modelCapabilities");
            if (capabilities == null || !capabilities.isObject()) {
                return null;
            }
            JsonNode entry = capabilities.get(targetModel);
            if (entry == null || !entry.isObject()) {
                return null;
            }
            Integer contextWindow = entry.hasNonNull("contextWindow") && entry.get("contextWindow").isNumber()
                    ? entry.get("contextWindow").asInt()
                    : null;
            List<String> inputs = parseModalities(entry.get("inputModalities"));
            List<String> outputs = parseModalities(entry.get("outputModalities"));
            return new AiModelCapability(contextWindow, inputs, outputs);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<String> parseModalities(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of("TEXT");
        }
        List<String> list = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual()) {
                String normalized = item.asText().trim().toUpperCase(Locale.ROOT);
                if (!normalized.isEmpty() && !list.contains(normalized)) {
                    list.add(normalized);
                }
            }
        }
        return list.isEmpty() ? List.of("TEXT") : List.copyOf(list);
    }
}
