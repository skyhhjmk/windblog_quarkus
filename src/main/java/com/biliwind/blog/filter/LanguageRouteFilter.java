package com.biliwind.blog.filter;

import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.service.LanguageSettingsService;
import com.biliwind.blog.service.RegionRuleService;
import io.quarkus.vertx.http.runtime.filters.Filters;
import jakarta.inject.Inject;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Strips a leading language segment ({@code /en-US/...}, {@code /zh-cn/...})
 * before REST routing and passes the language to {@link LangRequestFilter}.
 * The browser keeps the prefixed URL; only the internal route changes, so
 * every existing public route gains language-prefixed URLs without a second
 * controller method for each page.
 */
@ApplicationScoped
public class LanguageRouteFilter {

    public static final String LANG_HEADER = "X-Request-Lang";
    public static final String DEFAULT_LANG_HEADER = "X-Windblog-Default-Lang";
    private static final String REROUTED_HEADER = "X-Windblog-Lang-Rerouted";

    @Inject
    RegionRuleService regionRuleService;

    @Inject
    LanguageSettingsService languageSettingsService;

    void init(@Observes Filters filters) {
        filters.register(this::handle, 1000);
    }

    private void applyDefaultLanguageHeader(RoutingContext context, String path) {
        if (path.startsWith("/assets/") || path.startsWith("/q/") || path.startsWith("/api/")
                || path.startsWith("/uploads/") || path.startsWith("/static/")
                || path.startsWith("/.well-known/") || path.startsWith("/favicon")
                || path.startsWith("/robots.txt") || path.startsWith("/sitemap")
                || path.startsWith("/feed") || path.startsWith("/ads.txt")) {
            return;
        }
        String host = context.request().getHeader("Host");
        java.util.List<String> languages = new java.util.ArrayList<>();
        String acceptLanguage = context.request().getHeader("Accept-Language");
        if (acceptLanguage != null) {
            for (String part : acceptLanguage.split(",")) {
                String tag = part.split(";")[0].trim();
                if (!tag.isEmpty()) {
                    languages.add(tag);
                }
            }
        }
        BlogRegion region = regionRuleService.resolveRegionCached(host, languages);
        String defaultLanguage = languageSettingsService.defaultLanguageCached(region);
        context.request().headers().set(DEFAULT_LANG_HEADER, defaultLanguage);
    }

    private void handle(RoutingContext context) {
        HttpMethod method = context.request().method();
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
            context.next();
            return;
        }
        if (context.request().headers().contains(REROUTED_HEADER)) {
            context.next();
            return;
        }

        String path = context.request().path();
        if (path == null || path.length() < 1 || path.charAt(0) != '/') {
            context.next();
            return;
        }

        applyDefaultLanguageHeader(context, path);

        int slash = path.indexOf('/', 1);
        String firstSegment = slash < 0 ? path.substring(1) : path.substring(1, slash);
        String language = LanguageHelper.normalizeToSupportedLang(firstSegment);
        if (language == null) {
            context.next();
            return;
        }

        String target = slash < 0 ? "/" : path.substring(slash);
        String query = context.request().query();
        if (query != null && !query.isEmpty()) {
            target = target + "?" + query;
        }

        context.request().headers().set(LANG_HEADER, language);
        context.request().headers().set(REROUTED_HEADER, "1");
        context.reroute(method, target);
    }
}
