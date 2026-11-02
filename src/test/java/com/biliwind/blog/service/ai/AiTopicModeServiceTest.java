package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiConfigType;
import com.biliwind.blog.model.AiProviderConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiTopicModeServiceTest {

    @Test
    void usesLocalPoolWhenCodexCreatorIsNotConfigured() {
        AiTopicModeService service = service(null, false);
        assertTrue(service.localMode(),
                "没有 Codex 共享密钥时不能继续依赖 Codex 话题池");
    }

    @Test
    void usesLocalPoolWhenTopicOperationUsesNormalProviderEvenIfCodexIsConfigured() {
        AiTopicModeService service = service(provider(AiConfigType.PROVIDER, "OPENAI", true), true);
        assertTrue(service.localMode(), "话题操作指定普通 AI 提供商时应使用本地话题池");
    }

    @Test
    void usesLocalPoolForPollingGroup() {
        AiTopicModeService service = service(provider(AiConfigType.POLLING_GROUP, "OPENAI", true), true);
        assertTrue(service.localMode(), "话题操作指定轮询组时应使用本地话题池");
    }

    @Test
    void keepsCodexPoolWhenTopicOperationUsesCodexCreator() {
        AiTopicModeService service = service(
                provider(AiConfigType.PROVIDER, "CODEX_CREATOR", true), true);
        assertFalse(service.localMode());
    }

    @Test
    void keepsCodexPoolWhenTopicOperationIsAutomaticAndCodexIsConfigured() {
        assertFalse(service(null, true).localMode());
    }

    @Test
    void ignoresDisabledTopicProvider() {
        AiTopicModeService service = service(provider(AiConfigType.PROVIDER, "OPENAI", false), true);
        assertFalse(service.localMode());
    }

    private static AiTopicModeService service(AiProviderConfig provider, boolean codexConfigured) {
        return new AiTopicModeService() {
            @Override
            public AiProviderConfig configuredTopicProvider() {
                return provider;
            }

            @Override
            boolean codexCreatorConfigured() {
                return codexConfigured;
            }
        };
    }

    private static AiProviderConfig provider(AiConfigType type, String name, boolean enabled) {
        AiProviderConfig config = new AiProviderConfig();
        config.type = type;
        config.provider = name;
        config.enabled = enabled;
        return config;
    }
}
