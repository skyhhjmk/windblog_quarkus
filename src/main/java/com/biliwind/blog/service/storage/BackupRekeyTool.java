package com.biliwind.blog.service.storage;

import java.io.Console;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Re-encrypt one self-contained backup with a replacement master password. */
public final class BackupRekeyTool {
    private BackupRekeyTool() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: BackupRekeyTool <backup-file> <object-uuid> <new-backup-file>");
        }
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("An interactive terminal is required");
        }
        char[] oldPassword = console.readPassword("Current master password: ");
        char[] newPassword = console.readPassword("New master password: ");
        if (oldPassword == null || newPassword == null || newPassword.length < 16) {
            throw new IllegalArgumentException("Both passwords are required; new password needs at least 16 characters");
        }
        Path plaintext = null;
        Path encrypted = null;
        try {
            try (InputStream source = Files.newInputStream(Path.of(args[0]))) {
                plaintext = EncryptedBackupCodec.decrypt(source, args[1], oldPassword);
            }
            try (InputStream source = Files.newInputStream(plaintext)) {
                encrypted = EncryptedBackupCodec.encrypt(source, args[1], newPassword);
            }
            // Validate the replacement before delivering it.
            Path verified;
            try (InputStream source = Files.newInputStream(encrypted)) {
                verified = EncryptedBackupCodec.decrypt(source, args[1], newPassword);
            }
            try {
                if (!EncryptedBackupCodec.sha256Hex(plaintext)
                        .equals(EncryptedBackupCodec.sha256Hex(verified))) {
                    throw new IllegalStateException("Rekeyed backup digest mismatch");
                }
            } finally {
                Files.deleteIfExists(verified);
            }
            try (InputStream source = Files.newInputStream(encrypted);
                 var output = Files.newOutputStream(Path.of(args[2]),
                         java.nio.file.StandardOpenOption.CREATE_NEW)) {
                source.transferTo(output);
            }
        } finally {
            Arrays.fill(oldPassword, '\0');
            Arrays.fill(newPassword, '\0');
            if (plaintext != null) Files.deleteIfExists(plaintext);
            if (encrypted != null) Files.deleteIfExists(encrypted);
        }
    }
}
