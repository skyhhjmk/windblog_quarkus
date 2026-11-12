package com.biliwind.blog.service;

import com.biliwind.blog.common.helper.PjaxHelper;
import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;

/**
 * Renders a public page from the one model that also produced its validator.
 * The caller names a {@link PageView}; whether the response is the complete
 * document or the PJAX fragment is decided here from the request headers.
 */
@ApplicationScoped
public class PageRenderer {

    @Inject
    Engine engine;

    public TemplateInstance render(PageView view, HttpHeaders headers, PageRenderModel model) {
        return model.render(template(view, PjaxHelper.isPjaxRequest(headers)));
    }

    public TemplateInstance render(PageView view, MultivaluedMap<String, String> headers,
                                   PageRenderModel model) {
        return model.render(template(view, PjaxHelper.isPjaxRequest(headers)));
    }

    Template template(PageView view, boolean fragment) {
        String id = fragment ? view.fragmentId() : view.documentId();
        Template template = engine.getTemplate(id);
        if (template == null) {
            throw new IllegalStateException("Qute template is not registered: " + id);
        }
        return template;
    }
}
