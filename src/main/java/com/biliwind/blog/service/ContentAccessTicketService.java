package com.biliwind.blog.service;

import com.biliwind.blog.model.ContentAccessTicket;
import com.biliwind.blog.model.ContentAccessTicketKeyState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;

@ApplicationScoped
public class ContentAccessTicketService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    @Transactional
    public IssuedTicket issuePasswordTicket(Long postId, Duration lifetime) {
        return issuePasswordTicket(postId, lifetime, null);
    }

    @Transactional
    public IssuedTicket issuePasswordTicket(Long postId, Duration lifetime, String deviceId) {
        return issue("POST", null, postId, "POST_PASSWORD", lifetime, deviceId);
    }

    @Transactional
    public IssuedTicket issueMediaDownloadTicket(Long mediaId, Long postId, Long userId, Duration lifetime) {
        return issueMediaDownloadTicket(mediaId, postId, userId, lifetime, null);
    }

    @Transactional
    public IssuedTicket issueMediaDownloadTicket(Long mediaId, Long postId, Long userId,
                                                 Duration lifetime, String deviceId) {
        return issue("USER", userId, postId, "MEDIA_DOWNLOAD:" + mediaId, lifetime, deviceId);
    }

    @Transactional
    public String issueMediaDownloadPath(Long mediaId, Long postId, Long userId, Duration lifetime) {
        return issueMediaDownloadPath(mediaId, postId, userId, lifetime, null);
    }

    @Transactional
    public String issueMediaDownloadPath(Long mediaId, Long postId, Long userId,
                                         Duration lifetime, String deviceId) {
        IssuedTicket ticket = issueMediaDownloadTicket(mediaId, postId, userId, lifetime, deviceId);
        return ticket.token();
    }

    @Transactional
    public boolean isValid(String rawToken, String scope, Long postId, Long subjectId) {
        return isValid(rawToken, scope, postId, subjectId, null);
    }

    @Transactional
    public boolean isValid(String rawToken, String scope, Long postId, Long subjectId, String deviceId) {
        ContentAccessTicket ticket = findValid(rawToken, scope, postId, subjectId, deviceId);
        if (ticket == null) {
            return false;
        }
        ticket.lastUsedAt = OffsetDateTime.now();
        return true;
    }

    @Transactional
    public ContentAccessTicket requireMediaTicket(String rawToken, Long userId) {
        return requireMediaTicket(rawToken, userId, null);
    }

    @Transactional
    public ContentAccessTicket requireMediaTicket(String rawToken, Long userId, String deviceId) {
        if (rawToken == null || rawToken.isBlank() || userId == null) {
            return null;
        }
        String deviceHash = hashNullable(deviceId);
        String query = "tokenHash = ?1 and subjectType = ?2 and subjectId = ?3 "
                + "and scope like ?4 and revokedAt is null and expiresAt > ?5 and keyVersion = ?6";
        java.util.List<Object> parameters = new java.util.ArrayList<>();
        parameters.add(hash(rawToken));
        parameters.add("USER");
        parameters.add(userId);
        parameters.add("MEDIA_DOWNLOAD:%");
        parameters.add(OffsetDateTime.now());
        parameters.add(currentKeyVersion());
        if (deviceHash == null) {
            query = query + " and deviceHash is null";
        } else {
            query = query + " and (deviceHash is null or deviceHash = ?7)";
            parameters.add(deviceHash);
        }
        ContentAccessTicket ticket = ContentAccessTicket.find(
                query, parameters.toArray()).firstResult();
        if (ticket == null || ticket.postId == null || !ticket.scope.startsWith("MEDIA_DOWNLOAD:")) {
            return null;
        }
        ticket.lastUsedAt = OffsetDateTime.now();
        return ticket;
    }

    @Transactional
    public long revokePasswordTickets(Long postId) {
        return ContentAccessTicket.update(
                "revokedAt = ?1 where scope = ?2 and postId = ?3 and revokedAt is null",
                OffsetDateTime.now(), "POST_PASSWORD", postId);
    }

    @Transactional
    public long revokeMediaDownloadTickets(Long mediaId, Long postId, Long userId) {
        return revokeMediaDownloadTickets(mediaId, postId, userId, null);
    }

    @Transactional
    public long revokeMediaDownloadTickets(Long mediaId, Long postId, Long userId, String deviceId) {
        String deviceHash = hashNullable(deviceId);
        if (mediaId == null && postId == null && userId == null && deviceHash == null) {
            return 0L;
        }
        StringBuilder query = new StringBuilder("revokedAt = ?1 where scope = ?2 and revokedAt is null");
        java.util.List<Object> parameters = new java.util.ArrayList<>();
        parameters.add(OffsetDateTime.now());
        if (mediaId == null) {
            query = new StringBuilder("revokedAt = ?1 where scope like ?2 and revokedAt is null");
            parameters.add("MEDIA_DOWNLOAD:%");
        } else {
            parameters.add("MEDIA_DOWNLOAD:" + mediaId);
        }
        int nextParameter = 3;
        if (postId != null) {
            query.append(" and postId = ?").append(nextParameter);
            parameters.add(postId);
            nextParameter = nextParameter + 1;
        }
        if (userId != null) {
            query.append(" and subjectType = ?").append(nextParameter)
                    .append(" and subjectId = ?").append(nextParameter + 1);
            parameters.add("USER");
            parameters.add(userId);
        }
        if (deviceHash != null) {
            int parameterNumber = parameters.size() + 1;
            query.append(" and deviceHash = ?").append(parameterNumber);
            parameters.add(deviceHash);
        }
        return ContentAccessTicket.update(query.toString(), parameters.toArray());
    }

    @Transactional
    public int rotateKeyVersion() {
        ContentAccessTicketKeyState state = ContentAccessTicketKeyState.findById(1);
        if (state == null) {
            state = new ContentAccessTicketKeyState();
            state.id = 1;
            state.currentVersion = 1;
        }
        state.currentVersion = state.currentVersion + 1;
        state.updatedAt = OffsetDateTime.now();
        state.persist();
        return state.currentVersion;
    }

    private IssuedTicket issue(String subjectType, Long subjectId, Long postId, String scope,
                               Duration lifetime, String deviceId) {
        if (lifetime == null || lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("票据有效期必须为正数");
        }
        String rawToken = randomToken();
        ContentAccessTicket ticket = new ContentAccessTicket();
        ticket.tokenHash = hash(rawToken);
        ticket.subjectType = subjectType;
        ticket.subjectId = subjectId;
        ticket.postId = postId;
        ticket.scope = scope;
        ticket.keyVersion = currentKeyVersion();
        ticket.deviceHash = hashNullable(deviceId);
        ticket.expiresAt = OffsetDateTime.now().plus(lifetime);
        ticket.persist();
        return new IssuedTicket(rawToken, ticket.expiresAt);
    }

    private ContentAccessTicket findValid(String rawToken, String scope, Long postId,
                                          Long subjectId, String deviceId) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        ContentAccessTicket ticket = ContentAccessTicket.find(
                "tokenHash = ?1 and scope = ?2 and revokedAt is null and expiresAt > ?3 and keyVersion = ?4",
                hash(rawToken), scope, OffsetDateTime.now(), currentKeyVersion()).firstResult();
        if (ticket == null || (postId != null && !equalsNullable(ticket.postId, postId))) {
            return null;
        }
        if (subjectId != null && !equalsNullable(ticket.subjectId, subjectId)) {
            return null;
        }
        if (ticket.deviceHash != null) {
            String requestedDeviceHash = hashNullable(deviceId);
            if (requestedDeviceHash == null || !ticket.deviceHash.equals(requestedDeviceHash)) {
                return null;
            }
        }
        return ticket;
    }

    private boolean equalsNullable(Long first, Long second) {
        return first == null ? second == null : first.equals(second);
    }

    private int currentKeyVersion() {
        ContentAccessTicketKeyState state = ContentAccessTicketKeyState.findById(1);
        return state == null || state.currentVersion == null ? 1 : state.currentVersion;
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                result.append(String.format("%02x", current));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成访问票据摘要", exception);
        }
    }

    private String hashNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return hash(value.trim());
    }

    public record IssuedTicket(String token, OffsetDateTime expiresAt) {
    }
}
