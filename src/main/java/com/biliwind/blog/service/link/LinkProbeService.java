package com.biliwind.blog.service.link;

import com.biliwind.blog.service.security.SafeExternalHttpService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;

@ApplicationScoped
public class LinkProbeService {

    @Inject
    SafeExternalHttpService safeExternalHttpService;

    public LinkProbeResult probe(String url, String siteUrl) {
        long startedAt = System.currentTimeMillis();
        try {
            SafeExternalHttpService.ExternalHttpResponse response = safeExternalHttpService.get(
                    url,
                    "WindBlog-Link-Monitor/1.0"
            );
            int statusCode = response.statusCode();
            int loadTimeMs = calculateLoadTime(startedAt);
            boolean reachable = statusCode >= 200 && statusCode < 400;
            boolean backlinkFound = false;
            if (reachable) {
                backlinkFound = containsBacklink(response.bodyAsText(), url, siteUrl);
            }
            return new LinkProbeResult(reachable, statusCode, loadTimeMs, backlinkFound, "");
        } catch (Exception exception) {
            return new LinkProbeResult(false, 0, calculateLoadTime(startedAt), false, "外部地址请求失败");
        }
    }

    public boolean isPublicHttpUrl(String url) {
        try {
            safeExternalHttpService.validatePublicHttpUri(url);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private int calculateLoadTime(long startedAt) {
        long elapsed = System.currentTimeMillis() - startedAt;
        if (elapsed > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) elapsed;
    }

    private boolean containsBacklink(String html, String checkedUrl, String siteUrl) {
        String expectedHost = readHost(siteUrl);
        if (expectedHost.isBlank()) {
            return false;
        }
        Document document = Jsoup.parse(html, checkedUrl);
        for (Element anchor : document.select("a[href]")) {
            if (!isVisible(anchor)) {
                continue;
            }
            String linkHost = readHost(anchor.attr("abs:href"));
            if (expectedHost.equalsIgnoreCase(linkHost)) {
                return true;
            }
        }
        return false;
    }

    private boolean isVisible(Element element) {
        Element currentElement = element;
        while (currentElement != null) {
            if (currentElement.hasAttr("hidden")) {
                return false;
            }
            if ("true".equalsIgnoreCase(currentElement.attr("aria-hidden"))) {
                return false;
            }
            String style = currentElement.attr("style").replace(" ", "").toLowerCase();
            if (style.contains("display:none") || style.contains("visibility:hidden")) {
                return false;
            }
            currentElement = currentElement.parent();
        }
        return true;
    }

    private String readHost(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null) {
                return "";
            }
            String normalizedHost = host.toLowerCase();
            if (normalizedHost.startsWith("www.")) {
                return normalizedHost.substring(4);
            }
            return normalizedHost;
        } catch (Exception exception) {
            return "";
        }
    }
}