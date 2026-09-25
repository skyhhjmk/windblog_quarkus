package com.biliwind.blog.service.storage;

import jakarta.enterprise.context.ApplicationScoped;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Self-contained, password-decryptable encrypted media backup format. */
@ApplicationScoped
public class EncryptedBackupCodec {
    private static final int MAGIC = 0x57424231; // WBB1
    private static final int MAX_HEADER = 4096;
    private static final int MEMORY_KIB = 65536;
    private static final int PASSES = 3;
    private static final int LANES = 4;

    @ConfigProperty(name = "windblog.storage.backup.master-password")
    Optional<String> configuredPassword;

    @ConfigProperty(name = "windblog.storage.backup.master-password-file")
    Optional<String> configuredPasswordFile;

    public Path encrypt(InputStream source, String objectUuid) throws IOException {
        char[] secret = password();
        try {
            return encrypt(source, objectUuid, secret);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    public Path decrypt(InputStream source, String expectedUuid) throws IOException {
        char[] secret = password();
        try {
            return decrypt(source, expectedUuid, secret);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    public static Path encrypt(InputStream source, String objectUuid, char[] password) throws IOException {
        requireUuid(objectUuid);
        Path plain = Files.createTempFile("windblog-backup-source-", ".tmp");
        Path encrypted = Files.createTempFile("windblog-backup-encrypted-", ".tmp");
        try {
            MessageDigest digest = sha256();
            long length = 0;
            try (InputStream plaintext = source;
                 OutputStream output = Files.newOutputStream(plain)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = plaintext.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    length += count;
                }
            }
            SecureRandom random = new SecureRandom();
            byte[] salt = randomBytes(random, 16);
            byte[] iv = randomBytes(random, 12);
            byte[] header = header(objectUuid, salt, iv, digest.digest(), length);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, iv, header);
            try (OutputStream file = Files.newOutputStream(encrypted)) {
                new DataOutputStream(file).writeInt(header.length);
                file.write(header);
                try (CipherOutputStream ciphertext = new CipherOutputStream(file, cipher);
                     GZIPOutputStream zipped = new GZIPOutputStream(ciphertext);
                     InputStream plaintext = Files.newInputStream(plain)) {
                    plaintext.transferTo(zipped);
                }
            }
            return encrypted;
        } catch (Exception exception) {
            Files.deleteIfExists(encrypted);
            throw new IOException("Unable to encrypt backup", exception);
        } finally {
            Files.deleteIfExists(plain);
        }
    }

    /** Decrypt to a private temporary file and return it only after GCM and SHA-256 verification. */
    public static Path decrypt(InputStream source, String expectedUuid, char[] password) throws IOException {
        if (expectedUuid != null) {
            requireUuid(expectedUuid);
        }
        Path plain = Files.createTempFile("windblog-backup-restored-", ".tmp");
        try {
            DataInputStream input = new DataInputStream(source);
            int headerLength = input.readInt();
            if (headerLength < 70 || headerLength > MAX_HEADER) {
                throw new IOException("Invalid backup header length");
            }
            byte[] header = input.readNBytes(headerLength);
            if (header.length != headerLength) {
                throw new IOException("Incomplete backup header");
            }
            DataInputStream fields = new DataInputStream(new java.io.ByteArrayInputStream(header));
            if (fields.readInt() != MAGIC || fields.readInt() != 1) {
                throw new IOException("Unsupported backup version");
            }
            String objectUuid = fields.readUTF();
            requireUuid(objectUuid);
            if (expectedUuid != null && !expectedUuid.equals(objectUuid)) {
                throw new IOException("Backup object identity mismatch");
            }
            byte[] salt = fields.readNBytes(16);
            byte[] iv = fields.readNBytes(12);
            byte[] expectedHash = fields.readNBytes(32);
            long expectedLength = fields.readLong();
            if (salt.length != 16 || iv.length != 12 || expectedHash.length != 32
                    || expectedLength < 0 || fields.available() != 0) {
                throw new IOException("Invalid backup header");
            }
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, password, salt, iv, header);
            MessageDigest digest = sha256();
            long length = 0;
            try (CipherInputStream ciphertext = new CipherInputStream(input, cipher);
                 GZIPInputStream zipped = new GZIPInputStream(ciphertext);
                 OutputStream output = Files.newOutputStream(plain)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = zipped.read(buffer)) != -1) {
                    length += count;
                    if (length > expectedLength) {
                        throw new IOException("Backup expands beyond declared size");
                    }
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                }
                // Read through the cipher tag even when GZIP reached its trailer.
                while (ciphertext.read() != -1) {
                    throw new IOException("Unexpected bytes after compressed backup");
                }
            }
            if (length != expectedLength || !MessageDigest.isEqual(digest.digest(), expectedHash)) {
                throw new IOException("Backup checksum mismatch");
            }
            return plain;
        } catch (Exception exception) {
            Files.deleteIfExists(plain);
            throw new IOException("Unable to decrypt or verify backup", exception);
        }
    }

    public static String sha256Hex(Path path) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private char[] password() throws IOException {
        String value = configuredPassword.orElse("");
        String passwordFile = configuredPasswordFile.orElse("");
        if (!passwordFile.isBlank()) {
            value = Files.readString(Path.of(passwordFile)).stripTrailing();
        }
        if (value == null || value.length() < 16) {
            throw new IOException("Backup master password is missing or too short");
        }
        return value.toCharArray();
    }

    private static byte[] header(String uuid, byte[] salt, byte[] iv, byte[] hash, long length) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(MAGIC);
        output.writeInt(1);
        output.writeUTF(uuid);
        output.write(salt);
        output.write(iv);
        output.write(hash);
        output.writeLong(length);
        output.flush();
        return bytes.toByteArray();
    }

    private static Cipher cipher(int mode, char[] password, byte[] salt, byte[] iv, byte[] aad)
            throws Exception {
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt).withMemoryAsKB(MEMORY_KIB).withIterations(PASSES)
                .withParallelism(LANES).build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] key = new byte[32];
        try {
            generator.generateBytes(password, key);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad);
            return cipher;
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static byte[] randomBytes(SecureRandom random, int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return bytes;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void requireUuid(String value) throws IOException {
        try {
            java.util.UUID.fromString(value);
            if (!java.util.UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException();
            }
        } catch (Exception exception) {
            throw new IOException("Invalid backup object UUID", exception);
        }
    }
}
