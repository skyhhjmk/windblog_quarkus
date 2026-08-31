package com.biliwind.blog.service.repost;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.RepostLicense;
import com.biliwind.blog.model.User;
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

/**
 * 创建转载授权和复制内容。
 */
@ApplicationScoped
public class RepostLicenseService {

    private volatile SecureRandom secureRandom;

    @ConfigProperty(name = "windblog.site.public-url", defaultValue = "http://localhost:8080")
    String publicSiteUrl;

    @ConfigProperty(name = "windblog.go.public-url", defaultValue = "http://localhost:8080")
    String goPublicUrl;

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

        CopyContent copyContent = buildCopyContent(license, tokenCreationResult.rawToken);
        return new LicenseCreationResult(license, tokenCreationResult.rawToken, copyContent);
    }

    @Transactional
    public CopyContent createFreshCopyContent(Long licenseId, Long userId) {
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

        return buildCopyContent(license, tokenCreationResult.rawToken);
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
        String title = resolvePostTitle(license.article);
        String originalUrl = buildPostUrl(license.article);
        String goUrl = buildGoUrl(rawToken);
        RepostPolicyCatalog.Policy policy = repostPolicyCatalog.resolve(license.article);

        if (!policy.requiresApplication()) {
            return buildOptionalRegistrationCopyContent(license, policy, title, originalUrl, goUrl);
        }

        StringBuilder markdownBuilder = new StringBuilder();
        markdownBuilder.append("> 本文已获得 WindBlog 转载授权。授权码：");
        markdownBuilder.append(license.code);
        markdownBuilder.append("\n\n");
        markdownBuilder.append("# ");
        markdownBuilder.append(title);
        markdownBuilder.append("\n\n");
        markdownBuilder.append("原文链接：");
        markdownBuilder.append(originalUrl);
        markdownBuilder.append("\n\n");
        markdownBuilder.append("商业链接：");
        markdownBuilder.append(goUrl);
        markdownBuilder.append("\n");

        StringBuilder htmlBuilder = new StringBuilder();
        htmlBuilder.append("<section class=\"repost-license\">");
        htmlBuilder.append("<p>本文已获得 WindBlog 转载授权。授权码：");
        htmlBuilder.append(escapeHtml(license.code));
        htmlBuilder.append("</p>");
        htmlBuilder.append("<h1>");
        htmlBuilder.append(escapeHtml(title));
        htmlBuilder.append("</h1>");
        htmlBuilder.append("<p>原文链接：<a href=\"");
        htmlBuilder.append(escapeHtml(originalUrl));
        htmlBuilder.append("\">");
        htmlBuilder.append(escapeHtml(originalUrl));
        htmlBuilder.append("</a></p>");
        htmlBuilder.append("<p>商业链接：<a rel=\"sponsored nofollow\" href=\"");
        htmlBuilder.append(escapeHtml(goUrl));
        htmlBuilder.append("\">");
        htmlBuilder.append(escapeHtml(goUrl));
        htmlBuilder.append("</a></p>");
        htmlBuilder.append("</section>");

        StringBuilder plainTextBuilder = new StringBuilder();
        plainTextBuilder.append("本文已获得 WindBlog 转载授权。授权码：");
        plainTextBuilder.append(license.code);
        plainTextBuilder.append("\n");
        plainTextBuilder.append(title);
        plainTextBuilder.append("\n");
        plainTextBuilder.append("原文链接：");
        plainTextBuilder.append(originalUrl);
        plainTextBuilder.append("\n");
        plainTextBuilder.append("商业链接：");
        plainTextBuilder.append(goUrl);

        return new CopyContent(markdownBuilder.toString(), htmlBuilder.toString(), plainTextBuilder.toString(), originalUrl, goUrl);
    }

    private CopyContent buildOptionalRegistrationCopyContent(
            RepostLicense license,
            RepostPolicyCatalog.Policy policy,
            String title,
            String originalUrl,
            String goUrl) {
        String policyNotice = "本文使用 " + policy.name() + " 协议，无须申请转载。";
        String conditionText = String.join("；", policy.conditions());

        StringBuilder markdownBuilder = new StringBuilder();
        markdownBuilder.append("> ").append(policyNotice).append("\n");
        markdownBuilder.append("> 转载条件：").append(conditionText).append("\n");
        markdownBuilder.append("> 此次登记仅用于生成可选的授权码和追踪短链，不是转载前置条件。\n\n");
        markdownBuilder.append("# ").append(title).append("\n\n");
        markdownBuilder.append("原文地址：").append(originalUrl).append("\n\n");
        markdownBuilder.append("协议链接：").append(policy.licenseUrl()).append("\n\n");
        markdownBuilder.append("可选登记授权码：").append(license.code).append("\n\n");
        markdownBuilder.append("追踪链接（可选）：").append(goUrl).append("\n");

        StringBuilder htmlBuilder = new StringBuilder();
        htmlBuilder.append("<section class=\"repost-license\">");
        htmlBuilder.append("<p>").append(escapeHtml(policyNotice)).append("</p>");
        htmlBuilder.append("<p>转载条件：").append(escapeHtml(conditionText)).append("</p>");
        htmlBuilder.append("<p>此次登记仅用于生成可选的授权码和追踪短链，不是转载前置条件。</p>");
        htmlBuilder.append("<h1>").append(escapeHtml(title)).append("</h1>");
        htmlBuilder.append("<p>原文地址：<a href=\"").append(escapeHtml(originalUrl)).append("\">");
        htmlBuilder.append(escapeHtml(originalUrl)).append("</a></p>");
        htmlBuilder.append("<p>协议链接：<a href=\"").append(escapeHtml(policy.licenseUrl())).append("\">");
        htmlBuilder.append(escapeHtml(policy.licenseUrl())).append("</a></p>");
        htmlBuilder.append("<p>可选登记授权码：").append(escapeHtml(license.code)).append("</p>");
        htmlBuilder.append("<p>追踪链接（可选）：<a rel=\"nofollow\" href=\"");
        htmlBuilder.append(escapeHtml(goUrl)).append("\">").append(escapeHtml(goUrl)).append("</a></p>");
        htmlBuilder.append("</section>");

        StringBuilder plainTextBuilder = new StringBuilder();
        plainTextBuilder.append(policyNotice).append("\n");
        plainTextBuilder.append("转载条件：").append(conditionText).append("\n");
        plainTextBuilder.append("此次登记仅用于生成可选的授权码和追踪短链，不是转载前置条件。\n");
        plainTextBuilder.append(title).append("\n");
        plainTextBuilder.append("原文地址：").append(originalUrl).append("\n");
        plainTextBuilder.append("协议链接：").append(policy.licenseUrl()).append("\n");
        plainTextBuilder.append("可选登记授权码：").append(license.code).append("\n");
        plainTextBuilder.append("追踪链接（可选）：").append(goUrl);

        return new CopyContent(markdownBuilder.toString(), htmlBuilder.toString(), plainTextBuilder.toString(), originalUrl, goUrl);
    }

    public String buildPostUrl(Post post) {
        if (post == null) {
            return buildPostUrl("");
        }
        return buildPostUrl(post.slug);
    }

    public String buildPostUrl(String slug) {
        String baseUrl = publicSiteUrl;
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + "/post/" + slug;
    }

    public String buildGoUrl(String rawToken) {
        String baseUrl = goPublicUrl;
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + "/r/" + rawToken;
    }

    private String resolvePostTitle(Post post) {
        String title = LanguageHelper.resolveLocalizedValue(post.title, LanguageConstant.DEFAULT_LANG);
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
