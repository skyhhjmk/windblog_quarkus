package com.biliwind.blog.service;

import com.biliwind.blog.service.SystemEmailTemplateCatalog.EmailTemplatePreviewResponse;
import com.biliwind.blog.service.SystemEmailTemplateCatalog.SystemEmailTemplateItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemEmailTemplateCatalogTest {

    @Test
    void listsBuiltInSystemTemplatesAndRendersPreviews() {
        SystemEmailTemplateCatalog catalog = new SystemEmailTemplateCatalog();
        catalog.emailTemplateRenderer = new EmailTemplateRenderer();

        List<SystemEmailTemplateItem> templates = catalog.listAll();
        assertFalse(templates.isEmpty());
        assertTrue(templates.stream().allMatch(t -> t.system() && !t.editable()));
        assertTrue(templates.stream().anyMatch(t -> "FRIEND_LINK_SITE_UPDATE".equals(t.templateKey())));
        assertTrue(templates.stream().anyMatch(t -> "ARTICLE_UPDATE".equals(t.templateKey())));
        assertTrue(templates.stream().anyMatch(t -> "COMMENT_REPLY".equals(t.templateKey())));
        assertTrue(templates.stream().anyMatch(t -> "ADMIN_COMMENT_NOTICE".equals(t.templateKey())));

        for (SystemEmailTemplateItem template : templates) {
            EmailTemplatePreviewResponse preview = catalog.renderPreview(template.templateKey());
            assertNotNull(preview);
            assertNotNull(preview.html());
            assertTrue(preview.html().contains("<html"));
        }

        EmailTemplatePreviewResponse siteUpdatePreview = catalog.renderPreview("FRIEND_LINK_SITE_UPDATE");
        assertTrue(siteUpdatePreview.html().contains("自您添加我站友链以来，我站信息已经变更，这是最新信息："));
        assertTrue(siteUpdatePreview.html().contains("以下是附加备注："));
    }
}
