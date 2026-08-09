package com.biliwind.blog.common.helper;

import io.quarkus.arc.Arc;
import io.quarkus.qute.TemplateGlobal;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Qute 模板全局变量提供者
 */
@ApplicationScoped
public class QuteGlobalProvider {

    /**
     * 获取 CSRF Token
     * 模板中可以通过 {csrfToken} 访问
     * 注意：@TemplateGlobal 方法必须是静态的
     */
    @TemplateGlobal
    public static String csrfToken() {
        try {
            // 动态从 Arc 容器中获取当前的 HttpServerRequest
            HttpServerRequest request = Arc.container().instance(HttpServerRequest.class).get();
            if (request == null) {
                return "";
            }
            var cookie = request.getCookie("XSRF-TOKEN");
            return cookie != null ? cookie.getValue() : "";
        } catch (Exception e) {
            // 如果不在请求上下文中，返回空
            return "";
        }
    }

    @TemplateGlobal
    public static String cspNonce() {
        try {
            com.biliwind.blog.context.CspNonceContext context =
                    Arc.container().instance(com.biliwind.blog.context.CspNonceContext.class).get();
            return context.getNonce();
        } catch (Exception exception) {
            return "";
        }
    }

    @TemplateGlobal
    public static com.biliwind.blog.common.helper.LanguageHelper LanguageHelper() {
        return new com.biliwind.blog.common.helper.LanguageHelper();
    }

    @TemplateGlobal
    public static com.biliwind.blog.common.helper.MarkdownHelper MarkdownHelper() {
        return new com.biliwind.blog.common.helper.MarkdownHelper();
    }

    @TemplateGlobal
    public static String defaultCanonicalUrl() {
        String baseUrl = ConfigProvider.getConfig()
                .getOptionalValue("windblog.site.public-url", String.class)
                .orElse("http://localhost:8080");
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String requestPath = "/";
        try {
            HttpServerRequest request = Arc.container().instance(HttpServerRequest.class).get();
            if (request != null && request.path() != null && !request.path().isBlank()) {
                requestPath = request.path();
            }
        } catch (Exception exception) {
            requestPath = "/";
        }

        if (!requestPath.startsWith("/")) {
            requestPath = "/" + requestPath;
        }
        return baseUrl + requestPath;
    }
}
