package com.biliwind.blog.context;

import jakarta.enterprise.context.RequestScoped;

import java.security.SecureRandom;
import java.util.Base64;

@RequestScoped
public class CspNonceContext {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final String nonce = createNonce();

    public String getNonce() {
        return nonce;
    }

    private static String createNonce() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
