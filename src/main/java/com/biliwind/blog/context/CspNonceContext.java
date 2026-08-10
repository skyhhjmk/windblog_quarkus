package com.biliwind.blog.context;

import jakarta.enterprise.context.RequestScoped;

import java.security.SecureRandom;
import java.util.Base64;

@RequestScoped
public class CspNonceContext {

    private volatile SecureRandom random;
    private volatile String nonce;

    public String getNonce() {
        String cached = nonce;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = nonce;
            if (cached == null) {
                cached = createNonce();
                nonce = cached;
            }
            return cached;
        }
    }

    private String createNonce() {
        byte[] bytes = new byte[18];
        getRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private SecureRandom getRandom() {
        SecureRandom cached = random;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = random;
            if (cached == null) {
                cached = new SecureRandom();
                random = cached;
            }
            return cached;
        }
    }
}
