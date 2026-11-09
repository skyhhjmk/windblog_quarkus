package com.biliwind.blog.service.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoneypotServiceTest {

    private final HoneypotService service = new HoneypotService();

    @Test
    void shouldNotTreatUploadZipAsDecoyPath() {
        assertFalse(service.isDecoyPath("/uploads/aeb635d1-4155-4090-af56-df7f6086e6da.zip"));
        assertFalse(service.isDecoyPath("uploads/aeb635d1-4155-4090-af56-df7f6086e6da.zip"));
        assertFalse(service.isDecoyPath("/uploads/theme.tar.gz"));
        assertFalse(service.isDecoyPath("/uploads/sample.7z"));
        assertFalse(service.isDecoyPath("/uploads/cover.webp"));
    }

    @Test
    void shouldTreatRootBackupAsDecoyPath() {
        assertTrue(service.isDecoyPath("/backup.zip"));
        assertTrue(service.isDecoyPath("/wwwroot.tar.gz"));
        assertTrue(service.isDecoyPath("/.env"));
        assertTrue(service.isDecoyPath("/wp-login.php"));
    }

    @Test
    void shouldNotDetectUploadZipAsSensitiveFileProbe() {
        HoneypotService.Detection uploadZipDetection = service.detect(
                "GET", "https://example.com/uploads/aeb635d1-4155-4090-af56-df7f6086e6da.zip",
                Map.of(), new byte[0]);
        assertFalse(uploadZipDetection.matchedRules().contains("sensitive_file_probe"));
        assertFalse(uploadZipDetection.blocked());

        HoneypotService.Detection uploadTarDetection = service.detect(
                "GET", "https://example.com/uploads/archive.tar.gz",
                Map.of(), new byte[0]);
        assertFalse(uploadTarDetection.matchedRules().contains("sensitive_file_probe"));
    }

    @Test
    void shouldStillDetectRootBackupAndUploadEnvAsSensitiveFileProbe() {
        HoneypotService.Detection rootZipDetection = service.detect(
                "GET", "https://example.com/backup.zip",
                Map.of(), new byte[0]);
        assertTrue(rootZipDetection.matchedRules().contains("sensitive_file_probe"));

        HoneypotService.Detection uploadEnvDetection = service.detect(
                "GET", "https://example.com/uploads/.env",
                Map.of(), new byte[0]);
        assertTrue(uploadEnvDetection.matchedRules().contains("sensitive_file_probe"));
    }
}
