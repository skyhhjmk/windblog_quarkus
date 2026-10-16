package com.biliwind.blog.service;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeoLinkPolicyTest {
    private String rel(String href, String initial) {
        var anchor = Jsoup.parseBodyFragment("<a>link</a>").selectFirst("a");
        anchor.attr("href", href).attr("rel", initial);
        SeoLinkPolicy.apply(anchor, "https://blog.example.com");
        return anchor.attr("rel");
    }

    @Test void internalNavigationRemainsFollowable() {
        for (String url : new String[]{"/post/next", "../next", "#section", "https://blog.example.com/post/next", "//blog.example.com/post/next"}) {
            assertFalse(rel(url, "").contains("nofollow"), url);
            assertTrue(rel(url, "").contains("noopener"), url);
        }
    }

    @Test void externalAndLookalikeHostsRemainRestricted() {
        for (String url : new String[]{"https://other.example/post", "//other.example/post", "https://blog.example.com.evil.test/post"}) {
            assertTrue(rel(url, "").contains("nofollow"), url);
        }
    }

    @Test void explicitAuthorRestrictionsSurvive() {
        String value = rel("/go/token", "sponsored ugc nofollow");
        assertTrue(value.contains("sponsored"));
        assertTrue(value.contains("ugc"));
        assertTrue(value.contains("nofollow"));
    }
}
