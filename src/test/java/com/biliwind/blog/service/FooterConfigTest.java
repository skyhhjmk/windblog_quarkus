package com.biliwind.blog.service;

import com.biliwind.blog.model.BlogRegion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FooterConfigTest {

    @Test
    void footerRecordsCanBeLimitedToTheSelectedRegion() {
        assertTrue(ConfigTemplateData.isFooterRecordsVisible("all", BlogRegion.US));
        assertTrue(ConfigTemplateData.isFooterRecordsVisible("cn", BlogRegion.CN));
        assertFalse(ConfigTemplateData.isFooterRecordsVisible("cn", BlogRegion.GLOBAL));
        assertFalse(ConfigTemplateData.isFooterRecordsVisible("unknown", BlogRegion.GLOBAL));
    }

    @Test
    void footerLinksAcceptOnlyAbsoluteHttpUrls() {
        assertEquals("https://beian.example.cn/query?id=123",
                ConfigTemplateData.safeExternalHttpUrl(" https://beian.example.cn/query?id=123 "));
        assertEquals("", ConfigTemplateData.safeExternalHttpUrl("javascript:alert(1)"));
        assertEquals("", ConfigTemplateData.safeExternalHttpUrl("/portal/record"));
        assertEquals("", ConfigTemplateData.safeExternalHttpUrl("https://user:pass@example.cn"));
    }
}
