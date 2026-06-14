package com.biliwind.blog.service.link;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class LinkProbeService {

    public LinkProbeResult probe(String url, String siteUrl) {
        long startedAt = System.currentTimeMillis();
        if (!isPublicHttpUrl(url)) {
            return new LinkProbeResult(false, 0, 0, false, "拒绝访问非公开网络地址");
        }
        try (Client client = createHttpClient()) {
            try (Response response = client.target(url)
                    .request()
                    .header("User-Agent", "WindBlog-Link-Monitor/1.0")
                    .get()) {
                int statusCode = response.getStatus();
                int loadTimeMs = calculateLoadTime(startedAt);
                boolean reachable = statusCode >= 200 && statusCode < 400;
                boolean backlinkFound = false;
                if (reachable && response.hasEntity()) {
                    String html = response.readEntity(String.class);
                    backlinkFound = containsBacklink(html, url, siteUrl);
                }
                return new LinkProbeResult(reachable, statusCode, loadTimeMs, backlinkFound, "");
            }
        } catch (Exception exception) {
            String errorMessage = exception.getMessage();
            if (errorMessage == null || errorMessage.isBlank()) {
                errorMessage = exception.getClass().getSimpleName();
            }
            return new LinkProbeResult(false, 0, calculateLoadTime(startedAt), false, errorMessage);
        }
    }

    public boolean isPublicHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                return false;
            }
            for (InetAddress address : addresses) {
                if (!isPublicAddress(address)) {
                    return false;
                }
            }
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private Client createHttpClient() {
        return ClientBuilder.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
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
            if (style.contains("display:none")) {
                return false;
            }
            if (style.contains("visibility:hidden")) {
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

    private boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress()) {
            return false;
        }
        if (address.isLoopbackAddress()) {
            return false;
        }
        if (address.isLinkLocalAddress()) {
            return false;
        }
        if (address.isSiteLocalAddress()) {
            return false;
        }
        if (address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet6Address) {
            byte[] addressBytes = address.getAddress();
            int firstByte = addressBytes[0] & 0xff;
            if ((firstByte & 0xfe) == 0xfc) {
                return false;
            }
        }
        return true;
    }
}
