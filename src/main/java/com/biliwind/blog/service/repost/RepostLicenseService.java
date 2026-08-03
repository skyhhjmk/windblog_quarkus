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

    private final SecureRandom secureRandom = new SecureRandom();

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
        secureRandom.nextBytes(randomBytes);
        return "RPL-" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
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
