package com.biliwind.blog.service.ai;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexCreatorHmacTest {
    @Test
    void signsTheSamePayloadAsTheCodexCreatorClient() {
        String body = "{\"operation\":\"tag.list\",\"input\":{}}";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String digest = CodexCreatorHmac.bodyDigest(body);
        String signature = CodexCreatorHmac.sign(
                "secret", CodexCreatorHmac.CLIENT_ID, timestamp, "nonce-1", digest);

        assertEquals(64, digest.length());
        assertTrue(CodexCreatorHmac.constantTimeEquals(signature,
                CodexCreatorHmac.sign("secret", "codex-creator", timestamp, "nonce-1", digest)));
        assertFalse(CodexCreatorHmac.constantTimeEquals(signature,
                CodexCreatorHmac.sign("secret", "codex-creator", timestamp, "nonce-1",
                        CodexCreatorHmac.bodyDigest(body + "!"))));
    }
}
