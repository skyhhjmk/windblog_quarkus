package com.biliwind.blog.common.security;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@ApplicationScoped
public class PasswordHasher {

    private static final String PREFIX = "{pbkdf2-sha256}";
    private static final int SALT_BYTES = 16;

    @ConfigProperty(name = "admin.password-hash.iterations", defaultValue = "210000")
    int iterations;

    @ConfigProperty(name = "admin.password-hash.key-length-bits", defaultValue = "256")
    int keyLengthBits;

    public String hash(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        secureRandom().nextBytes(salt);
        byte[] derived = pbkdf2(rawPassword, salt, iterations, keyLengthBits);
        return PREFIX
                + "$" + iterations
                + "$" + Base64.getUrlEncoder().withoutPadding().encodeToString(salt)
                + "$" + Base64.getUrlEncoder().withoutPadding().encodeToString(derived);
    }

    public boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null || encodedPassword.isBlank()) {
            return false;
        }
        if (!isHashedFormat(encodedPassword)) {
            return MessageDigest.isEqual(
                    rawPassword.getBytes(StandardCharsets.UTF_8),
                    encodedPassword.getBytes(StandardCharsets.UTF_8)
            );
        }

        String[] parts = encodedPassword.split("\\$");
        if (parts.length != 4) {
            return false;
        }

        int configuredIterations;
        byte[] salt;
        byte[] expected;
        try {
            configuredIterations = Integer.parseInt(parts[1]);
            salt = Base64.getUrlDecoder().decode(parts[2]);
            expected = Base64.getUrlDecoder().decode(parts[3]);
        } catch (Exception e) {
            return false;
        }

        byte[] actual = pbkdf2(rawPassword, salt, configuredIterations, expected.length * 8);
        return MessageDigest.isEqual(actual, expected);
    }

    public boolean isHashedFormat(String encodedPassword) {
        if (encodedPassword == null || !encodedPassword.startsWith(PREFIX + "$")) {
            return false;
        }
        String[] parts = encodedPassword.split("\\$");
        return parts.length == 4;
    }

    private byte[] pbkdf2(String password, byte[] salt, int rounds, int bits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, rounds, bits);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return skf.generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Password hash failed", e);
        }
    }

    private SecureRandom secureRandom() {
        try {
            return SecureRandom.getInstanceStrong();
        } catch (GeneralSecurityException e) {
            return new SecureRandom();
        }
    }
}
