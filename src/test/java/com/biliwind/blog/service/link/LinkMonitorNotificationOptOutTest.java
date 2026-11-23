package com.biliwind.blog.service.link;

import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.Link;
import com.biliwind.blog.service.EmailDeliveryService;
import com.biliwind.blog.service.EmailTemplateRenderer;
import com.biliwind.blog.service.SiteLinkUrlService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkMonitorNotificationOptOutTest {

    @Test
    void advanceConditionSuppressesExternalEmailWhenDisallowed() {
        List<String> queuedRecipients = new ArrayList<>();
        LinkMonitorService service = new LinkMonitorService() {
            @Override
            String notificationSiteUrl(BlogRegion region) {
                return "https://blog.example.com";
            }
        };
        service.emailDeliveryService = new EmailDeliveryService() {
            @Override
            public void queueRenderedHtml(String scenario, String recipientAddress, String subject, String htmlContent) {
                queuedRecipients.add(recipientAddress);
            }
        };
        service.emailTemplateRenderer = new EmailTemplateRenderer();
        service.siteLinkUrlService = new SiteLinkUrlService(null) {
            @Override
            public String urlFor(BlogRegion region) {
                return "https://blog.example.com";
            }
        };

        Link link = buildMonitoredLink(1L);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        service.advanceCondition(link, LinkMonitorPolicy.OFFLINE, true, now, false);

        assertTrue(queuedRecipients.isEmpty());
        Map<String, Object> state = LinkMonitorPolicy.lifecycleState(link, LinkMonitorPolicy.OFFLINE);
        assertEquals("BUFFERING", state.get("phase"));
        assertNull(state.get("warningNotified"));

        Link linkWithEmail = buildMonitoredLink(2L);
        service.advanceCondition(linkWithEmail, LinkMonitorPolicy.OFFLINE, true, now, true);

        assertEquals(List.of("owner@friend.example.com"), queuedRecipients);
        Map<String, Object> stateWithEmail = LinkMonitorPolicy.lifecycleState(linkWithEmail, LinkMonitorPolicy.OFFLINE);
        assertEquals(Boolean.TRUE, stateWithEmail.get("warningNotified"));
    }

    private Link buildMonitoredLink(long id) {
        Link link = new Link();
        link.id = id;
        link.name = "Friend Site";
        link.url = "https://friend.example.com";
        link.email = "owner@friend.example.com";
        Map<String, Object> settings = new HashMap<>();
        settings.put("hideWhenOffline", true);
        settings.put("notifyOnOffline", true);
        settings.put("offlineGraceDays", 7);
        Map<String, Object> lifecycle = new HashMap<>();
        Map<String, Object> offlineState = new HashMap<>();
        offlineState.put("phase", "ACTIVE");
        offlineState.put("consecutiveFailures", 2);
        lifecycle.put(LinkMonitorPolicy.OFFLINE, offlineState);
        settings.put("autoHideLifecycle", lifecycle);
        link.settings = settings;
        return link;
    }
}
