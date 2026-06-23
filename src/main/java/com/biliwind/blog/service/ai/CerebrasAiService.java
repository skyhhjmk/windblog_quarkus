package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProviderConfig;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Cerebras.ai 专属 AI 服务实现类。
 * 继承自 OpenAiAiService，因为 Cerebras 提供完全兼容 OpenAI 规范的 Chat Completions API。
 */
@ApplicationScoped
public class CerebrasAiService extends OpenAiAiService {

    @Override
    public boolean supports(AiProviderConfig config) {
        if (config == null) {
            return false;
        }
        return "CEREBRAS".equalsIgnoreCase(config.provider);
    }

    @Override
    protected URI resolveUri(AiProviderConfig config) {
        String endpoint = config.endpoint;
        // 如果用户未显式配置接口地址，我们将默认采用 Cerebras.ai 的官方 API Endpoint
        if (endpoint == null || endpoint.isBlank()) {
            return URI.create("https://api.cerebras.ai/v1/chat/completions");
        }

        String base = endpoint.trim();
        // 自动补齐缺失的 /v1 路径段，增加对非标输入的兼容性
        if (!base.contains("/v1") && (base.startsWith("https://api.cerebras.ai") || base.startsWith("http://api.cerebras.ai"))) {
            if (base.endsWith("/")) {
                base = base + "v1";
            } else {
                base = base + "/v1";
            }
        }

        if (base.endsWith("/chat/completions")) {
            return URI.create(base);
        }
        if (base.endsWith("/")) {
            return URI.create(base + "chat/completions");
        }
        return URI.create(base + "/chat/completions");
    }

    @Override
    protected String chooseModel(AiProviderConfig config, String defaultModel) {
        // 如果未填模型，则选用 llama-3.3-70b 作为默认的高性价比模型
        if (config.model == null || config.model.isBlank()) {
            return "llama-3.3-70b";
        }
        return config.model;
    }

    @Override
    public CompletionStage<List<String>> fetchModels(AiProviderConfig config) {
        // 因为 fetchModels 内部会根据配置中的 endpoint 拼接并请求模型列表，
        // 同样在 endpoint 为空时，我们需要在此指定 Cerebras.ai 的 Base URL "https://api.cerebras.ai/v1"
        AiProviderConfig tempConfig = new AiProviderConfig();
        tempConfig.id = config.id;
        tempConfig.type = config.type;
        tempConfig.name = config.name;
        tempConfig.enabled = config.enabled;
        tempConfig.provider = config.provider;
        tempConfig.apiKey = config.apiKey;
        tempConfig.model = config.model;
        tempConfig.config = config.config;
        tempConfig.createdAt = config.createdAt;
        tempConfig.updatedAt = config.updatedAt;

        if (config.endpoint == null || config.endpoint.isBlank()) {
            tempConfig.endpoint = "https://api.cerebras.ai/v1";
        } else {
            String base = config.endpoint.trim();
            // 自动补齐缺失的 /v1 路径段，增加对非标输入的兼容性
            if (!base.contains("/v1") && (base.startsWith("https://api.cerebras.ai") || base.startsWith("http://api.cerebras.ai"))) {
                if (base.endsWith("/")) {
                    base = base + "v1";
                } else {
                    base = base + "/v1";
                }
            }
            tempConfig.endpoint = base;
        }
        return super.fetchModels(tempConfig);
    }
}
