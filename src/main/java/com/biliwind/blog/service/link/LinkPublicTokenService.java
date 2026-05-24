package com.biliwind.blog.service.link;

import com.biliwind.blog.model.Link;
import com.biliwind.blog.service.edge.DataSyncEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 为链接生成不可遍历的前台跳转 token。
 */
@ApplicationScoped
public class LinkPublicTokenService {

    private static final int TOKEN_BYTES_LENGTH = 18;
    private final SecureRandom secureRandom = new SecureRandom();

    @Inject
    Event<DataSyncEvent> dataSyncEvent;

    @Transactional
    public String ensurePublicToken(Link link) {
        if (link == null) {
            return null;
        }
        if (link.publicToken != null && link.publicToken.isBlank() == false) {
            return link.publicToken;
        }

        Link tokenTarget = link;
        if (link.id != null) {
            Link managedLink = Link.findById(link.id);
            if (managedLink != null) {
                tokenTarget = managedLink;
            }
        }

        tokenTarget.publicToken = generateUniqueToken();
        link.publicToken = tokenTarget.publicToken;
        if (link.id != null) {
            dataSyncEvent.fire(new DataSyncEvent("LINK", link.id, "UPSERT"));
        }
        return link.publicToken;
    }

    public Link findByPublicToken(String publicToken) {
        if (publicToken == null || publicToken.isBlank()) {
            return null;
        }
        return Link.find("publicToken = ?1", publicToken).firstResult();
    }

    private String generateUniqueToken() {
        String publicToken = generateToken();
        while (Link.count("publicToken = ?1", publicToken) > 0) {
            publicToken = generateToken();
        }
        return publicToken;
    }

    private String generateToken() {
        byte[] tokenBytes = new byte[TOKEN_BYTES_LENGTH];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }
}
