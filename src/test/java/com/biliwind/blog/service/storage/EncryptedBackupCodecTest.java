package com.biliwind.blog.service.storage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EncryptedBackupCodecTest {
    @Test
    void onePasswordRestoresSelfContainedCompressedBackup() throws Exception {
        String uuid = UUID.randomUUID().toString();
        byte[] original = "private media backup ".repeat(200).getBytes(StandardCharsets.UTF_8);
        char[] password = "example-master-password-for-test".toCharArray();
        Path encrypted = EncryptedBackupCodec.encrypt(new ByteArrayInputStream(original), uuid, password);
        Path restored = null;
        try {
            assertTrue(Files.size(encrypted) < original.length);
            try (var source = Files.newInputStream(encrypted)) {
                restored = EncryptedBackupCodec.decrypt(source, uuid, password);
            }
            assertArrayEquals(original, Files.readAllBytes(restored));
            try (var source = Files.newInputStream(encrypted)) {
                assertThrows(Exception.class, () -> EncryptedBackupCodec.decrypt(
                        source, uuid, "wrong-master-password".toCharArray()));
            }
            try (var source = Files.newInputStream(encrypted)) {
                assertThrows(Exception.class, () -> EncryptedBackupCodec.decrypt(
                        source, UUID.randomUUID().toString(), password));
            }
        } finally {
            Arrays.fill(password, '\0');
            Files.deleteIfExists(encrypted);
            if (restored != null) Files.deleteIfExists(restored);
        }
    }
}
