package com.biliwind.blog.filter;

import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.service.PageCachePolicy;
import com.biliwind.blog.service.StaticHtmlCacheService;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Intercepts anonymous public HTML GET requests (both full-page documents and
 * PJAX fragment requests) to serve pre-rendered filesystem static HTML on cache
 * hit, and persists rendered HTML on miss.
 */
@Provider
@Priority(Priorities.USER + 10)
@ApplicationScoped
public class StaticHtmlCacheFilter implements ContainerRequestFilter {

    private static final String PROP_ELIGIBLE = "WINDBLOG_STATIC_CACHE_ELIGIBLE";
    private static final String PROP_KEY = "WINDBLOG_STATIC_CACHE_KEY";
    private static final String PROP_PATH = "WINDBLOG_STATIC_CACHE_PATH";
    private static final String PROP_LANG = "WINDBLOG_STATIC_CACHE_LANG";
    private static final String PROP_REGION = "WINDBLOG_STATIC_CACHE_REGION";
    private static final String PROP_VARIANT = "WINDBLOG_STATIC_CACHE_VARIANT";
    private static final String PROP_PJAX = "WINDBLOG_STATIC_CACHE_PJAX";
    private static final String PROP_HIT = "WINDBLOG_STATIC_CACHE_HIT";
    private static final String MARKDOWN_REQUEST_PROP = MarkdownNegotiationFilter.class.getName();

    @Inject
    StaticHtmlCacheService staticHtmlCacheService;

    @Inject
    Instance<LanguageContext> languageContextInstance;

