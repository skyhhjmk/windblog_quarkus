package com.biliwind.blog.service;

import com.biliwind.blog.common.CacheService;
import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PostAuthorHelper;
import com.biliwind.blog.controller.api.admin.dto.AdminPostDtos.PostPreviewLinkRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminPostDtos.PostPreviewLinkResponse;
import com.biliwind.blog.model.ArticleMode;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.ResourceMeta;
import com.biliwind.blog.model.Tag;
import com.biliwind.blog.model.TutorialMeta;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.PublicCacheRefreshService.PublicCategorySnapshot;
import com.biliwind.blog.service.PublicCacheRefreshService.PublicTagSnapshot;
import com.biliwind.blog.service.repost.RepostPolicyCatalog;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Stores ephemeral, unguessable preview snapshots generated from the admin
 * editor so authors can open drafts or unsaved edits in a real browser tab
 * without publishing them.
 */
@ApplicationScoped
public class PostPreviewService {

    static final int DEFAULT_TTL_MINUTES = 60;
    static final int MIN_TTL_MINUTES = 5;
    static final int MAX_TTL_MINUTES = 1440;
    private static final int MAX_LOCAL_ENTRIES = 256;
    private static final String CACHE_KEY_PREFIX = "post:preview:link:";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{16,128}$");

    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, PostPreviewSnapshot> localFallback = new ConcurrentHashMap<>();

    @Inject
    CacheService cacheService;

    @Inject
    CanonicalUrlService canonicalUrlService;

    @Inject
    RepostPolicyCatalog repostPolicyCatalog;

    public PostPreviewLinkResponse createPreview(PostPreviewLinkRequest request, User operator) {
        OffsetDateTime now = OffsetDateTime.now();
        int ttlMinutes = normalizeTtlMinutes(request == null ? null : request.ttlMinutes());
        OffsetDateTime expiresAt = now.plusMinutes(ttlMinutes);

        Post existingPost = null;
        if (request != null && request.postId() != null) {
            existingPost = Post.findById(request.postId());
        }

        Map<String, String> title = copyNonEmptyMap(request == null ? null : request.title());
        if (title.isEmpty() && existingPost != null && existingPost.title != null) {
            title = copyNonEmptyMap(existingPost.title);
        }
        if (title.isEmpty()) {
            title = Map.of(LanguageConstant.DEFAULT_LANG, "未命名预览文章");
        }

        Map<String, String> summary = copyNonEmptyMap(request == null ? null : request.summary());
        if (summary.isEmpty() && existingPost != null && existingPost.summary != null) {
            summary = copyNonEmptyMap(existingPost.summary);
        }

        Map<String, String> aiSummary = copyNonEmptyMap(request == null ? null : request.aiSummary());
        if (aiSummary.isEmpty() && existingPost != null && existingPost.aiSummary != null) {
            aiSummary = copyNonEmptyMap(existingPost.aiSummary);
        }

        Map<String, String> contentMarkdown = copyNonEmptyMap(request == null ? null : request.contentMarkdown());
        if (contentMarkdown.isEmpty() && existingPost != null && existingPost.currentRevision != null
                && existingPost.currentRevision.contentMarkdown != null) {
            contentMarkdown = copyNonEmptyMap(existingPost.currentRevision.contentMarkdown);
        }

        String slug = request != null && request.slug() != null && !request.slug().isBlank()
                ? request.slug().trim()
                : (existingPost != null && existingPost.slug != null && !existingPost.slug.isBlank()
                ? existingPost.slug
                : "preview-draft");

        PostRenderType renderType = resolveRenderType(request == null ? null : request.renderType(),
                existingPost == null ? null : existingPost.renderType);

        String primaryLang = resolvePrimaryLanguage(title);
        Map<String, String> seoTitle = buildSeoMap(
                request == null ? null : request.seoTitleTranslations(),
                primaryLang,
                request == null ? null : request.seoTitle());
        Map<String, String> seoKeywords = buildSeoMap(
                request == null ? null : request.seoKeywordsTranslations(),
                primaryLang,
                request == null ? null : request.seoKeywords());
        Map<String, String> seoDescription = buildSeoMap(
                request == null ? null : request.seoDescriptionTranslations(),
                primaryLang,
                request == null ? null : request.seoDescription());

        List<PublicCategorySnapshot> categories = resolveCategories(request, existingPost);
        Map<String, String> primaryCategoryName = categories.isEmpty() ? Map.of() : categories.getFirst().name();
        String primaryCategorySlug = categories.isEmpty() ? null : categories.getFirst().slug();

        List<PublicTagSnapshot> tags = resolveTags(request);
        String authorName = resolveAuthorName(request, existingPost, operator);

        short visibility = request != null && request.visibility() != null
                ? request.visibility()
                : (existingPost != null ? existingPost.visibility : (short) 0);
        Short aiSummaryStatus = request != null && request.aiSummaryStatus() != null
                ? request.aiSummaryStatus()
                : (existingPost != null ? existingPost.aiSummaryStatus : (short) 0);
        long postPrice = request != null && request.pointsPrice() != null
                ? Math.max(0L, request.pointsPrice())
                : 0L;

        List<String> contentDeclarations = request != null && request.contentDeclarations() != null
                ? List.copyOf(request.contentDeclarations())
                : (existingPost != null && existingPost.contentDeclarations != null
                ? List.copyOf(existingPost.contentDeclarations) : List.of());

        String repostPolicyCode = resolveRepostPolicyCode(
                request == null ? null : request.repostPolicyCode(),
                existingPost == null ? null : existingPost.repostPolicyCode);

        ArticleMode articleMode = request != null && request.articleMode() != null
                ? ArticleMode.fromCode(request.articleMode())
                : (existingPost != null && existingPost.articleMode != null
                ? existingPost.articleMode : ArticleMode.STANDARD);

        TutorialMeta tutorialMeta = request != null && request.tutorialMeta() != null
                ? request.tutorialMeta()
                : (existingPost != null ? existingPost.tutorialMeta : null);
        ResourceMeta resourceMeta = request != null && request.resourceMeta() != null
                ? request.resourceMeta()
                : (existingPost != null ? existingPost.resourceMeta : null);

        String preferredLanguage = normalizePreferredLanguage(request == null ? null : request.language());
        OffsetDateTime publishedAt = existingPost != null && existingPost.publishedAt != null
                ? existingPost.publishedAt
                : now;

        PostPreviewSnapshot snapshot = new PostPreviewSnapshot(
                existingPost == null ? (request == null ? null : request.postId()) : existingPost.id,
                slug,
                title,
                summary,
                aiSummary,
                aiSummaryStatus,
                contentMarkdown,
                renderType,
                seoTitle,
                seoKeywords,
                seoDescription,
                primaryCategoryName,
                primaryCategorySlug,
                categories,
                authorName,
                publishedAt,
                now,
                visibility,
                postPrice,
                tags,
                contentDeclarations,
                repostPolicyCode,
                articleMode,
                tutorialMeta,
                resourceMeta,
                preferredLanguage,
                now,
                expiresAt);

        String token = generateToken();
        storeSnapshot(token, snapshot, Duration.ofMinutes(ttlMinutes));

        String rawPath = "/preview/post/" + token;
        String previewPath = preferredLanguage == null
                ? rawPath
                : LanguageHelper.localizedPath(rawPath, preferredLanguage, canonicalUrlService.getDefaultLanguage());
        String previewUrl = canonicalUrlService.buildPath(previewPath);
        return new PostPreviewLinkResponse(token, previewPath, previewUrl, expiresAt);
    }

