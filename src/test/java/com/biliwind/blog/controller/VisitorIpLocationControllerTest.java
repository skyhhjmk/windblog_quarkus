package com.biliwind.blog.controller;

import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.controller.VisitorIpLocationController.VisitorLocationResponse;
import com.biliwind.blog.service.IpLocationService;
import com.biliwind.blog.service.IpLocationService.IpLocationResult;
import com.biliwind.blog.service.security.ClientIpResolver;
import com.biliwind.blog.service.security.SecurityRateLimitService;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisitorIpLocationControllerTest {

    @Test
    void returnsWelcomeMessageForPublicIpInChineseAndEnglish() {
        VisitorIpLocationController controller = new VisitorIpLocationController();
        controller.clientIpResolver = new ClientIpResolver() {
            @Override
            public ClientIpResolution resolve(RoutingContext context) {
                return new ClientIpResolution("223.5.5.5", "223.5.5.5", null, false, null);
            }
        };
        controller.ipLocationService = new IpLocationService() {
            @Override
            public IpLocationResult lookup(String rawIp) {
                return new IpLocationResult(
                        "223.5.5.5", true, "public",
                        "中国", "浙江省", "杭州市", null, "阿里", "CN",
                        "中国 浙江省 杭州市", "中国 浙江省 杭州市 (阿里)");
            }
        };
        controller.securityRateLimitService = new SecurityRateLimitService() {
            @Override
            public boolean tryAcquire(String key, int limit, Duration window) {
                return true;
            }
        };

        LanguageContext zhLang = new LanguageContext();
        zhLang.setLang("zh-CN");
        controller.languageContext = zhLang;

        Response zhResp = controller.currentVisitorLocation(null);
        assertEquals(200, zhResp.getStatus());
        assertEquals("no-store", zhResp.getHeaderString("Cache-Control"));
        VisitorLocationResponse zhBody = (VisitorLocationResponse) zhResp.getEntity();
        assertNotNull(zhBody);
        assertTrue(zhBody.found());
        assertEquals("欢迎来自中国 浙江省 杭州市的访客（IP：223.5.5.5）", zhBody.welcomeMessage());
        assertEquals("中国 浙江省 杭州市 (阿里)", zhBody.location());

        LanguageContext enLang = new LanguageContext();
        enLang.setLang("en-US");
        controller.languageContext = enLang;

        Response enResp = controller.currentVisitorLocation(null);
        VisitorLocationResponse enBody = (VisitorLocationResponse) enResp.getEntity();
        assertEquals("Welcome, visitor from 中国 浙江省 杭州市 (IP: 223.5.5.5)", enBody.welcomeMessage());
    }

    @Test
    void returnsLocalNetworkWelcomeMessageForSpecialIpAndNullWhenPublicLookupFails() {
        VisitorIpLocationController controller = new VisitorIpLocationController();
        controller.clientIpResolver = new ClientIpResolver() {
            @Override
            public ClientIpResolution resolve(RoutingContext context) {
                return new ClientIpResolution("127.0.0.1", "127.0.0.1", null, false, null);
            }
        };
        controller.ipLocationService = new IpLocationService() {
            @Override
            public IpLocationResult lookup(String rawIp) {
                return new IpLocationResult(
                        "127.0.0.1", false, "special",
                        null, null, null, null, null, null,
                        null, "本地/内网");
            }
        };
        controller.securityRateLimitService = new SecurityRateLimitService() {
            @Override
            public boolean tryAcquire(String key, int limit, Duration window) {
                return true;
            }
        };

        LanguageContext zhLang = new LanguageContext();
        zhLang.setLang("zh-CN");
        controller.languageContext = zhLang;

        Response zhResp = controller.currentVisitorLocation(null);
        assertEquals(200, zhResp.getStatus());
        VisitorLocationResponse zhBody = (VisitorLocationResponse) zhResp.getEntity();
        assertFalse(zhBody.found());
        assertEquals("本地/内网", zhBody.displayLocation());
        assertEquals("欢迎来自本地/内网的访客（IP：127.0.0.1）", zhBody.welcomeMessage());
        assertEquals("本地/内网", zhBody.location());

        LanguageContext enLang = new LanguageContext();
        enLang.setLang("en-US");
        controller.languageContext = enLang;

        Response enResp = controller.currentVisitorLocation(null);
        VisitorLocationResponse enBody = (VisitorLocationResponse) enResp.getEntity();
        assertFalse(enBody.found());
        assertEquals("Local Network", enBody.displayLocation());
        assertEquals("Welcome, visitor from Local Network (IP: 127.0.0.1)", enBody.welcomeMessage());

        // 公网 IP 查询失败时仍返回 welcomeMessage = null
        controller.clientIpResolver = new ClientIpResolver() {
            @Override
            public ClientIpResolution resolve(RoutingContext context) {
                return new ClientIpResolution("8.8.8.8", "8.8.8.8", null, false, null);
            }
        };
        controller.ipLocationService = new IpLocationService() {
            @Override
            public IpLocationResult lookup(String rawIp) {
                return IpLocationResult.empty("8.8.8.8");
            }
        };
        Response emptyResp = controller.currentVisitorLocation(null);
        VisitorLocationResponse emptyBody = (VisitorLocationResponse) emptyResp.getEntity();
        assertFalse(emptyBody.found());
        assertNull(emptyBody.displayLocation());
        assertNull(emptyBody.welcomeMessage());
    }

    @Test
    void returns429WhenRateLimitExceeded() {
        VisitorIpLocationController controller = new VisitorIpLocationController();
        controller.clientIpResolver = new ClientIpResolver() {
            @Override
            public ClientIpResolution resolve(RoutingContext context) {
                return new ClientIpResolution("1.1.1.1", "1.1.1.1", null, false, null);
            }
        };
        controller.ipLocationService = new IpLocationService();
        AtomicBoolean called = new AtomicBoolean(false);
        controller.securityRateLimitService = new SecurityRateLimitService() {
            @Override
            public boolean tryAcquire(String key, int limit, Duration window) {
                called.set(true);
                return false;
            }
        };

        Response resp = controller.currentVisitorLocation(null);
        assertTrue(called.get());
        assertEquals(429, resp.getStatus());
        assertEquals("no-store", resp.getHeaderString("Cache-Control"));
    }
}
