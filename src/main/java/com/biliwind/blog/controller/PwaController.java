package com.biliwind.blog.controller;

import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.i18n.I18n;
import com.biliwind.blog.service.ConfigTemplateData;
import com.biliwind.blog.service.FaviconResolverService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Progressive Web App (PWA) & Service Worker support endpoints:
 * - {@code GET /manifest.webmanifest}: Web App Manifest with dynamic site branding.
 * - {@code GET /offline}: Branded offline fallback page & PJAX fragment shell.
 */
@Path("/")
@Blocking
public class PwaController {

    private static final String MANIFEST_MEDIA_TYPE = "application/manifest+json; charset=UTF-8";

    @Inject
    @Location("system/offline.html")
    Template offlinePage;

    @Inject
    @Location("system/offline.content.html")
    Template offlineContent;

    @Inject
    LanguageContext languageContext;

    @Inject
    I18n i18n;

    @Inject
    FaviconResolverService faviconResolverService;

    @GET
    @Path("/manifest.webmanifest")
    @Produces(MANIFEST_MEDIA_TYPE)
    public Response manifest() {
        String siteTitle = resolveSiteTitle();
        String siteDescription = resolveSiteDescription();
        String themeColor = resolveThemeColor();
        String language = languageContext != null && languageContext.getLang() != null
                ? languageContext.getLang()
                : "zh-CN";
        String startUrl = languageContext != null && languageContext.homeUrl() != null
                ? languageContext.homeUrl()
                : "/";

        List<Map<String, String>> icons = new ArrayList<>();
        Map<String, String> defaultSvgIcon = new LinkedHashMap<>();
        defaultSvgIcon.put("src", "/favicon.svg");
        defaultSvgIcon.put("sizes", "any");
        defaultSvgIcon.put("type", "image/svg+xml");
        defaultSvgIcon.put("purpose", "any maskable");
        icons.add(defaultSvgIcon);

        String configuredFavicon = faviconResolverService != null
                ? faviconResolverService.resolveFaviconUrl()
                : "";
        if (configuredFavicon != null && !configuredFavicon.isBlank()
                && !"/favicon.svg".equalsIgnoreCase(configuredFavicon.trim())) {
            Map<String, String> customIcon = new LinkedHashMap<>();
            customIcon.put("src", configuredFavicon.trim());
            customIcon.put("sizes", "192x192 512x512");
            customIcon.put("purpose", "any");
            icons.add(customIcon);
        }

        String json = buildManifestJson(siteTitle, siteDescription, themeColor, language, startUrl, icons);
        return Response.ok(json)
                .header("Content-Type", MANIFEST_MEDIA_TYPE)
                .header("Cache-Control", "public, max-age=3600")
                .build();
    }

    @GET
    @Path("/offline")
    @Produces(MediaType.TEXT_HTML)
    public Response offline(@Context HttpHeaders httpHeaders) {
        boolean pjaxRequest = PjaxHelper.isPjaxRequest(httpHeaders);
        String language = languageContext != null && languageContext.getLang() != null
                ? languageContext.getLang()
                : "zh-CN";
        String offlineTitle = i18n != null ? i18n.t("offline.title") : "Offline Mode";
        String siteTitle = resolveSiteTitle();
        String pageTitle = (siteTitle != null && !siteTitle.isBlank())
                ? offlineTitle + " - " + siteTitle
                : offlineTitle;
        String pageDescription = i18n != null ? i18n.t("offline.description") : "";

        Template targetTemplate = pjaxRequest ? offlineContent : offlinePage;
        String renderedHtml = targetTemplate
                .data("pageTitle", pageTitle)
                .data("pageDescription", pageDescription)
                .data("language", language)
                .data("pjaxRequest", pjaxRequest)
                .data("seoRobots", "noindex,follow")
                .render();

        return Response.ok(renderedHtml)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .header("Cache-Control", "no-cache")
                .header("X-Robots-Tag", "noindex, follow")
                .build();
    }

    private String resolveSiteTitle() {
        try {
            if (io.quarkus.arc.Arc.container() != null) {
                String title = ConfigTemplateData.siteTitle();
                if (title != null && !title.isBlank()) {
                    return title;
                }
            }
        } catch (RuntimeException ignored) {
            // Fall back to default title when CDI container is not active
        }
        return "WindBlog";
    }

    private String resolveSiteDescription() {
        try {
            if (io.quarkus.arc.Arc.container() != null) {
                String description = ConfigTemplateData.siteDescription();
                if (description != null) {
                    return description;
                }
            }
        } catch (RuntimeException ignored) {
            // Fall back to empty description when CDI container is not active
        }
        return "";
    }

    private String resolveThemeColor() {
        try {
            if (io.quarkus.arc.Arc.container() != null) {
                String color = ConfigTemplateData.themeColor();
                if (color != null && !color.isBlank()) {
                    return color;
                }
            }
        } catch (RuntimeException ignored) {
            // Fall back to default theme color when CDI container is not active
        }
        return "#6366f1";
    }

    private String buildManifestJson(
            String name,
            String description,
            String themeColor,
            String lang,
            String startUrl,
            List<Map<String, String>> icons) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n");
        sb.append("  \"name\": \"").append(escapeJson(name)).append("\",\n");
        sb.append("  \"short_name\": \"").append(escapeJson(name)).append("\",\n");
        sb.append("  \"description\": \"").append(escapeJson(description)).append("\",\n");
        sb.append("  \"lang\": \"").append(escapeJson(lang)).append("\",\n");
        sb.append("  \"dir\": \"ltr\",\n");
        sb.append("  \"start_url\": \"").append(escapeJson(startUrl)).append("\",\n");
        sb.append("  \"scope\": \"/\",\n");
        sb.append("  \"display\": \"standalone\",\n");
        sb.append("  \"background_color\": \"#0c0d10\",\n");
        sb.append("  \"theme_color\": \"").append(escapeJson(themeColor)).append("\",\n");
        sb.append("  \"icons\": [\n");
        for (int i = 0; i < icons.size(); i++) {
            Map<String, String> icon = icons.get(i);
            sb.append("    {");
            int fieldIdx = 0;
            for (Map.Entry<String, String> entry : icon.entrySet()) {
                if (fieldIdx++ > 0) {
                    sb.append(", ");
                }
                sb.append("\"").append(escapeJson(entry.getKey())).append("\": \"")
                        .append(escapeJson(entry.getValue())).append("\"");
            }
            sb.append("}");
            if (i < icons.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }
}
