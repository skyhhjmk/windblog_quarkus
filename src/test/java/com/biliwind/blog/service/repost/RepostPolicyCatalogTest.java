package com.biliwind.blog.service.repost;

import com.biliwind.blog.model.Post;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepostPolicyCatalogTest {

    private final RepostPolicyCatalog catalog = new RepostPolicyCatalog();

    @Test
    void shouldExposeAllBuiltInPoliciesInStableOrder() {
        assertEquals(List.of(
                        RepostPolicyCatalog.REQUEST_REQUIRED,
                        RepostPolicyCatalog.CC_BY_4_0,
                        RepostPolicyCatalog.CC_BY_SA_4_0,
                        RepostPolicyCatalog.CC_BY_NC_4_0,
                        RepostPolicyCatalog.CC_BY_NC_SA_4_0,
                        RepostPolicyCatalog.CC_BY_ND_4_0,
                        RepostPolicyCatalog.CC_BY_NC_ND_4_0,
                        RepostPolicyCatalog.CC0_1_0),
                catalog.list().stream().map(RepostPolicyCatalog.Policy::code).toList());
        assertTrue(catalog.resolve(RepostPolicyCatalog.CC_BY_4_0, null).licenseUrl().contains("creativecommons.org"));
        assertFalse(catalog.resolve(RepostPolicyCatalog.CC_BY_4_0, null).requiresApplication());
    }

    @Test
    void shouldDefaultMissingAdminValueToAuthorizationRequired() {
        assertEquals(RepostPolicyCatalog.REQUEST_REQUIRED, catalog.require(null).code());
        assertEquals(RepostPolicyCatalog.REQUEST_REQUIRED, catalog.require(" ").code());
        assertTrue(catalog.require(null).requiresApplication());
    }

    @Test
    void shouldRejectUnknownAdminPolicyCode() {
        assertThrows(BadRequestException.class, () -> catalog.require("MADE_UP_POLICY"));
    }

    @Test
    void shouldPreferDedicatedPolicyAndSupportLegacyContentDeclaration() {
        assertEquals(RepostPolicyCatalog.CC_BY_NC_4_0,
                catalog.resolve(null, List.of("CC_BY_NC_4_0")).code());
        assertEquals(RepostPolicyCatalog.CC_BY_4_0,
                catalog.resolve(RepostPolicyCatalog.CC_BY_4_0, List.of("CC_BY_NC_4_0")).code());
        assertEquals(RepostPolicyCatalog.REQUEST_REQUIRED,
                catalog.resolve(null, List.of("AI_GENERATED_CONTENT")).code());

        Post post = new Post();
        post.contentDeclarations = List.of("CC_BY_NC_4_0");
        assertEquals(RepostPolicyCatalog.CC_BY_NC_4_0, catalog.resolve(post).code());
        post.repostPolicyCode = RepostPolicyCatalog.CC0_1_0;
        assertEquals(RepostPolicyCatalog.CC0_1_0, catalog.resolve(post).code());
    }

    @Test
    void shouldBuildDirectCopyTextWithConditionsAndWithoutTrackingData() {
        RepostPolicyCatalog.Policy policy = catalog.resolve(RepostPolicyCatalog.CC_BY_NC_ND_4_0, null);
        String copy = catalog.buildDirectCopyText(policy, "示例文章", "https://example.com/post/demo");

        assertNotNull(copy);
        assertTrue(copy.contains("本文使用 CC BY-NC-ND 4.0 协议，无须申请转载"));
        assertTrue(copy.contains("仅限非商业转载"));
        assertTrue(copy.contains("不得改编"));
        assertTrue(copy.contains("https://example.com/post/demo"));
        assertTrue(copy.contains(policy.licenseUrl()));
        assertFalse(copy.contains("RPL-"));
        assertFalse(copy.contains("/r/"));
    }
}
