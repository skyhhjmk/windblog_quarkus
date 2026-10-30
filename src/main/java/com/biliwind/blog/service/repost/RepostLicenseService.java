package com.biliwind.blog.service.repost;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.RepostLicense;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.PublicUrlService;
import com.biliwind.blog.service.edge.DataSyncEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * 创建转载授权和复制内容。
 */
@ApplicationScoped
public class RepostLicenseService {

    private volatile SecureRandom secureRandom;

    @ConfigProperty(name = "windblog.site.public-url", defaultValue = "http://localhost:8080")
    String publicSiteUrl;

    @ConfigProperty(name = "windblog.go.public-url")
    Optional<String> goPublicUrl;

    @Inject
    PublicUrlService publicUrlService;

    @Inject
    RepostDomainService repostDomainService;

    @Inject
    SemanticWatermarkService semanticWatermarkService;

    @Inject
    AffiliateTokenService affiliateTokenService;

    @Inject
    Event<DataSyncEvent> dataSyncEvent;

    @Inject
    RepostPolicyCatalog repostPolicyCatalog;

    @Transactional
    public LicenseCreationResult createLicense(Long postId, Long userId, String targetUrl) {
        return createLicense(postId, userId, targetUrl, LanguageConstant.DEFAULT_LANG);
    }

    @Transactional
    public LicenseCreationResult createLicense(Long postId, Long userId, String targetUrl, String language) {
        if (postId == null) {
            throw new BadRequestException("postId 不能为空");
        }
        if (targetUrl == null || targetUrl.isBlank()) {
            throw new BadRequestException("目标站点 URL 不能为空");
        }

        String allowedDomain = repostDomainService.normalizeDomainFromUrl(targetUrl);
        if (allowedDomain == null || allowedDomain.isBlank()) {
            throw new BadRequestException("目标站点 URL 无法识别域名");
        }

        Post post = Post.findById(postId);
        if (post == null) {
            throw new NotFoundException("文章不存在");
        }
        if (post.status != PostStatus.PUBLISHED) {
            throw new BadRequestException("只能转载已发布文章");
        }

        User user = User.findById(userId);
        if (user == null) {
            throw new BadRequestException("用户不存在");
        }

        RepostLicense license = new RepostLicense();
        license.code = generateUniqueLicenseCode();
        license.article = post;
        license.viewerUser = user;
        license.allowedDomain = allowedDomain;
        license.targetUrl = targetUrl.trim();
        license.status = 1;
        license.semanticWatermarkConfig = semanticWatermarkService.buildWatermarkConfig(userId, postId, allowedDomain);
        license.persist();

        AffiliateTokenService.TokenCreationResult tokenCreationResult =
                affiliateTokenService.createRepostToken(post, user, license, allowedDomain);

        dataSyncEvent.fire(new DataSyncEvent("REPOST_LICENSE", license.id, "UPSERT"));

        CopyContent copyContent = buildCopyContent(license, tokenCreationResult.rawToken, language);
        return new LicenseCreationResult(license, tokenCreationResult.rawToken, copyContent);
    }

    @Transactional
    public CopyContent createFreshCopyContent(Long licenseId, Long userId) {
        return createFreshCopyContent(licenseId, userId, LanguageConstant.DEFAULT_LANG);
    }

    @Transactional
    public CopyContent createFreshCopyContent(Long licenseId, Long userId, String language) {
        RepostLicense license = RepostLicense.findById(licenseId);
        if (license == null) {
            throw new NotFoundException("转载授权不存在");
        }
        if (license.viewerUser == null || license.viewerUser.id == null) {
            throw new BadRequestException("转载授权缺少用户信息");
        }
        if (license.viewerUser.id.equals(userId) == false) {
            throw new NotFoundException("转载授权不存在");
        }
        if (license.status != 1) {
            throw new BadRequestException("转载授权已失效");
        }

        AffiliateTokenService.TokenCreationResult tokenCreationResult =
                affiliateTokenService.createRepostToken(
                        license.article,
                        license.viewerUser,
                        license,
                        license.allowedDomain
                );

        return buildCopyContent(license, tokenCreationResult.rawToken, language);
    }