    public Optional<PostPreviewSnapshot> find(String token) {
        if (token == null || !TOKEN_PATTERN.matcher(token).matches()) {
            return Optional.empty();
        }
        PostPreviewSnapshot snapshot = null;
        if (cacheService != null) {
            snapshot = cacheService.get(CACHE_KEY_PREFIX + token, PostPreviewSnapshot.class).orElse(null);
        }
        if (snapshot == null) {
            snapshot = localFallback.get(token);
        }
        if (snapshot == null) {
            return Optional.empty();
        }
        if (snapshot.expiresAt() != null && snapshot.expiresAt().isBefore(OffsetDateTime.now())) {
            localFallback.remove(token);
            if (cacheService != null) {
                cacheService.delete(CACHE_KEY_PREFIX + token);
            }
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    void storeSnapshot(String token, PostPreviewSnapshot snapshot, Duration ttl) {
        if (cacheService != null) {
            cacheService.set(CACHE_KEY_PREFIX + token, snapshot, ttl);
        }
        pruneLocalFallback();
        localFallback.put(token, snapshot);
    }

    private void pruneLocalFallback() {
        OffsetDateTime now = OffsetDateTime.now();
        localFallback.entrySet().removeIf(entry ->
                entry.getValue() == null
                        || (entry.getValue().expiresAt() != null && entry.getValue().expiresAt().isBefore(now)));
        if (localFallback.size() < MAX_LOCAL_ENTRIES) {
            return;
        }
        localFallback.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().createdAt() == null
                        ? OffsetDateTime.MIN : e.getValue().createdAt()))
                .limit(localFallback.size() - MAX_LOCAL_ENTRIES + 1L)
                .map(Map.Entry::getKey)
                .forEach(localFallback::remove);
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static int normalizeTtlMinutes(Integer requested) {
        if (requested == null) {
            return DEFAULT_TTL_MINUTES;
        }
        return Math.max(MIN_TTL_MINUTES, Math.min(MAX_TTL_MINUTES, requested));
    }

    private static Map<String, String> copyNonEmptyMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }

    private static PostRenderType resolveRenderType(Short requestedCode, PostRenderType fallback) {
        if (requestedCode != null && PostRenderType.isSupportedCode(requestedCode)) {
            return PostRenderType.fromCode(requestedCode);
        }
        return fallback == null ? PostRenderType.MARKDOWN : fallback;
    }

    private static String resolvePrimaryLanguage(Map<String, String> title) {
        if (title != null) {
            for (String lang : LanguageConstant.SUPPORTED_LANG_ORDER) {
                if (LanguageHelper.hasLocalizedValue(title, lang)) {
                    return lang;
                }
            }
        }
        return LanguageConstant.DEFAULT_LANG;
    }

    private static Map<String, String> buildSeoMap(Map<String, String> translations,
                                                   String primaryLanguage,
                                                   String singleValue) {
        Map<String, String> result = new LinkedHashMap<>();
        if (translations != null) {
            for (Map.Entry<String, String> entry : translations.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isBlank()
                        && entry.getValue() != null && !entry.getValue().isBlank()) {
                    result.put(entry.getKey(), entry.getValue().trim());
                }
            }
        }
        if (singleValue != null && !singleValue.isBlank()) {
            result.put(primaryLanguage, singleValue.trim());
        }
        return result;
    }

    private static String normalizePreferredLanguage(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        return LanguageHelper.normalizeToSupportedLang(language);
    }

    private List<PublicCategorySnapshot> resolveCategories(PostPreviewLinkRequest request, Post existingPost) {
        Set<Long> ids = new LinkedHashSet<>();
        if (request != null && request.categoryIds() != null) {
            for (Long id : request.categoryIds()) {
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        if (ids.isEmpty() && request != null && request.categoryId() != null) {
            ids.add(request.categoryId());
        }
        List<PublicCategorySnapshot> result = new ArrayList<>();
        for (Long id : ids) {
            Category category = Category.findById(id);
            if (category != null) {
                result.add(new PublicCategorySnapshot(
                        category.name == null ? Map.of() : Map.copyOf(category.name),
                        category.slug));
            }
        }
        if (result.isEmpty() && existingPost != null && existingPost.category != null) {
            result.add(new PublicCategorySnapshot(
                    existingPost.category.name == null ? Map.of() : Map.copyOf(existingPost.category.name),
                    existingPost.category.slug));
        }
        return result;
    }

    private List<PublicTagSnapshot> resolveTags(PostPreviewLinkRequest request) {
        if (request == null || request.tagIds() == null || request.tagIds().isEmpty()) {
            return List.of();
        }
        List<PublicTagSnapshot> result = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (Long id : request.tagIds()) {
            if (id == null || !seen.add(id)) {
                continue;
            }
            Tag tag = Tag.findById(id);
            if (tag != null) {
                result.add(new PublicTagSnapshot(
                        tag.name == null ? Map.of() : Map.copyOf(tag.name),
                        tag.slug));
            }
        }
        return result;
    }

    private String resolveAuthorName(PostPreviewLinkRequest request, Post existingPost, User operator) {
        if (request != null && request.authorName() != null && !request.authorName().isBlank()) {
            return request.authorName().trim();
        }
        if (request != null && request.userId() != null) {
            User target = User.findById(request.userId());
            if (target != null && target.username != null && !target.username.isBlank()) {
                return target.username;
            }
        }
        if (existingPost != null) {
            return PostAuthorHelper.displayName(existingPost);
        }
        if (operator != null && operator.username != null && !operator.username.isBlank()) {
            return operator.username;
        }
        return "Admin";
    }

    private String resolveRepostPolicyCode(String requested, String fallback) {
        if (repostPolicyCatalog == null) {
            return requested != null && !requested.isBlank() ? requested : fallback;
        }
        if (requested != null && !requested.isBlank()) {
            try {
                return repostPolicyCatalog.require(requested).code();
            } catch (RuntimeException ignored) {
                // Fall back to default policy for preview rendering.
            }
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return repostPolicyCatalog.resolve(null, List.of()).code();
    }

    @RegisterForReflection
    public record PostPreviewSnapshot(
            Long postId,
            String slug,
            Map<String, String> title,
            Map<String, String> summary,
            Map<String, String> aiSummary,
            Short aiSummaryStatus,
            Map<String, String> contentMarkdown,
            PostRenderType renderType,
            Map<String, String> seoTitle,
            Map<String, String> seoKeywords,
            Map<String, String> seoDescription,
            Map<String, String> categoryName,
            String categorySlug,
            List<PublicCategorySnapshot> categories,
            String authorName,
            OffsetDateTime publishedAt,
            OffsetDateTime updatedAt,
            short visibility,
            long postPrice,
            List<PublicTagSnapshot> tags,
            List<String> contentDeclarations,
            String repostPolicyCode,
            ArticleMode articleMode,
            TutorialMeta tutorialMeta,
            ResourceMeta resourceMeta,
            String preferredLanguage,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt) {
    }
}
