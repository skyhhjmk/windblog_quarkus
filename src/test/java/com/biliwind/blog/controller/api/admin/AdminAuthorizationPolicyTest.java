package com.biliwind.blog.controller.api.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAuthorizationPolicyTest {

    @Test
    void shouldRequireSuperAdminForDangerousActions() {
        assertTrue(AdminAuthorizationPolicy.decide("POST", "/api/admin/database/migrate").superAdminOnly());
        assertTrue(AdminAuthorizationPolicy.decide("POST", "/api/admin/import").superAdminOnly());
        assertTrue(AdminAuthorizationPolicy.decide("POST", "/api/admin/edge-nodes/node-a/issue-certificate").superAdminOnly());
        assertTrue(AdminAuthorizationPolicy.decide("POST", "/api/admin/storage/dead-letter/1/retry").superAdminOnly());
        assertTrue(AdminAuthorizationPolicy.decide("PUT", "/api/admin/settings/mail.password").superAdminOnly());
        assertTrue(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/import"));
        assertTrue(AdminAuthorizationPolicy.requiresIdempotencyKey("DELETE", "/api/admin/dead-letters/1"));
        assertTrue(AdminAuthorizationPolicy.decide("GET", "/api/admin/media").action().equals("media.read"));
        assertTrue(AdminAuthorizationPolicy.decide("POST", "/api/admin/posts/1/publish").action().equals("post.publish"));
        assertTrue(AdminAuthorizationPolicy.decide("POST", "/api/admin/queues/1/publish").action().equals("queue.publish"));
        assertEquals("media.download_original",
                AdminAuthorizationPolicy.decide("POST", "/api/admin/media/7/original-download-ticket").action());
        assertTrue(AdminAuthorizationPolicy.requiresStepUp(
                "POST", "/api/admin/media/7/original-download-ticket"));
        assertTrue(AdminAuthorizationPolicy.requiresIdempotencyKey(
                "POST", "/api/admin/media/7/original-download-ticket"));
        assertTrue(AdminAuthorizationPolicy.decide("GET", "/api/admin/users").action().equals("user.read"));
        assertTrue(AdminAuthorizationPolicy.decide("POST", "/api/admin/storage/edge-nodes/node-a/sync").superAdminOnly());
        assertTrue(AdminAuthorizationPolicy.decide("POST", "/api/admin/security/content-access-tickets/rotate-key").superAdminOnly());
        assertTrue(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/security/content-access-tickets/rotate-key"));
        assertTrue(AdminAuthorizationPolicy.decide("GET", "/api/admin/edge-nodes/node-a/deployment-zip").superAdminOnly());
        assertTrue(AdminAuthorizationPolicy.requiresStepUp("GET", "/api/admin/edge-nodes/node-a/deployment-zip"));
        assertTrue(AdminAuthorizationPolicy.requiresIdempotencyKey("GET", "/api/admin/edge-nodes/node-a/deployment-zip"));
    }

    @Test
    void shouldAllowNormalReadActionsForAuthenticatedAdmins() {
        assertFalse(AdminAuthorizationPolicy.decide("GET", "/api/admin/posts").superAdminOnly());
        assertFalse(AdminAuthorizationPolicy.decide("GET", "/api/admin/settings").superAdminOnly());
        assertFalse(AdminAuthorizationPolicy.decide("GET", "/api/admin/edge-nodes/node-a").superAdminOnly());
        assertFalse(AdminAuthorizationPolicy.requiresStepUp("GET", "/api/admin/settings"));
    }

    @Test
    void shouldNotClassifyUnrelatedGenerateEndpointsAsCertificateActions() {
        assertTrue(AdminAuthorizationPolicy.decide("POST", "/api/admin/media/generate").action().equals("media.write"));
    }

    @Test
    void shouldKeepImageProcessingActionsSeparateFromStorageActions() {
        assertEquals("image_processing.read", AdminAuthorizationPolicy.decide(
                "GET", "/api/admin/storage/image-processing/configs").action());
        assertEquals("image_processing.write", AdminAuthorizationPolicy.decide(
                "PUT", "/api/admin/storage/image-processing/configs").action());
    }

    @Test
    void shouldProtectFinancialEmailAndSystemSideEffects() {
        assertTrue(AdminAuthorizationPolicy.decide("POST", "/api/admin/users/7/wallet/adjust")
                .action().equals("wallet.write"));
        assertTrue(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/users/7/wallet/adjust"));
        assertTrue(AdminAuthorizationPolicy.requiresIdempotencyKey("POST", "/api/admin/users/7/wallet/adjust"));
        assertTrue(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/email-deliveries/7/retry"));
        assertTrue(AdminAuthorizationPolicy.requiresStepUp(
                "POST", "/api/admin/email-deliveries/fail-pending"));
        assertTrue(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/email-channels/7/test"));
        assertTrue(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/email-templates/7/test"));
        assertTrue(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/email-campaigns"));
        assertTrue(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/system/decrypt-error"));
        assertTrue(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/system/sync-cluster-keys"));
    }

    @Test
    void shouldKeepResourceActionsScopedToTheirOwnEndpoints() {
        assertEquals("edge.revoke_certificate", AdminAuthorizationPolicy.decide(
                "POST", "/api/admin/edge-nodes/node-a/revoke-certificate").action());
        assertEquals("dead_letter.replay", AdminAuthorizationPolicy.decide(
                "POST", "/api/admin/dead-letters/7/retry").action());
        assertEquals("email_deliveries.retry", AdminAuthorizationPolicy.decide(
                "POST", "/api/admin/email-deliveries/7/retry").action());
        assertEquals("email_deliveries.fail_pending", AdminAuthorizationPolicy.decide(
                "POST", "/api/admin/email-deliveries/fail-pending").action());
        assertEquals("media.retry", AdminAuthorizationPolicy.decide(
                "POST", "/api/admin/media/7/retry").action());
        assertEquals("media.scan", AdminAuthorizationPolicy.decide(
                "POST", "/api/admin/media/7/virus-scan").action());
        assertEquals("repost.revoke", AdminAuthorizationPolicy.decide(
                "POST", "/api/admin/repost/tokens/7/revoke").action());
        assertTrue(AdminAuthorizationPolicy.requiresStepUp(
                "POST", "/api/admin/repost/tokens/7/revoke"));
    }

    @Test
    void shouldMapManagedResourcePrefixesToStableActions() {
        assertEquals("auth.read", AdminAuthorizationPolicy.decide("GET", "/api/admin/auth/me").action());
        assertEquals("audit_logs.read", AdminAuthorizationPolicy.decide("GET", "/api/admin/audit-logs").action());
        assertEquals("category.write", AdminAuthorizationPolicy.decide("POST", "/api/admin/categories").action());
        assertEquals("comment.write", AdminAuthorizationPolicy.decide("PUT", "/api/admin/comments/7").action());
        assertEquals("email_channels.write", AdminAuthorizationPolicy.decide(
                "POST", "/api/admin/email-channels").action());
        assertEquals("email_campaigns.read", AdminAuthorizationPolicy.decide(
                "GET", "/api/admin/email-campaigns").action());
        assertEquals("email_templates.write", AdminAuthorizationPolicy.decide(
                "PUT", "/api/admin/email-templates/7").action());
        assertEquals("elasticsearch.write", AdminAuthorizationPolicy.decide(
                "POST", "/api/admin/elasticsearch/rebuild").action());
        assertEquals("image_processing.write", AdminAuthorizationPolicy.decide(
                "POST", "/api/admin/storage/image-processing/configs/test").action());
        assertEquals("link.write", AdminAuthorizationPolicy.decide("POST", "/api/admin/links").action());
        assertEquals("region.delete", AdminAuthorizationPolicy.decide("DELETE", "/api/admin/regions/7").action());
        assertEquals("store.write", AdminAuthorizationPolicy.decide("POST", "/api/admin/store").action());
        assertEquals("system.read", AdminAuthorizationPolicy.decide("GET", "/api/admin/system/monitor").action());
        assertEquals("user.write", AdminAuthorizationPolicy.decide("PUT", "/api/admin/users/7").action());
        assertEquals("outbox.read", AdminAuthorizationPolicy.decide("GET", "/api/admin/outbox").action());
        assertEquals("outbox.replay", AdminAuthorizationPolicy.decide(
                "POST", "/api/admin/outbox/7/replay").action());
    }

    @Test
    void shouldKeepSensitiveResourceWritesBehindExplicitStepUpOrSuperAdmin() {
        assertTrue(AdminAuthorizationPolicy.decide("POST", "/api/admin/settings/site.confirm").superAdminOnly());
        assertTrue(AdminAuthorizationPolicy.decide("POST", "/api/admin/permissions/roles/ADMIN").superAdminOnly());
        assertTrue(AdminAuthorizationPolicy.decide("POST", "/api/admin/database/seed").superAdminOnly());
        assertTrue(AdminAuthorizationPolicy.decide("POST", "/api/admin/dead-letters/7/retry").superAdminOnly());
        assertTrue(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/email-campaigns"));
        assertTrue(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/users/7/wallet/check-in-reward"));
        assertTrue(AdminAuthorizationPolicy.decide("POST", "/api/admin/outbox/7/replay").superAdminOnly());
        assertTrue(AdminAuthorizationPolicy.requiresIdempotencyKey("POST", "/api/admin/outbox/7/replay"));
        assertFalse(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/categories"));
    }
}
