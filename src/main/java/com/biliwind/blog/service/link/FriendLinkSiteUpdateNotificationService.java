package com.biliwind.blog.service.link;

import com.biliwind.blog.controller.api.admin.dto.AdminLinkDtos.LinkSiteUpdateNoticePreview;
import com.biliwind.blog.controller.api.admin.dto.AdminLinkDtos.LinkSiteUpdateNoticeResult;
import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.Link;
import com.biliwind.blog.model.LinkType;
import com.biliwind.blog.service.EmailDeliveryService;
import com.biliwind.blog.service.EmailTemplateRenderer;
import com.biliwind.blog.service.PublicUrlService;
import com.biliwind.blog.service.SiteLinkUrlService;
import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Composes and queues site-information update notifications to friendly-link webmasters.
 */
@ApplicationScoped
public class FriendLinkSiteUpdateNotificationService {

    public static final String SCENARIO = "FRIEND_LINK_SITE_UPDATE";

    @Inject
    EmailDeliveryService emailDeliveryService;

    @Inject
    EmailTemplateRenderer emailTemplateRenderer;

    @Inject
    SiteLinkUrlService siteLinkUrlService;

    @Inject
    PublicUrlService publicUrlService;

    @Inject
    SettingsReader settingsReader;

    public LinkSiteUpdateNoticePreview preview(Long linkId, String customSubject, String adminNote) {
        Link targetLink = null;
        if (linkId != null) {
            targetLink = Link.findById(linkId);
            if (targetLink == null) {
                throw new NotFoundException("友链不存在");
            }
        }
        BlogRegion region = targetLink == null ? BlogRegion.GLOBAL : LinkMonitorPolicy.displayRegion(targetLink);
        SiteSnapshot snapshot = resolveSiteSnapshot(region);
        String linkName = targetLink == null ? "示例友链" : targetLink.name;
        String subject = resolveSubject(customSubject, snapshot.siteName());
        String greeting = buildGreeting(linkName);
        String content = buildContent(snapshot, adminNote);
        String html = emailTemplateRenderer.render(
                snapshot.siteName() + " 站点信息变更通知",
                greeting,
                content,
                "访问本站",
                snapshot.siteUrl());
        long eligibleCount = targetLink != null
                ? (hasEmail(targetLink) ? 1L : 0L)
                : countEligibleLinks();
        return new LinkSiteUpdateNoticePreview(
                snapshot.siteName(),
                snapshot.siteSubtitle(),
                snapshot.siteUrl(),
                snapshot.siteDescription(),
                snapshot.siteIcon(),
                subject,
                greeting,
                content,
                html,
                eligibleCount);
    }

    public LinkSiteUpdateNoticeResult notifySingle(long linkId, String customSubject, String adminNote,
                                                   boolean allowEmailNotification) {
        Link link = Link.findById(linkId);
        if (link == null) {
            throw new NotFoundException("友链不存在");
        }
        return notifyLinks(List.of(link), customSubject, adminNote, allowEmailNotification);
    }

    public LinkSiteUpdateNoticeResult notifyBatch(List<Long> linkIds, String customSubject, String adminNote,
                                                  boolean allowEmailNotification) {
        List<Link> targets = resolveTargetLinks(linkIds);
        return notifyLinks(targets, customSubject, adminNote, allowEmailNotification);
    }

    LinkSiteUpdateNoticeResult notifyLinks(List<Link> targets, String customSubject, String adminNote,
                                           boolean allowEmailNotification) {
        if (targets.isEmpty()) {
            throw new BadRequestException("没有可通知的友链");
        }
        int queued = 0;
        int skipped = 0;
        List<String> skippedLinks = new ArrayList<>();

        for (Link link : targets) {
            if (!hasEmail(link)) {
                skipped++;
                skippedLinks.add(formatLinkLabel(link));
                continue;
            }
            if (!allowEmailNotification) {
                skipped++;
                continue;
            }
            BlogRegion region = LinkMonitorPolicy.displayRegion(link);
            SiteSnapshot snapshot = resolveSiteSnapshot(region);
            String subject = resolveSubject(customSubject, snapshot.siteName());
            String greeting = buildGreeting(link.name);
            String content = buildContent(snapshot, adminNote);
            String html = emailTemplateRenderer.render(
                    snapshot.siteName() + " 站点信息变更通知",
                    greeting,
                    content,
                    "访问本站",
                    snapshot.siteUrl());
            emailDeliveryService.queueRenderedHtml(SCENARIO, link.email.trim(), subject, html);
            queued++;
        }

        String message;
        if (!allowEmailNotification) {
            message = "已取消对外发送，本次未向友链站长发送邮件通知";
        } else if (queued == 0) {
            message = "所选友链均未配置联系邮箱，未发送任何邮件";
        } else if (skipped > 0) {
            message = "已加入发送队列 " + queued + " 封，跳过 " + skipped + " 个未填写邮箱的友链";
        } else {
            message = "已将 " + queued + " 封站点信息变更通知加入邮件发送队列";
        }

        return new LinkSiteUpdateNoticeResult(targets.size(), queued, skipped, skippedLinks, message);
    }

