package com.biliwind.blog.service.link;

import com.biliwind.blog.controller.api.admin.dto.AdminLinkDtos.LinkSiteUpdateNoticeResult;
import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.Link;
import com.biliwind.blog.service.EmailDeliveryService;
import com.biliwind.blog.service.EmailTemplateRenderer;
import com.biliwind.blog.service.SiteLinkUrlService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendLinkSiteUpdateNotificationServiceTest {

    record QueuedEmail(String scenario, String recipient, String subject, String html) {
    }

    @Test
    void notifyLinksComposesGreetingLatestInfoAndAdminNote() {
        List<QueuedEmail> queued = new ArrayList<>();
        FriendLinkSiteUpdateNotificationService service = new FriendLinkSiteUpdateNotificationService() {
            @Override
            SiteSnapshot resolveSiteSnapshot(BlogRegion region) {
                return new SiteSnapshot(
                        "WindBlog 新站",
                        "技术与思考",
                        "https://new.windblog.example.com",
                        "全新迁移的博客站点",
                        "https://new.windblog.example.com/logo.png");
            }
        };
        service.emailDeliveryService = new EmailDeliveryService() {
            @Override
            public void queueRenderedHtml(String scenario, String recipientAddress, String subject, String htmlContent) {
                queued.add(new QueuedEmail(scenario, recipientAddress, subject, htmlContent));
            }
        };
        service.emailTemplateRenderer = new EmailTemplateRenderer();
        service.siteLinkUrlService = new SiteLinkUrlService(null);

        Link linkWithEmail = new Link();
        linkWithEmail.id = 1L;
        linkWithEmail.name = "好友站点";
        linkWithEmail.email = "friend@example.com";

        Link linkWithoutEmail = new Link();
        linkWithoutEmail.id = 2L;
        linkWithoutEmail.name = "无邮箱友链";
        linkWithoutEmail.email = "";

        LinkSiteUpdateNoticeResult result = service.notifyLinks(
                List.of(linkWithEmail, linkWithoutEmail),
                null,
                "本站已从原域名 old.windblog.example.com 迁移到 new.windblog.example.com",
                true);

        assertEquals(2, result.requestedCount());
        assertEquals(1, result.queuedCount());
        assertEquals(1, result.skippedCount());
        assertEquals(1, queued.size());

        QueuedEmail sent = queued.getFirst();
        assertEquals(FriendLinkSiteUpdateNotificationService.SCENARIO, sent.scenario());
        assertEquals("friend@example.com", sent.recipient());
        assertEquals("[WindBlog 新站] 友链信息变更通知", sent.subject());
        assertTrue(sent.html().contains("尊敬的「好友站点」站长："));
        assertTrue(sent.html().contains("自您添加我站友链以来，我站信息已经变更，这是最新信息："));
        assertTrue(sent.html().contains("https://new.windblog.example.com"));
        assertTrue(sent.html().contains("以下是附加备注："));
        assertTrue(sent.html().contains("本站已从原域名 old.windblog.example.com 迁移到 new.windblog.example.com"));
    }

    @Test
    void notifyLinksSuppressesEmailWhenOptedOut() {
        List<QueuedEmail> queued = new ArrayList<>();
        FriendLinkSiteUpdateNotificationService service = new FriendLinkSiteUpdateNotificationService();
        service.emailDeliveryService = new EmailDeliveryService() {
            @Override
            public void queueRenderedHtml(String scenario, String recipientAddress, String subject, String htmlContent) {
                queued.add(new QueuedEmail(scenario, recipientAddress, subject, htmlContent));
            }
        };
        service.emailTemplateRenderer = new EmailTemplateRenderer();

        Link link = new Link();
        link.id = 3L;
        link.name = "好友站点";
        link.email = "friend@example.com";

        LinkSiteUpdateNoticeResult result = service.notifyLinks(
                List.of(link), null, "域名迁移", false);

        assertEquals(0, result.queuedCount());
        assertEquals(1, result.skippedCount());
        assertTrue(queued.isEmpty());
    }

    @Test
    void buildContentOmitsAdminNoteSectionWhenBlank() {
        var snapshot = new FriendLinkSiteUpdateNotificationService.SiteSnapshot(
                "WindBlog", "", "https://windblog.example.com", "", "");
        String content = FriendLinkSiteUpdateNotificationService.buildContent(snapshot, "   ");
        assertTrue(content.contains("自您添加我站友链以来，我站信息已经变更，这是最新信息："));
        assertFalse(content.contains("以下是附加备注："));
    }
}
