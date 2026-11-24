package com.biliwind.blog.service;

import com.biliwind.blog.service.IpLocationService.IpLocationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpLocationServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesChinesePublicIpWithIsp() {
        AtomicInteger calls = new AtomicInteger();
        IpLocationService service = new IpLocationService(
                MAPPER, null, true, "https://ipnest.mliev.com/api/v1/lookup", "community", 3000) {
            @Override
            protected String fetchLookupJson(String normalizedIp) {
                calls.incrementAndGet();
                return """
                        {"code":0,"message":"success","data":{"ip":"223.5.5.5","ip_version":4,"found":true,"scope":"public","continent":null,"country":"中国","region":"浙江省","city":"杭州市","district":null,"isp":"阿里","country_code":"CN"}}
                        """;
            }
        };

        IpLocationResult result = service.lookup("223.5.5.5");
        assertTrue(result.found());
        assertEquals("223.5.5.5", result.ip());
        assertEquals("中国", result.country());
        assertEquals("浙江省", result.region());
        assertEquals("杭州市", result.city());
        assertEquals("阿里", result.isp());
        assertEquals("CN", result.countryCode());
        assertEquals("中国 浙江省 杭州市", result.displayLocation());
        assertEquals("中国 浙江省 杭州市 (阿里)", result.fullLocation());

        // 命中内存缓存时不重复请求上游
        IpLocationResult cached = service.lookup("223.5.5.5");
        assertEquals(result, cached);
        assertEquals(1, calls.get());
    }

    @Test
    void parsesOverseasPublicIpWithoutIsp() {
        IpLocationService service = new IpLocationService(
                MAPPER, null, true, "https://ipnest.mliev.com/api/v1/lookup", "community", 3000) {
            @Override
            protected String fetchLookupJson(String normalizedIp) {
                return """
                        {"code":0,"message":"success","data":{"ip":"1.1.1.1","ip_version":4,"found":true,"scope":"public","continent":null,"country":"Australia","region":"Queensland","city":"Brisbane","district":null,"isp":null,"country_code":"AU"}}
                        """;
            }
        };

        IpLocationResult result = service.lookup("[1.1.1.1]");
        assertTrue(result.found());
        assertEquals("Australia Queensland Brisbane", result.displayLocation());
        assertEquals("Australia Queensland Brisbane", result.fullLocation());
    }

    @Test
    void deduplicatesRepeatedRegionAndCity() {
        IpLocationService service = new IpLocationService(
                MAPPER, null, true, "https://ipnest.mliev.com/api/v1/lookup", "community", 3000) {
            @Override
            protected String fetchLookupJson(String normalizedIp) {
                return """
                        {"code":0,"message":"success","data":{"ip":"202.106.0.20","ip_version":4,"found":true,"scope":"public","country":"中国","region":"北京市","city":"北京市","district":"海淀区","isp":"联通","country_code":"CN"}}
                        """;
            }
        };

        IpLocationResult result = service.lookup("202.106.0.20:443");
        assertTrue(result.found());
        assertEquals("202.106.0.20", result.ip());
        assertEquals("中国 北京市 海淀区", result.displayLocation());
        assertEquals("中国 北京市 海淀区 (联通)", result.fullLocation());
    }

    @Test
    void shortCircuitsLoopbackAndPrivateIpsWithoutUpstreamCall() {
        AtomicInteger calls = new AtomicInteger();
        IpLocationService service = new IpLocationService(
                MAPPER, null, true, "https://ipnest.mliev.com/api/v1/lookup", "community", 3000) {
            @Override
            protected String fetchLookupJson(String normalizedIp) {
                calls.incrementAndGet();
                throw new AssertionError("Should not call upstream for special/private IP: " + normalizedIp);
            }
        };

        for (String privateIp : new String[]{"127.0.0.1", "192.168.1.5", "10.0.0.1", "172.16.8.1", "100.64.1.2", "::1"}) {
            IpLocationResult result = service.lookup(privateIp);
            assertFalse(result.found());
            assertEquals("special", result.scope());
            assertNull(result.displayLocation());
            assertEquals("本地/内网", result.fullLocation());
        }
        assertEquals(0, calls.get());
        assertNull(service.normalizeIp("unknown"));
        assertNull(service.normalizeIp("not-an-ip"));
    }

    @Test
    void degradesGracefullyOnUpstreamFailureOrInvalidJson() {
        IpLocationService throwingService = new IpLocationService(
                MAPPER, null, true, "https://ipnest.mliev.com/api/v1/lookup", "community", 3000) {
            @Override
            protected String fetchLookupJson(String normalizedIp) {
                throw new IllegalStateException("network timeout");
            }
        };
        IpLocationResult failed = throwingService.lookup("8.8.8.8");
        assertFalse(failed.found());
        assertEquals("8.8.8.8", failed.ip());
        assertNull(failed.displayLocation());
        assertNull(failed.fullLocation());

        IpLocationService invalidJsonService = new IpLocationService(
                MAPPER, null, true, "https://ipnest.mliev.com/api/v1/lookup", "community", 3000) {
            @Override
            protected String fetchLookupJson(String normalizedIp) {
                return "{not-json";
            }
        };
        IpLocationResult invalid = invalidJsonService.lookup("8.8.4.4");
        assertFalse(invalid.found());
        assertNull(invalid.fullLocation());
    }
}