    @Transactional
    public void revokeLicense(Long licenseId) {
        RepostLicense license = RepostLicense.findById(licenseId);
        if (license == null) {
            return;
        }
        license.status = 2;
        license.revokedAt = OffsetDateTime.now();
        dataSyncEvent.fire(new DataSyncEvent("REPOST_LICENSE", license.id, "UPSERT"));
    }

    public CopyContent buildCopyContent(RepostLicense license, String rawToken) {
        return buildCopyContent(license, rawToken, LanguageConstant.DEFAULT_LANG);
    }

    public CopyContent buildCopyContent(RepostLicense license, String rawToken, String language) {
        boolean english = RepostPolicyCatalog.isEnglish(language);
        String title = resolvePostTitle(license.article, language);
        String originalUrl = buildPostUrl(license.article);
        String goUrl = buildGoUrl(rawToken);
        RepostPolicyCatalog.Policy policy = repostPolicyCatalog.resolve(license.article);

        if (!policy.requiresApplication()) {
            return buildOptionalRegistrationCopyContent(license, policy, title, originalUrl, goUrl, language, english);
        }

        String commercialNote = english
                ? "The commercial link is an authorized go short link. It records the source and license, then sends readers to the original article; always keep it on the reposted page."
                : "商业链接是带授权追踪的 go 短链：读者点击后会先记录来源和授权信息，再进入原文；转载页必须保留该链接。";

        StringBuilder markdownBuilder = new StringBuilder();
        markdownBuilder.append(english
                ? "> This article is reposted with authorization from WindBlog. License code: "
                : "> 本文已获得 WindBlog 转载授权。授权码：");
        markdownBuilder.append(license.code);
        markdownBuilder.append("\n\n");
        markdownBuilder.append("# ");
        markdownBuilder.append(title);
        markdownBuilder.append("\n\n");
        markdownBuilder.append(english ? "Original URL: " : "原文链接：");
        markdownBuilder.append(originalUrl);
        markdownBuilder.append("\n\n");
        markdownBuilder.append(english ? "Commercial link: " : "商业链接：");
        markdownBuilder.append(goUrl);
        markdownBuilder.append("\n\n");
        markdownBuilder.append(english ? "About the commercial link: " : "商业链接说明：");
        markdownBuilder.append(commercialNote);
        markdownBuilder.append("\n");

        StringBuilder htmlBuilder = new StringBuilder();
        htmlBuilder.append("<section class=\"repost-license\">");
        htmlBuilder.append("<p>");
        htmlBuilder.append(english
                ? "This article is reposted with authorization from WindBlog. License code: "
                : "本文已获得 WindBlog 转载授权。授权码：");
        htmlBuilder.append(escapeHtml(license.code));
        htmlBuilder.append("</p>");
        htmlBuilder.append("<h1>");
        htmlBuilder.append(escapeHtml(title));
        htmlBuilder.append("</h1>");
        htmlBuilder.append("<p>").append(english ? "Original URL: " : "原文链接：");
        htmlBuilder.append("<a href=\"");
        htmlBuilder.append(escapeHtml(originalUrl));
        htmlBuilder.append("\">");
        htmlBuilder.append(escapeHtml(originalUrl));
        htmlBuilder.append("</a></p>");
        htmlBuilder.append("<p>").append(english ? "Commercial link: " : "商业链接：");
        htmlBuilder.append("<a rel=\"sponsored nofollow\" href=\"");
        htmlBuilder.append(escapeHtml(goUrl));
        htmlBuilder.append("\">");
        htmlBuilder.append(escapeHtml(goUrl));
        htmlBuilder.append("</a></p>");
        htmlBuilder.append("<p class=\"repost-commercial-note\">");
        htmlBuilder.append(english ? "About the commercial link: " : "商业链接说明：");
        htmlBuilder.append(escapeHtml(commercialNote));
        htmlBuilder.append("</p>");
        htmlBuilder.append("</section>");

        StringBuilder plainTextBuilder = new StringBuilder();
        plainTextBuilder.append(english
                ? "This article is reposted with authorization from WindBlog. License code: "
                : "本文已获得 WindBlog 转载授权。授权码：");
        plainTextBuilder.append(license.code);
        plainTextBuilder.append("\n");
        plainTextBuilder.append(title);
        plainTextBuilder.append("\n");
        plainTextBuilder.append(english ? "Original URL: " : "原文链接：");
        plainTextBuilder.append(originalUrl);
        plainTextBuilder.append("\n");
        plainTextBuilder.append(english ? "Commercial link: " : "商业链接：");
        plainTextBuilder.append(goUrl);
        plainTextBuilder.append("\n");
        plainTextBuilder.append(english ? "About the commercial link: " : "商业链接说明：");
        plainTextBuilder.append(commercialNote);

        return new CopyContent(markdownBuilder.toString(), htmlBuilder.toString(), plainTextBuilder.toString(), originalUrl, goUrl);
    }

