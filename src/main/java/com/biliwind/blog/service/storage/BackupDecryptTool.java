package com.biliwind.blog.service.storage;

import java.io.Console;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Offline recovery: the backup object and its master password are sufficient. */
public final class BackupDecryptTool {
    private BackupDecryptTool() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: BackupDecryptTool <backup-file> <new-output-file>");
        }
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("An interactive terminal is required for the master password");
        }
        char[] password = console.readPassword("Backup master password: ");
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Master password is required");
        }
        Path temporary = null;
        try {
            try (InputStream source = Files.newInputStream(Path.of(args[0]))) {
                temporary = EncryptedBackupCodec.decrypt(source, null, password);
            }
            try (InputStream source = Files.newInputStream(temporary);
                 var destination = Files.newOutputStream(Path.of(args[1]),
                         java.nio.file.StandardOpenOption.CREATE_NEW)) {
                source.transferTo(destination);
            }
        } finally {
            Arrays.fill(password, '\0');
            if (temporary != null) {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
