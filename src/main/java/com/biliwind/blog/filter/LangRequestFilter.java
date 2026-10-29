package com.biliwind.blog.filter;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.service.LanguageSettingsService;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

import java.util.ArrayList;
import java.util.List;

@Provider
public class LangRequestFilter implements ContainerRequestFilter {

    @Inject
    Instance<LanguageContext> languageContextInstance;

    @Inject
    RegionContext regionContext;

    @Inject
    LanguageSettingsService languageSettingsService;

    public static final String LANG_ATTRIBUTE = "REQUEST_LANG";
    public static final String LANG_EXPLICIT_ATTRIBUTE = "REQUEST_LANG_EXPLICIT";
    private static final String LANG_HEADER = LanguageRouteFilter.LANG_HEADER;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        boolean explicit = false;

        // 0) Language-prefixed URL handled by LanguageRouteFilter
        String lang = LanguageHelper.normalizeToSupportedLang(
                requestContext.getHeaders().getFirst(LANG_HEADER));
        explicit = lang != null;

        // 1) URL 路径语言前缀（直接调用过滤器时的兜底，例如测试）
        if (lang == null) {
            lang = LanguageHelper.resolveLangFromPath(requestContext.getUriInfo().getPath());
            explicit = lang != null;
        }

        // 2) query 参数 ?lang=...
        if (lang == null) {
            List<String> langParams = requestContext.getUriInfo().getQueryParameters().get("lang");
            if (langParams != null && !langParams.isEmpty()) {
                lang = LanguageHelper.normalizeToSupportedLang(langParams.getFirst());
                explicit = lang != null;
            }
        }

        // 3) Cookie（只由用户显式切换写入）
        if (lang == null) {
            String cookieLang = readCookieLang(requestContext);
            lang = LanguageHelper.normalizeToSupportedLang(cookieLang);
        }

        String defaultLang = LanguageHelper.normalizeToSupportedLang(
                requestContext.getHeaders().getFirst(LanguageRouteFilter.DEFAULT_LANG_HEADER));
        if (defaultLang == null) {
            // Cache-only read: request filters may run on an IO thread for
            // non-blocking endpoints and must never hit the database here.
            defaultLang = languageSettingsService.defaultLanguageCached(
                    regionContext.getCurrentRegion());
        }

        // 4) 区域默认语言（.com/global = en-US, .cn = zh-cn）
        if (lang == null) {
            lang = defaultLang;
        }

        // 5) Accept-Language（按 q 值排序），仅在区域未配置默认语言时生效
        if (lang == null) {
            lang = LanguageHelper.resolveFromAcceptLanguage(
                    requestContext.getHeaders().getFirst(LanguageConstant.HEADER_ACCEPT_LANG));
        }

        if (lang == null || !LanguageConstant.SUPPORT_LANGS.contains(lang)) {
            lang = LanguageConstant.DEFAULT_LANG;
        }

        try {
            if (!languageContextInstance.isUnsatisfied()) {
                LanguageContext languageContext = languageContextInstance.get();
                languageContext.setLang(lang);
                languageContext.setDefaultLang(defaultLang);
                languageContext.setExplicit(explicit);
                languageContext.setPath(rawPath(requestContext));
                languageContext.setQuery(cleanQuery(requestContext));
                languageContext.setContentLanguage(lang);
                languageContext.setResolved(true);
            }
        } catch (Exception e) {
            // 忽略异常
        }
        requestContext.getHeaders().putSingle(LanguageConstant.HEADER_REQUEST_LANG, lang);
        requestContext.setProperty(LANG_ATTRIBUTE, lang);
        requestContext.setProperty(LANG_EXPLICIT_ATTRIBUTE, explicit);
    }

    private String readCookieLang(ContainerRequestContext requestContext) {
        try {
            var cookie = requestContext.getCookies().get(LanguageConstant.COOKIE_LANG_KEY);
            return cookie == null ? null : cookie.getValue();
        } catch (Exception exception) {
            return null;
        }
    }

    private String rawPath(ContainerRequestContext requestContext) {
        try {
            String path = requestContext.getUriInfo().getRequestUri().getRawPath();
            return path == null || path.isBlank() ? "/" : path;
        } catch (Exception exception) {
            return "/";
        }
    }

    private String cleanQuery(ContainerRequestContext requestContext) {
        try {
            String rawQuery = requestContext.getUriInfo().getRequestUri().getRawQuery();
            if (rawQuery == null || rawQuery.isBlank()) {
                return "";
            }
            List<String> kept = new ArrayList<>();
            for (String part : rawQuery.split("&")) {
                if (part.isBlank() || part.toLowerCase(java.util.Locale.ROOT).startsWith("lang=")) {
                    continue;
                }
                kept.add(part);
            }
            return String.join("&", kept);
        } catch (Exception exception) {
            return "";
        }
    }
}