    private CopyContent buildOptionalRegistrationCopyContent(
            RepostLicense license,
            RepostPolicyCatalog.Policy policy,
            String title,
            String originalUrl,
            String goUrl,
            String language,
            boolean english) {
        String policyNotice = english
                ? "This article is licensed under " + policy.nameFor(language) + "; no application is required."
                : "本文使用 " + policy.nameFor(language) + " 协议，无须申请转载。";
        String conditionText = String.join(english ? "; " : "；", policy.conditionsFor(language));
        String registrationText = english
                ? "This registration only generates an optional license code and tracking short link; it is not a prerequisite for reposting."
                : "此次登记仅用于生成可选的授权码和追踪短链，不是转载前置条件。";
        String trackingNote = english
                ? "The tracking link is an optional go short link used to count repost traffic; keeping it is recommended but not required."
                : "追踪链接是用于统计转载来源的可选 go 短链；建议保留，但不是转载前置条件。";

        StringBuilder markdownBuilder = new StringBuilder();
        markdownBuilder.append("> ").append(policyNotice).append("\n");
        markdownBuilder.append(english ? "> Reposting conditions: " : "> 转载条件：").append(conditionText).append("\n");
        markdownBuilder.append("> ").append(registrationText).append("\n\n");
        markdownBuilder.append("# ").append(title).append("\n\n");
        markdownBuilder.append(english ? "Original URL: " : "原文地址：").append(originalUrl).append("\n\n");
        markdownBuilder.append(english ? "License URL: " : "协议链接：").append(policy.licenseUrl()).append("\n\n");
        markdownBuilder.append(english ? "Optional registration code: " : "可选登记授权码：").append(license.code).append("\n\n");
        markdownBuilder.append(english ? "Tracking link (optional): " : "追踪链接（可选）：").append(goUrl).append("\n");
        markdownBuilder.append(english ? "About the tracking link: " : "追踪链接说明：").append(trackingNote).append("\n");

        StringBuilder htmlBuilder = new StringBuilder();
        htmlBuilder.append("<section class=\"repost-license\">");
        htmlBuilder.append("<p>").append(escapeHtml(policyNotice)).append("</p>");
        htmlBuilder.append("<p>").append(english ? "Reposting conditions: " : "转载条件：")
                .append(escapeHtml(conditionText)).append("</p>");
        htmlBuilder.append("<p>").append(escapeHtml(registrationText)).append("</p>");
        htmlBuilder.append("<h1>").append(escapeHtml(title)).append("</h1>");
        htmlBuilder.append("<p>").append(english ? "Original URL: " : "原文地址：")
                .append("<a href=\"").append(escapeHtml(originalUrl)).append("\">");
        htmlBuilder.append(escapeHtml(originalUrl)).append("</a></p>");
        htmlBuilder.append("<p>").append(english ? "License URL: " : "协议链接：")
                .append("<a href=\"").append(escapeHtml(policy.licenseUrl())).append("\">");
        htmlBuilder.append(escapeHtml(policy.licenseUrl())).append("</a></p>");
        htmlBuilder.append("<p>").append(english ? "Optional registration code: " : "可选登记授权码：")
                .append(escapeHtml(license.code)).append("</p>");
        htmlBuilder.append("<p>").append(english ? "Tracking link (optional): " : "追踪链接（可选）：")
                .append("<a rel=\"nofollow\" href=\"");
        htmlBuilder.append(escapeHtml(goUrl)).append("\">").append(escapeHtml(goUrl)).append("</a></p>");
        htmlBuilder.append("<p class=\"repost-tracking-note\">")
                .append(english ? "About the tracking link: " : "追踪链接说明：")
                .append(escapeHtml(trackingNote)).append("</p>");
        htmlBuilder.append("</section>");

        StringBuilder plainTextBuilder = new StringBuilder();
        plainTextBuilder.append(policyNotice).append("\n");
        plainTextBuilder.append(english ? "Reposting conditions: " : "转载条件：").append(conditionText).append("\n");
        plainTextBuilder.append(registrationText).append("\n");
        plainTextBuilder.append(title).append("\n");
        plainTextBuilder.append(english ? "Original URL: " : "原文地址：").append(originalUrl).append("\n");
        plainTextBuilder.append(english ? "License URL: " : "协议链接：").append(policy.licenseUrl()).append("\n");
        plainTextBuilder.append(english ? "Optional registration code: " : "可选登记授权码：").append(license.code).append("\n");
        plainTextBuilder.append(english ? "Tracking link (optional): " : "追踪链接（可选）：").append(goUrl).append("\n");
        plainTextBuilder.append(english ? "About the tracking link: " : "追踪链接说明：").append(trackingNote);

        return new CopyContent(markdownBuilder.toString(), htmlBuilder.toString(), plainTextBuilder.toString(), originalUrl, goUrl);
    }

