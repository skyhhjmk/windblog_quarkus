package com.biliwind.blog.config;

import com.biliwind.blog.context.LanguageContext;
import io.quarkus.qute.TemplateInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Global configuration for Qute templates.
 * This ensures that common variables like 'language' are available in all templates.
 */
@ApplicationScoped
public class QuteConfig {

    @Inject
    LanguageContext languageContext;

    /**
     * Observes every TemplateInstance before rendering and adds global data.
     */
    void onTemplateInstance(@Observes TemplateInstance instance) {
        instance.data("language", languageContext.getLang());
        instance.data("LanguageHelper", new com.biliwind.blog.common.helper.LanguageHelper());
        instance.data("MarkdownHelper", new com.biliwind.blog.common.helper.MarkdownHelper());
    }
}
