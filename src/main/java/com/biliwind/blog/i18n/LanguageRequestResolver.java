package com.biliwind.blog.i18n;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.filter.LanguageRouteFilter;
import io.quarkus.arc.Arc;
import io.vertx.core.http.HttpServerRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the request language even when the JAX-RS filters did not run,
 * for example on a 404 or an exception raised before resource matching.
 * The Vert.x route filter already computed the region default language and
 * stored it on the request, so the error page can render in the selected or
 * regional default language instead of falling back to zh-cn.
 */
public final class LanguageRequestResolver {

    private LanguageRequestResolver() {
    }

    public static LanguageContext current() {
        LanguageContext context;
        try {
            context = Arc.container().instance(LanguageContext.class).get();
        } catch (Exception exception) {
            return new LanguageContext();
        }
        if (context == null || context.isResolved()) {
            return context;
        }
        try {
            HttpServerRequest request = Arc.container().instance(HttpServerRequest.class).get();
            String defaultLanguage = LanguageHelper.normalizeToSupportedLang(
                    request.getHeader(LanguageRouteFilter.DEFAULT_LANG_HEADER));
            if (defaultLanguage == null) {
                defaultLanguage = LanguageConstant.DEFAULT_LANG;
            }
            String selected = LanguageHelper.normalizeToSupportedLang(
                    request.getHeader(LanguageRouteFilter.LANG_HEADER));
            context.setDefaultLang(defaultLanguage);
            context.setLang(selected != null ? selected : defaultLanguage);
            context.setPath(normalizePath(request.path()));
            context.setQuery(cleanQuery(request.query()));
            context.setContentLanguage(context.getLang());
            context.setResolved(true);
        } catch (Exception exception) {
            context.setResolved(true);
        }
        return context;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String cleanQuery(String rawQuery) {
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
    }
}
