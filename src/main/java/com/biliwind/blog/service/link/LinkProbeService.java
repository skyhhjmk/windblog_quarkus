package com.biliwind.blog.service.link;

import com.biliwind.blog.service.security.SafeExternalHttpService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.Parser;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class LinkProbeService {

    private static final int MAX_KEYWORDS = 20;
    private static final int MAX_KEYWORD_LENGTH = 100;

    @Inject
    SafeExternalHttpService safeExternalHttpService;

    public LinkProbeResult probe(String url, String siteUrl) {
        return probe(url, siteUrl, "WindBlog", "", "");
    }

    public LinkProbeResult probe(String url, String siteUrl, String siteName, String configuredKeywords,
                                 String targetName) {
        long startedAt = System.currentTimeMillis();
        List<String> expectedKeywords = parseKeywords(siteName, configuredKeywords);
        try {
            SafeExternalHttpService.ExternalHttpResponse response = safeExternalHttpService.get(
                    url,
                    "WindBlog-Link-Monitor/1.0"
            );
            int statusCode = response.statusCode();
            int loadTimeMs = calculateLoadTime(startedAt);
            boolean reachable = statusCode >= 200 && statusCode < 400;
            if (!reachable) {
                return result(false, statusCode, loadTimeMs, false, "", url, targetName, siteUrl, siteName,
                        expectedKeywords, List.of(), List.of(), List.of(), false, List.of(), 0, false);
            }

            DetectionResult detection = inspectBacklinks(
                    response.bodyAsText(), url, siteUrl, expectedKeywords);
            return result(true, statusCode, loadTimeMs, detection.backlinkFound(), "", url, targetName, siteUrl,
                    siteName, expectedKeywords, detection.matchedKeywords(), detection.matchedUrls(),
                    detection.matchedAnchorTexts(), detection.fraudDetected(), detection.fraudReasons(),
                    detection.domParseErrorCount(), true);
        } catch (Exception exception) {
            return result(false, 0, calculateLoadTime(startedAt), false, "外部地址请求失败", url, targetName,
                    siteUrl, siteName, expectedKeywords, List.of(), List.of(), List.of(), false, List.of(), 0, false);
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

    private LinkProbeResult result(boolean reachable, int statusCode, int loadTimeMs, boolean backlinkFound,
                                   String errorMessage, String checkedUrl, String targetName, String siteUrl,
                                   String siteName, List<String> expectedKeywords, List<String> matchedKeywords,
                                   List<String> matchedUrls, List<String> matchedAnchorTexts,
                                   boolean fraudDetected, List<String> fraudReasons, int domParseErrorCount,
                                   boolean detectorSupported) {
        LinkProbeEvidence evidence = new LinkProbeEvidence(
                checkedUrl, targetName, siteUrl, siteName, expectedKeywords, matchedKeywords, matchedUrls,
                matchedAnchorTexts, fraudDetected, fraudReasons, domParseErrorCount, detectorSupported);
        return new LinkProbeResult(reachable, statusCode, loadTimeMs, backlinkFound, errorMessage, evidence);
    }

    private int calculateLoadTime(long startedAt) {
        long elapsed = System.currentTimeMillis() - startedAt;
        return elapsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsed;
    }

    private DetectionResult inspectBacklinks(String html, String checkedUrl, String siteUrl,
                                              List<String> expectedKeywords) {
        String expectedHost = readHost(siteUrl);
        if (expectedHost.isBlank()) {
            return new DetectionResult(false, List.of(), List.of(), List.of(), false,
                    List.of("本站检测地址无效"), 0);
        }

        Parser parser = Parser.htmlParser().setTrackErrors(100);
        Document document = Jsoup.parse(html == null ? "" : html, checkedUrl, parser);
        List<String> matchedKeywords = new ArrayList<>();
        List<String> matchedUrls = new ArrayList<>();
        List<String> matchedAnchorTexts = new ArrayList<>();
        List<String> fraudReasons = new ArrayList<>();

        for (Element anchor : document.select("a[href]")) {
            String absoluteUrl = anchor.attr("abs:href").trim();
            String anchorHost = readHost(absoluteUrl);
            String anchorText = readAnchorText(anchor);
            if (anchorHost.equalsIgnoreCase(expectedHost) && isVisible(anchor)) {
                List<String> anchorMatches = matchingKeywords(anchorText, expectedKeywords);
                if (!anchorMatches.isEmpty()) {
                    addBounded(matchedUrls, absoluteUrl);
                    addBounded(matchedAnchorTexts, anchorText);
                    for (String keyword : anchorMatches) {
                        addBounded(matchedKeywords, keyword);
                    }
                }
            }
        }
        boolean backlinkFound = !matchedKeywords.isEmpty() && !matchedAnchorTexts.isEmpty();

        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof Comment comment) {
                    String commentText = comment.getData();
                    if (!matchingKeywords(commentText, expectedKeywords).isEmpty()
                            && containsHost(commentText, expectedHost)
                            && commentText.toLowerCase(Locale.ROOT).contains("href")) {
                        addBounded(fraudReasons, "HTML 注释中包含指向本站的检测关键词");
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                // No-op.
            }
        }, document);

        for (Element element : document.getAllElements()) {
            if (!isHidden(element)) {
                continue;
            }
            String hiddenContent = hiddenContent(element);
            if (matchingKeywords(hiddenContent, expectedKeywords).isEmpty()) {
                continue;
            }
            boolean hiddenSiteAnchor = "a".equals(element.normalName())
                    && element.hasAttr("href")
                    && readHost(element.attr("abs:href")).equalsIgnoreCase(expectedHost)
                    && !matchingKeywords(readAnchorText(element), expectedKeywords).isEmpty();
            for (Element anchor : element.select("a[href]")) {
                if (readHost(anchor.attr("abs:href")).equalsIgnoreCase(expectedHost)
                        && !matchingKeywords(readAnchorText(anchor), expectedKeywords).isEmpty()) {
                    hiddenSiteAnchor = true;
                    break;
                }
            }
            if (hiddenSiteAnchor) {
                addBounded(fraudReasons, "指向本站且包含检测关键词的链接位于隐藏元素中");
            } else {
                addBounded(fraudReasons, "隐藏元素中包含检测关键词");
            }
        }

        int parseErrorCount = parser.getErrors().size();
        String source = html == null ? "" : html;
        if (parseErrorCount > 0 && !backlinkFound && hasMalformedCandidateAnchor(source, expectedHost,
                expectedKeywords)) {
            addBounded(fraudReasons, "DOM 结构解析异常，源码同时含本站地址和检测关键词");
        }

        return new DetectionResult(backlinkFound, matchedKeywords, matchedUrls, matchedAnchorTexts,
                !fraudReasons.isEmpty(), fraudReasons, parseErrorCount);
    }

    private List<String> parseKeywords(String siteName, String configuredKeywords) {
        Set<String> keywords = new LinkedHashSet<>();
        addKeyword(keywords, siteName);
        if (configuredKeywords != null) {
            for (String part : configuredKeywords.split("[,，;；\\r\\n]+")) {
                addKeyword(keywords, part);
                if (keywords.size() >= MAX_KEYWORDS) {
                    break;
                }
            }
        }
        return List.copyOf(keywords);
    }

    private void addKeyword(Set<String> keywords, String value) {
        if (value == null || value.isBlank() || keywords.size() >= MAX_KEYWORDS) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_KEYWORD_LENGTH) {
            trimmed = trimmed.substring(0, MAX_KEYWORD_LENGTH);
        }
        if (!trimmed.isBlank()) {
            keywords.add(trimmed);
        }
    }

    private List<String> matchingKeywords(String text, List<String> expectedKeywords) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalizedText = text.toLowerCase(Locale.ROOT);
        return expectedKeywords.stream()
                .filter(keyword -> normalizedText.contains(keyword.toLowerCase(Locale.ROOT)))
                .toList();
    }

    private String readAnchorText(Element anchor) {
        StringBuilder text = new StringBuilder(anchor.text());
        appendAttribute(text, anchor, "aria-label");
        appendAttribute(text, anchor, "title");
        for (Element image : anchor.select("img[alt], img[title]")) {
            appendAttribute(text, image, "alt");
            appendAttribute(text, image, "title");
        }
        return text.toString().trim();
    }

    private void appendAttribute(StringBuilder text, Element element, String attribute) {
        if (element.hasAttr(attribute)) {
            text.append(' ').append(element.attr(attribute));
        }
    }

    private String hiddenContent(Element element) {
        StringBuilder content = new StringBuilder(element.text());
        for (String attribute : List.of("title", "alt", "aria-label", "href")) {
            appendAttribute(content, element, attribute);
        }
        return content.toString();
    }

    private boolean isVisible(Element element) {
        return !isHidden(element);
    }

    private boolean isHidden(Element element) {
        Element current = element;
        while (current != null) {
            if (current.hasAttr("hidden") || "true".equalsIgnoreCase(current.attr("aria-hidden"))) {
                return true;
            }
            String tag = current.normalName();
            if (List.of("template", "script", "style", "noscript").contains(tag)
                    || ("input".equals(tag) && "hidden".equalsIgnoreCase(current.attr("type")))) {
                return true;
            }
            String style = current.attr("style").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            if (style.matches(".*(?:display:none|visibility:hidden|visibility:collapse|opacity:0(?:[;!]|$)"
                    + "|font-size:0(?:px|em|rem|%)?(?:[;!]|$)|clip:rect\\(0(?:px)?,0(?:px)?,0(?:px)?,0(?:px)?\\)"
                    + "|clip-path:inset\\(50%\\)|text-indent:-[0-9]{3,}px).*")) {
                return true;
            }
            String classes = current.className().toLowerCase(Locale.ROOT);
            for (String className : classes.split("\\s+")) {
                if (Set.of("hidden", "d-none", "sr-only", "visually-hidden", "screen-reader-text")
                        .contains(className)) {
                    return true;
                }
            }
            current = current.parent();
        }
        return false;
    }

    private boolean containsHost(String text, String host) {
        if (text == null || host == null || host.isBlank()) {
            return false;
        }
        Pattern hostPattern = Pattern.compile("(?i)(?<![A-Za-z0-9.-])(?:www\\.)?"
                + Pattern.quote(host) + "(?![A-Za-z0-9.-])");
        return hostPattern.matcher(text).find();
    }

    private boolean hasMalformedCandidateAnchor(String source, String expectedHost,
                                                List<String> expectedKeywords) {
        Matcher anchors = Pattern.compile("(?is)<a\\b.{0,2000}?(?:</a>|$)").matcher(source);
        while (anchors.find()) {
            String anchorSource = anchors.group();
            if (containsHost(anchorSource, expectedHost)
                    && !matchingKeywords(anchorSource, expectedKeywords).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void addBounded(List<String> values, String value) {
        if (value != null && !value.isBlank() && values.size() < 20 && !values.contains(value)) {
            values.add(value.length() > 500 ? value.substring(0, 500) : value);
        }
    }

    private String readHost(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return "";
            }
            String host = uri.getHost();
            if (host == null) {
                return "";
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            return normalizedHost.startsWith("www.") ? normalizedHost.substring(4) : normalizedHost;
        } catch (Exception exception) {
            return "";
        }
    }

    private record DetectionResult(boolean backlinkFound, List<String> matchedKeywords,
                                   List<String> matchedUrls, List<String> matchedAnchorTexts,
                                   boolean fraudDetected, List<String> fraudReasons, int domParseErrorCount) {
    }
}