    @Inject
    Instance<RegionContext> regionContextInstance;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!staticHtmlCacheService.isEnabled()) {
            return;
        }
        if (!"GET".equalsIgnoreCase(requestContext.getMethod())) {
            return;
        }
        if (Boolean.TRUE.equals(requestContext.getProperty(MARKDOWN_REQUEST_PROP))) {
            return;
        }
        if (hasPrivateAuthContext(requestContext)) {
            return;
        }

        String rawPath = requestContext.getUriInfo().getPath();
        String normalizedPath = staticHtmlCacheService.normalizeCacheablePath(
                rawPath, requestContext.getUriInfo().getQueryParameters());
        if (normalizedPath == null) {
            return;
        }

        boolean isPjax = PjaxHelper.isPjaxRequest(requestContext.getHeaders());
        String variant = isPjax ? "pjax" : "full";
        String lang = resolveLanguage(requestContext);
        String region = resolveRegion();
        String cacheKey = staticHtmlCacheService.buildKey(lang, region, variant, normalizedPath);

        StaticHtmlCacheService.CachedDocument cached = staticHtmlCacheService.getIfPresent(cacheKey);
        if (cached != null) {
            requestContext.setProperty(PROP_HIT, Boolean.TRUE);
            if (!isPjax) {
                String ifNoneMatch = requestContext.getHeaderString(HttpHeaders.IF_NONE_MATCH);
                if (ifNoneMatch != null && !ifNoneMatch.isBlank() && etagMatches(ifNoneMatch, cached.etag())) {
                    requestContext.abortWith(Response.notModified(cached.etag())
                            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=60, s-maxage=300")
                            .header(HttpHeaders.VARY, PageCachePolicy.PUBLIC_VARY)
                            .header("X-WindBlog-Static-Cache", "HIT")
                            .header("X-WindBlog-Static-Variant", variant)
                            .build());
                    return;
                }
            }

            Response.ResponseBuilder builder = Response.ok(cached.html())
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                    .header(HttpHeaders.VARY, PageCachePolicy.PUBLIC_VARY)
                    .header("X-WindBlog-Static-Cache", "HIT")
                    .header("X-WindBlog-Static-Variant", variant);

            if (isPjax) {
                // Keep PJAX fragments out of shared CDN/browser caches at the same URL
                builder.header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                        .header("Pragma", "no-cache")
                        .header("Expires", "0");
            } else {
                builder.header(HttpHeaders.CACHE_CONTROL, "public, max-age=60, s-maxage=300")
                        .header(HttpHeaders.ETAG, cached.etag());
            }
            requestContext.abortWith(builder.build());
            return;
        }

        staticHtmlCacheService.recordMiss(cacheKey);
        requestContext.setProperty(PROP_ELIGIBLE, Boolean.TRUE);
        requestContext.setProperty(PROP_KEY, cacheKey);
        requestContext.setProperty(PROP_PATH, normalizedPath);
        requestContext.setProperty(PROP_LANG, lang);
        requestContext.setProperty(PROP_REGION, region);
        requestContext.setProperty(PROP_VARIANT, variant);
        requestContext.setProperty(PROP_PJAX, isPjax);
    }

    @ServerResponseFilter
    public Uni<Void> response(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        if (!Boolean.TRUE.equals(requestContext.getProperty(PROP_ELIGIBLE))
                || Boolean.TRUE.equals(requestContext.getProperty(PROP_HIT))) {
            return Uni.createFrom().voidItem();
        }
        if (responseContext.getStatus() != 200 || !responseContext.hasEntity()) {
            return Uni.createFrom().voidItem();
        }
        if (!isHtmlMediaType(responseContext.getMediaType())) {
            return Uni.createFrom().voidItem();
        }
        boolean isPjax = Boolean.TRUE.equals(requestContext.getProperty(PROP_PJAX));
        if (isPrivateOrUncacheableResponse(responseContext, isPjax)) {
            return Uni.createFrom().voidItem();
        }

        String key = (String) requestContext.getProperty(PROP_KEY);
        String path = (String) requestContext.getProperty(PROP_PATH);
        String lang = (String) requestContext.getProperty(PROP_LANG);
        String region = (String) requestContext.getProperty(PROP_REGION);
        String variant = (String) requestContext.getProperty(PROP_VARIANT);

        Object entity = responseContext.getEntity();
        Uni<String> htmlUni;
        if (entity instanceof String s) {
            htmlUni = Uni.createFrom().item(s);
        } else if (entity instanceof TemplateInstance template) {
            htmlUni = Uni.createFrom().completionStage(template::renderAsync);
        } else {
            return Uni.createFrom().voidItem();
        }

        return htmlUni.invoke(html -> {
            if (html == null || html.isBlank()) {
                return;
            }
            responseContext.setEntity(html, responseContext.getEntityAnnotations(),
                    MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"));
            String existingEtag = responseContext.getHeaderString(HttpHeaders.ETAG);
            staticHtmlCacheService.put(key, path, lang, region, variant, html, existingEtag);

            if (!isPjax) {
                if (responseContext.getHeaderString(HttpHeaders.CACHE_CONTROL) == null) {
                    responseContext.getHeaders().putSingle(HttpHeaders.CACHE_CONTROL, "public, max-age=60, s-maxage=300");
                }
                responseContext.getHeaders().putSingle(HttpHeaders.VARY, PageCachePolicy.PUBLIC_VARY);
            }
            responseContext.getHeaders().putSingle("X-WindBlog-Static-Cache", "MISS");
            responseContext.getHeaders().putSingle("X-WindBlog-Static-Variant", variant);
        }).replaceWithVoid();
    }

    private boolean hasPrivateAuthContext(ContainerRequestContext requestContext) {
        String auth = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (auth != null && !auth.isBlank()) {
            return true;
        }
        Map<String, Cookie> cookies = requestContext.getCookies();
        if (cookies == null || cookies.isEmpty()) {
            return false;
        }
        if (cookies.containsKey("user_token") || cookies.containsKey("admin_jwt")) {
            return true;
        }
        for (String name : cookies.keySet()) {
            if (name != null && name.startsWith("post_access_ticket_")) {
                return true;
            }
        }
        return false;
    }

    private String resolveLanguage(ContainerRequestContext requestContext) {
        Object attr = requestContext.getProperty(LangRequestFilter.LANG_ATTRIBUTE);
        if (attr instanceof String s && !s.isBlank()) {
            return s;
        }
        try {
            if (!languageContextInstance.isUnsatisfied()) {
                String lang = languageContextInstance.get().getLang();
                if (lang != null && !lang.isBlank()) {
                    return lang;
                }
            }
        } catch (Exception ignored) {
        }
        return "zh-CN";
    }

    private String resolveRegion() {
        try {
            if (!regionContextInstance.isUnsatisfied() && regionContextInstance.get().getCurrentRegion() != null) {
                return regionContextInstance.get().getCurrentRegion().name();
            }
        } catch (Exception ignored) {
        }
        return "DEFAULT";
    }

    private static boolean isHtmlMediaType(MediaType mediaType) {
        return mediaType == null || mediaType.isCompatible(MediaType.TEXT_HTML_TYPE);
    }

    private static boolean isPrivateOrUncacheableResponse(ContainerResponseContext responseContext, boolean isPjax) {
        if (responseContext.getHeaderString("X-WindBlog-Private") != null) {
            return true;
        }
        if (!isPjax) {
            String cc = responseContext.getHeaderString(HttpHeaders.CACHE_CONTROL);
            if (cc != null) {
                String lower = cc.toLowerCase(Locale.ROOT);
                if (lower.contains("no-store") || lower.contains("private")) {
                    return true;
                }
            }
        }
        List<Object> setCookies = responseContext.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (setCookies != null && !setCookies.isEmpty()) {
            for (Object cookie : setCookies) {
                String str = String.valueOf(cookie);
                if (str.contains("user_token=") || str.contains("admin_jwt=")
                        || str.contains("post_access_ticket_")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean etagMatches(String ifNoneMatch, String storedEtag) {
        if (storedEtag == null || storedEtag.isBlank()) {
            return false;
        }
        try {
            EntityTag stored = EntityTag.valueOf(storedEtag);
            for (String candidate : ifNoneMatch.split(",")) {
                String trimmed = candidate.trim();
                if ("*".equals(trimmed)) {
                    return true;
                }
                if (EntityTag.valueOf(trimmed).equals(stored)) {
                    return true;
                }
            }
        } catch (IllegalArgumentException ignored) {
            return ifNoneMatch.trim().equals(storedEtag.trim());
        }
        return false;
    }
}
