package com.biliwind.blog.service;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import org.jsoup.nodes.Element;

public final class SeoLinkPolicy {
    private SeoLinkPolicy() {}

    public static void apply(Element anchor, String baseUrl) {
        String href = anchor.attr("href");
        boolean internal = false;
        try {
            URI uri = URI.create(href);
            internal = !uri.isAbsolute() && uri.getRawAuthority() == null;
            if (!internal && baseUrl != null) {
                URI base = URI.create(baseUrl);
                internal = base.getHost() != null && base.getHost().equalsIgnoreCase(uri.getHost())
                        && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())
                            || uri.getScheme() == null);
            }
        } catch (IllegalArgumentException ignored) { }
        var rel = new LinkedHashSet<String>();
        for (String token : anchor.attr("rel").split("\\s+")) {
            if (List.of("ugc", "sponsored", "nofollow").contains(token)) rel.add(token);
        }
        // Preserve author supplied paid, UGC and nofollow restrictions.
        if (!internal) rel.add("nofollow");
        rel.add("noopener");
        rel.add("noreferrer");
        anchor.attr("rel", String.join(" ", rel));
    }
}
