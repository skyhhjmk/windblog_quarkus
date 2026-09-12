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
        assertEquals("edge.connection.connect",
                AdminAuthorizationPolicy.decide("POST", "/api/admin/edge-nodes/connect").action());
        assertTrue(AdminAuthorizationPolicy.requiresStepUp(
                "POST", "/api/admin/edge-nodes/connect"));
        assertEquals("edge.connection.bootstrap",
                AdminAuthorizationPolicy.decide("POST", "/api/admin/edge-nodes/connection/bootstrap").action());
        assertFalse(AdminAuthorizationPolicy.decide("POST", "/api/admin/edge-nodes/connection/bootstrap")
                .superAdminOnly());
        assertTrue(AdminAuthorizationPolicy.requiresStepUp(
                "POST", "/api/admin/edge-nodes/connection/bootstrap"));
        assertTrue(AdminAuthorizationPolicy.requiresIdempotencyKey(
                "POST", "/api/admin/edge-nodes/connection/bootstrap"));
        assertFalse(AdminAuthorizationPolicy.decide("POST", "/api/admin/storage/dead-letter/1/retry").superAdminOnly());
        assertTrue(AdminAuthorizationPolicy.decide("PUT", "/api/admin/settings/mail.password").superAdminOnly());
        assertTrue(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/import"));
        assertTrue(AdminAuthorizationPolicy.requiresIdempotencyKey("POST", "/api/admin/import"));
        assertTrue(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/import/test-connection"));
        assertTrue(AdminAuthorizationPolicy.requiresIdempotencyKey("POST", "/api/admin/import/test-connection"));
        assertTrue(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/import/analyze"));
        assertTrue(AdminAuthorizationPolicy.requiresIdempotencyKey("POST", "/api/admin/import/analyze-sql"));
        assertTrue(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/import/execute"));
        assertFalse(AdminAuthorizationPolicy.requiresIdempotencyKey("DELETE", "/api/admin/dead-letters/1"));
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
        assertFalse(AdminAuthorizationPolicy.requiresIdempotencyKey("GET", "/api/admin/edge-nodes/node-a/deployment-zip"));
    }

    @Test
    void shouldAllowNormalReadActionsForAuthenticatedAdmins() {
        assertFalse(AdminAuthorizationPolicy.decide("GET", "/api/admin/posts").superAdminOnly());
        assertFalse(AdminAuthorizationPolicy.decide("GET", "/api/admin/settings").superAdminOnly());
        assertFalse(AdminAuthorizationPolicy.decide("GET", "/api/admin/edge-nodes/node-a").superAdminOnly());
        assertFalse(AdminAuthorizationPolicy.requiresStepUp("GET", "/api/admin/settings"));
    }

    @Test
    void shouldKeepStepUpBootstrapOutsideHighRiskRequestChecks() {
        assertFalse(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/auth/step-up"));
        assertFalse(AdminAuthorizationPolicy.requiresIdempotencyKey("POST", "/api/admin/auth/step-up"));
        assertEquals("auth.write",
                AdminAuthorizationPolicy.decide("POST", "/api/admin/auth/step-up").action());
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
        assertFalse(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/email-deliveries/7/retry"));
        assertTrue(AdminAuthorizationPolicy.requiresStepUp(
                "POST", "/api/admin/email-deliveries/fail-pending"));
        assertFalse(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/email-channels/7/test"));
        assertFalse(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/email-templates/7/test"));
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
        assertEquals("repost.read", AdminAuthorizationPolicy.decide(
                "GET", "/api/admin/repost/policies").action());
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
        assertEquals("system.read", AdminAuthorizationPolicy.decide(
                "GET", "/api/admin/system/observability/grafana-embed-url").action());
        assertEquals("codex_creator.read", AdminAuthorizationPolicy.decide(
                "GET", "/api/admin/codex-creator/config").action());
        assertEquals("codex_creator.write", AdminAuthorizationPolicy.decide(
                "PUT", "/api/admin/codex-creator/config").action());
        assertEquals("user.write", AdminAuthorizationPolicy.decide("PUT", "/api/admin/users/7").action());
        assertEquals("outbox.read", AdminAuthorizationPolicy.decide("GET", "/api/admin/outbox").action());
        assertEquals("outbox.replay", AdminAuthorizationPolicy.decide(
                "POST", "/api/admin/outbox/7/replay").action());
    }

    @Test
    void shouldKeepSensitiveResourceWritesBehindExplicitStepUpOrSuperAdmin() {
        assertFalse(AdminAuthorizationPolicy.decide("POST", "/api/admin/settings/site.confirm").superAdminOnly());
        assertTrue(AdminAuthorizationPolicy.decide("PUT", "/api/admin/codex-creator/config").superAdminOnly());
        assertTrue(AdminAuthorizationPolicy.requiresStepUp("PUT", "/api/admin/codex-creator/config"));
        assertTrue(AdminAuthorizationPolicy.requiresIdempotencyKey("PUT", "/api/admin/codex-creator/config"));
        assertFalse(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/codex-creator/topic-runs"));
        assertTrue(AdminAuthorizationPolicy.requiresIdempotencyKey("POST", "/api/admin/codex-creator/topic-runs"));
        assertFalse(AdminAuthorizationPolicy.requiresStepUp("PUT", "/api/admin/codex-creator/topic-automation"));
        assertFalse(AdminAuthorizationPolicy.requiresIdempotencyKey("PUT", "/api/admin/codex-creator/topic-automation"));
        assertFalse(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/codex-creator/topic-seeds"));
        assertFalse(AdminAuthorizationPolicy.requiresIdempotencyKey("DELETE", "/api/admin/codex-creator/topic-seeds/7"));
        assertFalse(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/codex-creator/topics/7/review"));
        assertTrue(AdminAuthorizationPolicy.requiresIdempotencyKey("POST", "/api/admin/codex-creator/topics/7/draft"));
        assertFalse(AdminAuthorizationPolicy.requiresStepUp(
                "POST", "/api/admin/codex-creator/topics/7/draft/regenerate"));
        assertTrue(AdminAuthorizationPolicy.requiresIdempotencyKey(
                "POST", "/api/admin/codex-creator/topics/7/draft/regenerate"));
        assertTrue(AdminAuthorizationPolicy.decide("POST", "/api/admin/permissions/roles/ADMIN").superAdminOnly());
        assertTrue(AdminAuthorizationPolicy.decide("POST", "/api/admin/database/seed").superAdminOnly());
        assertFalse(AdminAuthorizationPolicy.decide("POST", "/api/admin/dead-letters/7/retry").superAdminOnly());
        assertTrue(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/email-campaigns"));
        assertTrue(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/users/7/wallet/check-in-reward"));
        assertFalse(AdminAuthorizationPolicy.decide("POST", "/api/admin/outbox/7/replay").superAdminOnly());
        assertTrue(AdminAuthorizationPolicy.requiresIdempotencyKey("POST", "/api/admin/outbox/7/replay"));
        assertFalse(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/categories"));
    }

    @Test
    void shouldClassifyDailyAdminWorkWithoutStepUp() {
        assertEquals(AdminAuthorizationPolicy.RiskLevel.DAILY,
                AdminAuthorizationPolicy.decide("POST", "/api/admin/posts/7/publish").riskLevel());
        assertEquals(AdminAuthorizationPolicy.RiskLevel.DAILY,
                AdminAuthorizationPolicy.decide("POST", "/api/admin/posts/7/revisions/2/publish").riskLevel());
        assertEquals(AdminAuthorizationPolicy.RiskLevel.DAILY,
                AdminAuthorizationPolicy.decide("POST", "/api/admin/queues/posts/publish").riskLevel());
        assertEquals(AdminAuthorizationPolicy.RiskLevel.DAILY,
                AdminAuthorizationPolicy.decide("POST", "/api/admin/outbox/7/replay").riskLevel());
        assertEquals(AdminAuthorizationPolicy.RiskLevel.DAILY,
                AdminAuthorizationPolicy.decide("PUT", "/api/admin/settings/site_info").riskLevel());
        assertFalse(AdminAuthorizationPolicy.requiresStepUp("POST", "/api/admin/posts/7/publish"));
        assertTrue(AdminAuthorizationPolicy.requiresIdempotencyKey("POST", "/api/admin/posts/7/publish"));
    }

    @Test
    void shouldClassifyCredentialsAndIrreversibleChangesAsHighRisk() {
        assertEquals(AdminAuthorizationPolicy.RiskLevel.HIGH,
                AdminAuthorizationPolicy.decide("PUT", "/api/admin/settings/elasticsearch").riskLevel());
        assertEquals(AdminAuthorizationPolicy.RiskLevel.HIGH,
                AdminAuthorizationPolicy.decide("POST", "/api/admin/codex-creator/test-servers").riskLevel());
        assertEquals(AdminAuthorizationPolicy.RiskLevel.HIGH,
                AdminAuthorizationPolicy.decide("POST", "/api/admin/repost/tokens/7/revoke").riskLevel());
        assertEquals(AdminAuthorizationPolicy.RiskLevel.LOW,
                AdminAuthorizationPolicy.decide("GET", "/api/admin/posts").riskLevel());
    }
}