    public String buildPostUrl(Post post) {
        if (post == null) {
            return buildPostUrl("");
        }
        return buildPostUrl(post.slug);
    }

    public String buildPostUrl(String slug) {
        String baseUrl = normalizeBaseUrl(publicSiteUrl);
        if (baseUrl == null) {
            baseUrl = publicUrlService == null ? "http://localhost:8080" : publicUrlService.getBaseUrl();
        }
        return baseUrl + "/post/" + slug;
    }

    public String buildGoUrl(String rawToken) {
        return resolveGoBaseUrl() + "/r/" + rawToken;
    }

    /**
     * The go link must be absolute and must never fall back to localhost in a
     * deployed site. When no dedicated go origin is configured, the canonical
     * public site origin serves the short link.
     */
    String resolveGoBaseUrl() {
        String configured = normalizeBaseUrl(goPublicUrl.orElse(null));
        if (configured != null) {
            return configured;
        }
        if (publicUrlService != null) {
            return publicUrlService.getBaseUrl();
        }
        String fallback = normalizeBaseUrl(publicSiteUrl);
        return fallback == null ? "http://localhost:8080" : fallback;
    }

    private String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String resolvePostTitle(Post post) {
        return resolvePostTitle(post, LanguageConstant.DEFAULT_LANG);
    }

    private String resolvePostTitle(Post post, String language) {
        String title = LanguageHelper.resolveLocalizedValue(post.title, language);
        if (title == null || title.isBlank()) {
            title = LanguageHelper.resolveLocalizedValue(post.title, LanguageConstant.DEFAULT_LANG);
        }
        if (title == null || title.isBlank()) {
            return post.slug;
        }
        return title;
    }

    private String generateUniqueLicenseCode() {
        String code = generateLicenseCode();
        while (RepostLicense.count("code = ?1", code) > 0) {
            code = generateLicenseCode();
        }
        return code;
    }

    private String generateLicenseCode() {
        byte[] randomBytes = new byte[12];
        getSecureRandom().nextBytes(randomBytes);
        return "RPL-" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private SecureRandom getSecureRandom() {
        SecureRandom cached = secureRandom;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = secureRandom;
            if (cached == null) {
                cached = new SecureRandom();
                secureRandom = cached;
            }
            return cached;
        }
    }

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public static class LicenseCreationResult {
        public final RepostLicense license;
        public final String rawToken;
        public final CopyContent copyContent;

        public LicenseCreationResult(RepostLicense license, String rawToken, CopyContent copyContent) {
            this.license = license;
            this.rawToken = rawToken;
            this.copyContent = copyContent;
        }
    }

    public static class CopyContent {
        public final String markdown;
        public final String html;
        public final String plainText;
        public final String originalUrl;
        public final String goUrl;

        public CopyContent(String markdown, String html, String plainText, String originalUrl, String goUrl) {
            this.markdown = markdown;
            this.html = html;
            this.plainText = plainText;
            this.originalUrl = originalUrl;
            this.goUrl = goUrl;
        }
    }
}