    List<Link> resolveTargetLinks(List<Long> linkIds) {
        if (linkIds == null || linkIds.isEmpty()) {
            return Link.list(
                    "type = ?1 and applicationStatus = ?2 and status = ?3 order by sortOrder asc, id asc",
                    LinkType.FRIENDLY_LINK, (short) 1, (short) 1);
        }
        Set<Long> uniqueIds = new LinkedHashSet<>();
        for (Long id : linkIds) {
            if (id != null && id > 0) {
                uniqueIds.add(id);
            }
        }
        if (uniqueIds.isEmpty()) {
            throw new BadRequestException("请选择有效的友链");
        }
        List<Link> resolved = new ArrayList<>();
        for (Long id : uniqueIds) {
            Link link = Link.findById(id);
            if (link != null) {
                resolved.add(link);
            }
        }
        return resolved;
    }

    long countEligibleLinks() {
        List<Link> links = Link.list(
                "type = ?1 and applicationStatus = ?2 and status = ?3",
                LinkType.FRIENDLY_LINK, (short) 1, (short) 1);
        long count = 0;
        for (Link link : links) {
            if (hasEmail(link)) {
                count++;
            }
        }
        return count;
    }

    SiteSnapshot resolveSiteSnapshot(BlogRegion region) {
        String siteName = resolveText(SettingKey.SITE_INFO, "title", "WindBlog");
        String siteSubtitle = resolveText(SettingKey.SITE_INFO, "subtitle", "");
        String siteDescription = resolveText(SettingKey.SITE_INFO, "description", "");
        String siteUrl = siteLinkUrlService != null
                ? siteLinkUrlService.urlFor(region == null ? BlogRegion.GLOBAL : region)
                : "https://example.com";
        String rawIcon = resolveText(SettingKey.APPEARANCE, "logo_url", "");
        if (rawIcon.isBlank()) {
            rawIcon = resolveText(SettingKey.APPEARANCE, "favicon_url", "");
        }
        String siteIcon = resolveAbsoluteIconUrl(rawIcon, siteUrl);
        return new SiteSnapshot(siteName, siteSubtitle, siteUrl, siteDescription, siteIcon);
    }

    static String resolveSubject(String customSubject, String siteName) {
        if (customSubject != null && !customSubject.isBlank()) {
            return customSubject.trim();
        }
        String resolvedSite = (siteName == null || siteName.isBlank()) ? "WindBlog" : siteName.trim();
        return "[" + resolvedSite + "] 友链信息变更通知";
    }

    static String buildGreeting(String linkName) {
        if (linkName != null && !linkName.isBlank()) {
            return "尊敬的「" + linkName.trim() + "」站长：";
        }
        return "尊敬的友链站长：";
    }

    static String buildContent(SiteSnapshot snapshot, String adminNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("自您添加我站友链以来，我站信息已经变更，这是最新信息：\n\n");
        sb.append("站点名称：").append(snapshot.siteName());
        if (!snapshot.siteSubtitle().isBlank()) {
            sb.append("\n站点副标题：").append(snapshot.siteSubtitle());
        }
        sb.append("\n站点链接：").append(snapshot.siteUrl());
        if (!snapshot.siteDescription().isBlank()) {
            sb.append("\n站点描述：").append(snapshot.siteDescription());
        }
        if (!snapshot.siteIcon().isBlank()) {
            sb.append("\n站点图标：").append(snapshot.siteIcon());
        }
        if (adminNote != null && !adminNote.isBlank()) {
            sb.append("\n\n以下是附加备注：\n").append(adminNote.trim());
        }
        sb.append("\n\n如您方便，欢迎在贵站友链页面更新我站信息，感谢您的支持！");
        return sb.toString();
    }

    private String resolveText(SettingKey key, String field, String fallback) {
        if (settingsReader == null) {
            return fallback;
        }
        String value = settingsReader.text(key, field);
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private String resolveAbsoluteIconUrl(String rawIcon, String siteUrl) {
        if (rawIcon == null || rawIcon.isBlank()) {
            return "";
        }
        String trimmed = rawIcon.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        if (trimmed.startsWith("/")) {
            String base = publicUrlService != null
                    ? PublicUrlService.normalize(siteUrl)
                    : siteUrl.replaceAll("/+$", "");
            return base + trimmed;
        }
        return trimmed;
    }

    private static boolean hasEmail(Link link) {
        return link != null && link.email != null && !link.email.isBlank();
    }

    private static String formatLinkLabel(Link link) {
        if (link == null) {
            return "未知友链";
        }
        if (link.name != null && !link.name.isBlank()) {
            return link.name.trim() + " (#" + link.id + ")";
        }
        return "#" + link.id;
    }

    record SiteSnapshot(
            String siteName,
            String siteSubtitle,
            String siteUrl,
            String siteDescription,
            String siteIcon) {
    }
}
