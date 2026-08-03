package com.biliwind.blog.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;

@ApplicationScoped
public class PublicContentSanitizer {

    private static final Safelist PUBLIC_HTML = Safelist.relaxed()
            .addTags("hr", "pre", "code", "table", "thead", "tbody", "tr", "th", "td", "span", "div")
            .addAttributes("code", "class")
            .addAttributes("pre", "class")
            .addAttributes("span", "class")
            .addAttributes("div", "class", "data-name", "data-group", "data-title", "data-block-id")
            .addAttributes("a", "target", "rel", "class", "title")
            .addAttributes("img", "alt", "width", "height", "loading", "decoding", "class")
            .addAttributes("table", "class")
            .addAttributes("th", "align")
            .addAttributes("td", "align")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "http", "https");

    public String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document.OutputSettings outputSettings = new Document.OutputSettings().prettyPrint(false);
        String sanitized = Jsoup.clean(html, "http://localhost", PUBLIC_HTML, outputSettings);
        Document document = Jsoup.parseBodyFragment(sanitized);
        PublicMediaUrlPolicy.removeDisallowedImages(document);
        for (Element anchor : document.select("a")) {
            anchor.attr("rel", "nofollow noopener noreferrer");
        }
        return document.body().html();
    }
}
