package com.biliwind.blog.service.og;

import com.biliwind.blog.common.CacheService;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.RegionVisibility;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.SeoPostPolicy;
import com.biliwind.blog.service.ConfigTemplateData;
import com.biliwind.blog.service.PublicCacheRefreshService;
import com.biliwind.blog.service.SeoPageService;
import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Generates and caches dynamic OpenGraph SVG and PNG cards for public posts.
 */
@ApplicationScoped
public class OgImageService {

    private static final Logger LOG = Logger.getLogger(OgImageService.class);
    private static final Duration OG_CACHE_TTL = Duration.ofHours(6);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Inject
    SettingsReader settings;

    @Inject
    PublicCacheRefreshService publicCacheRefreshService;

    @Inject
    SeoPageService seoPageService;

    @Inject
    LanguageContext languageContext;

    @Inject
    RegionContext regionContext;

    @Inject
    CacheService cacheService;

    public boolean isEnabled() {
        return settings.flag(SettingKey.SEO, "dynamic_og_enabled");
    }

    public String getPostOgSvg(String slug) {
        PostMeta meta = resolvePostMeta(slug);
        return OgImageRenderer.renderSvg(
                meta.title(),
                meta.summary(),
                meta.category(),
                meta.author(),
                meta.siteTitle(),
                meta.dateLabel(),
                meta.slug() + ":" + meta.category()
        );
    }

    public byte[] getPostOgPng(String slug) {
        PostMeta meta = resolvePostMeta(slug);
        String cacheKey = "og:png:v1:" + meta.slug() + ":" + meta.lang() + ":" + meta.revisionId();
        String cachedBase64 = cacheService.getOrLoad(cacheKey, String.class, OG_CACHE_TTL, () -> {
            byte[] png = rasterizeOrFallback(meta);
            return Base64.getEncoder().encodeToString(png);
        }).orElse(null);

        if (cachedBase64 != null && !cachedBase64.isEmpty()) {
            return Base64.getDecoder().decode(cachedBase64);
        }
        return rasterizeOrFallback(meta);
    }

    private byte[] rasterizeOrFallback(PostMeta meta) {
        String svg = OgImageRenderer.renderSvg(
                meta.title(),
                meta.summary(),
                meta.category(),
                meta.author(),
                meta.siteTitle(),
                meta.dateLabel(),
                meta.slug() + ":" + meta.category()
        );
        byte[] rsvgPng = tryRsvgConvert(svg);
        if (rsvgPng != null) {
            return rsvgPng;
        }
        return OgImageRenderer.renderFallbackPng(
                meta.siteTitle(),
                meta.slug(),
                meta.dateLabel(),
                meta.slug() + ":" + meta.category()
        );
    }

    private byte[] tryRsvgConvert(String svg) {
        try {
            Process process = new ProcessBuilder("rsvg-convert", "-w", "1200", "-h", "630", "-f", "png")
                    .redirectErrorStream(false)
                    .start();
            try (OutputStream os = process.getOutputStream()) {
                os.write(svg.getBytes(StandardCharsets.UTF_8));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream is = process.getInputStream()) {
                is.transferTo(out);
            }
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() == 0 && out.size() > 64) {
                return out.toByteArray();
            }
        } catch (Exception e) {
            LOG.debugf("rsvg-convert unavailable, using pure-Java PNG renderer: %s", e.getMessage());
        }
        return null;
    }

    private PostMeta resolvePostMeta(String slug) {
        if (!isEnabled() || slug == null || slug.isBlank()) {
            throw new NotFoundException();
        }
        PublicCacheRefreshService.PublicPostSnapshot snapshot =
                publicCacheRefreshService.findPublishedSnapshot(slug, currentRegionCode())
                        .orElseThrow(NotFoundException::new);
        if (snapshot.visibility() != 0) {
            throw new NotFoundException();
        }
        SeoPostPolicy policy = seoPageService.policy(snapshot.postId());
        if (policy != null && policy.noindex) {
            throw new NotFoundException();
        }

        String lang = currentLanguage();
        String title = LanguageHelper.resolveLocalizedValue(snapshot.title(), lang);
        if (title == null || title.isBlank()) {
            title = snapshot.slug();
        }
        String summary = LanguageHelper.resolveLocalizedValue(snapshot.aiSummary(), lang);
        if (summary == null || summary.isBlank()) {
            summary = LanguageHelper.resolveLocalizedValue(snapshot.summary(), lang);
        }
        String category = LanguageHelper.resolveLocalizedValue(snapshot.categoryName(), lang);
        String dateLabel = snapshot.publishedAt() != null
                ? snapshot.publishedAt().format(DATE_FORMATTER)
                : "";
        return new PostMeta(
                snapshot.slug(),
                lang,
                snapshot.publishedRevisionId() != null ? snapshot.publishedRevisionId() : 0L,
                title,
                summary,
                category,
                snapshot.authorName(),
                ConfigTemplateData.siteTitle(),
                dateLabel
        );
    }

    private String currentLanguage() {
        try {
            String lang = languageContext != null ? languageContext.getLang() : null;
            return (lang == null || lang.isBlank()) ? "zh-CN" : lang;
        } catch (ContextNotActiveException ignored) {
            return "zh-CN";
        }
    }

    private String currentRegionCode() {
        if (regionContext == null) {
            return BlogRegion.GLOBAL.getCode();
        }
        try {
            BlogRegion region = regionContext.getCurrentRegion();
            return region == null ? BlogRegion.GLOBAL.getCode() : region.getCode();
        } catch (ContextNotActiveException ignored) {
            return BlogRegion.GLOBAL.getCode();
        }
    }

    private record PostMeta(
            String slug,
            String lang,
            long revisionId,
            String title,
            String summary,
            String category,
            String author,
            String siteTitle,
            String dateLabel
    ) {}
}
