package com.biliwind.blog.service.repost;

import com.biliwind.blog.model.*;
import com.biliwind.blog.service.edge.DataSyncEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * 生成、查找和撤销 go 短链 token。
 */
@ApplicationScoped
public class AffiliateTokenService {

    private static final int RAW_TOKEN_BYTES = 24;
    private final SecureRandom secureRandom = new SecureRandom();

    @Inject
    Event<DataSyncEvent> dataSyncEvent;

    @Transactional
    public TokenCreationResult createRepostToken(Post post, User user, RepostLicense repostLicense, String allowedDomain) {
        return createToken(post, null, user, repostLicense, allowedDomain, "REPOST_LICENSE");
    }

    @Transactional
    public TokenCreationResult createFirstPartyToken(Post post, AffiliateLink affiliateLink) {
        return createToken(post, affiliateLink, null, null, null, "FIRST_PARTY");
    }

    public AffiliateToken findByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }

        String tokenHash = hashToken(rawToken);
        return AffiliateToken.find("tokenHash = ?1", tokenHash).firstResult();
    }

    @Transactional
    public void revokeToken(Long tokenId) {
        AffiliateToken token = AffiliateToken.findById(tokenId);
        if (token == null) {
            return;
        }

        token.status = 2;
        token.revokedAt = OffsetDateTime.now();
        dataSyncEvent.fire(new DataSyncEvent("AFFILIATE_TOKEN", token.id, "UPSERT"));
    }

    public String hashToken(String rawToken) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] digestBytes = messageDigest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte digestByte : digestBytes) {
                builder.append(String.format("%02x", digestByte));
            }
            return builder.toString();
        } catch (Exception exception) {
            return "";
        }
    }

    protected TokenCreationResult createToken(Post post,
                                              AffiliateLink affiliateLink,
                                              User user,
                                              RepostLicense repostLicense,
                                              String allowedDomain,
                                              String tokenType) {
        String rawToken = generateUniqueRawToken();
        AffiliateToken token = new AffiliateToken();
        token.tokenHash = hashToken(rawToken);
        token.shortDisplay = rawToken.substring(0, 10);
        token.tokenType = tokenType;
        token.status = 1;
        token.article = post;
        token.affiliateLink = affiliateLink;
        token.viewerUser = user;
        token.repostLicense = repostLicense;
        token.allowedDomain = allowedDomain;
        token.persist();
        dataSyncEvent.fire(new DataSyncEvent("AFFILIATE_TOKEN", token.id, "UPSERT"));
        return new TokenCreationResult(token, rawToken);
    }

    private String generateUniqueRawToken() {
        String rawToken = generateRawToken();
        String tokenHash = hashToken(rawToken);
        while (AffiliateToken.count("tokenHash = ?1", tokenHash) > 0) {
            rawToken = generateRawToken();
            tokenHash = hashToken(rawToken);
        }
        return rawToken;
    }

    private String generateRawToken() {
        byte[] randomBytes = new byte[RAW_TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    public static class TokenCreationResult {
        public final AffiliateToken token;
        public final String rawToken;

        public TokenCreationResult(AffiliateToken token, String rawToken) {
            this.token = token;
            this.rawToken = rawToken;
        }
    }
}
