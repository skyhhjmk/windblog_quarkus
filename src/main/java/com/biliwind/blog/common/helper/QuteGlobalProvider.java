package com.biliwind.blog.common.helper;

import io.quarkus.arc.Arc;
import io.quarkus.qute.TemplateGlobal;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.i18n.I18n;
import com.biliwind.blog.service.CanonicalUrlService;

import java.util.ArrayList;
import java.util.List;

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
    public static I18n i18n() {
        return Arc.container().instance(I18n.class).get();
    }

    @TemplateGlobal
    public static String language() {
        return languageContext().getLang();
    }

    @TemplateGlobal
    public static String defaultLanguage() {
        return languageContext().getDefaultLang();
    }

    @TemplateGlobal
    public static String languagePrefix() {
        LanguageContext context = languageContext();
        return LanguageHelper.prefixFor(context.getLang(), context.getDefaultLang());
    }

    @TemplateGlobal
    public static String currentPath() {
        return languageContext().getPath();
    }

    @TemplateGlobal
    public static String languageLabel() {
        I18n i18n = i18n();
        return i18n.t("language." + languageContext().getLang());
    }

    @TemplateGlobal
    public static List<LanguageOption> languageOptions() {
        LanguageContext context = languageContext();
        I18n i18n = i18n();
        String query = context.getQuery();
        List<LanguageOption> options = new ArrayList<>();
        for (String code : LanguageConstant.SUPPORTED_LANG_ORDER) {
            String url = LanguageHelper.localizedPath(context.getPath(), code, context.getDefaultLang());
            String targetQuery = query == null ? "" : query;
            boolean targetIsDefault = code.equalsIgnoreCase(context.getDefaultLang());
            boolean targetIsCurrent = code.equalsIgnoreCase(context.getLang());
            // The unprefixed URL is only "explicit" for SEO, not for language
            // selection: an existing user_lang_v2 cookie would win. Add ?lang
            // when switching from another language back to the region default.
            if (targetIsDefault && !targetIsCurrent) {
                targetQuery = targetQuery.isBlank()
                        ? "lang=" + code
                        : targetQuery + "&lang=" + code;
            }
            if (!targetQuery.isBlank()) {
                url = url + "?" + targetQuery;
            }
            options.add(new LanguageOption(code, i18n.t("language." + code), url,
                    targetIsCurrent));
        }
        return options;
    }

    private static LanguageContext languageContext() {
        return com.biliwind.blog.i18n.LanguageRequestResolver.current();
    }

    public record LanguageOption(String code, String label, String url, boolean active) {
    }

    @TemplateGlobal
    public static com.biliwind.blog.common.helper.MarkdownHelper MarkdownHelper() {
        return new com.biliwind.blog.common.helper.MarkdownHelper();
    }

    @TemplateGlobal
    public static String canonicalBaseUrl() {
        return Arc.container().instance(CanonicalUrlService.class).get().getBaseUrl();
    }

    @TemplateGlobal
    public static String defaultCanonicalUrl() {
        String baseUrl = canonicalBaseUrl();
        String requestPath = "/";
        String language = com.biliwind.blog.common.constant.LanguageConstant.DEFAULT_LANG;
        String defaultLanguage = com.biliwind.blog.common.constant.LanguageConstant.DEFAULT_LANG;
        try {
            HttpServerRequest request = Arc.container().instance(HttpServerRequest.class).get();
            if (request != null && request.path() != null && !request.path().isBlank()) {
                requestPath = request.path();
            }
            com.biliwind.blog.context.LanguageContext context =
                    Arc.container().instance(com.biliwind.blog.context.LanguageContext.class).get();
            language = context.getLang();
            defaultLanguage = context.getDefaultLang();
        } catch (Exception exception) {
            requestPath = "/";
        }

        if (!requestPath.startsWith("/")) {
            requestPath = "/" + requestPath;
        }
        return baseUrl + LanguageHelper.localizedPath(requestPath, language, defaultLanguage);
    }
    @TemplateGlobal
    public static java.util.List<com.biliwind.blog.service.SeoPageService.Alternate> seoAlternates() {
        HttpServerRequest request = Arc.container().instance(HttpServerRequest.class).get();
        return Arc.container().instance(com.biliwind.blog.service.SeoPageService.class).get().alternates(request.path());
    }

    @TemplateGlobal
    public static String seoRobots() {
        HttpServerRequest request = Arc.container().instance(HttpServerRequest.class).get();
        return Arc.container().instance(com.biliwind.blog.service.SeoManagementService.class).get().robots(request.path());
    }
}
