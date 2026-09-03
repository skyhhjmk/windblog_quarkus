package com.biliwind.blog.service.link;

import com.biliwind.blog.model.Link;
import com.biliwind.blog.model.LinkArticleReference;
import com.biliwind.blog.model.LinkType;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.service.edge.DataSyncEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文章 Markdown 外链与链接管理的同步和渲染接入。
 */
@ApplicationScoped
public class ArticleExternalLinkService {

    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("(?<!!)\\[([^\\]]+)]\\(([^\\s)]+)(?:\\s+\"[^\"]*\")?\\)");

    @ConfigProperty(name = "windblog.site.primary-domain", defaultValue = "localhost")
    String primaryDomain;

    @Inject
    Event<DataSyncEvent> dataSyncEvent;

    @Inject
    LinkPublicTokenService linkPublicTokenService;

    @Transactional
    public void syncMarkdownLinks(Post post, Map<String, String> contentMarkdownByLanguage) {
        if (post == null) {
            return;
        }
        if (contentMarkdownByLanguage == null || contentMarkdownByLanguage.isEmpty()) {
            return;
        }

        LinkArticleReference.delete("post.id = ?1", post.id);

        Map<Long, LinkReferenceDraft> referenceDraftByLinkId = new HashMap<>();
        for (Map.Entry<String, String> entry : contentMarkdownByLanguage.entrySet()) {
            syncSingleMarkdown(post, entry.getValue(), referenceDraftByLinkId);
        }

        for (Map.Entry<Long, LinkReferenceDraft> entry : referenceDraftByLinkId.entrySet()) {
            LinkReferenceDraft referenceDraft = entry.getValue();
            LinkArticleReference reference = new LinkArticleReference();
            reference.link = referenceDraft.link;
            reference.post = post;
            reference.anchorText = referenceDraft.anchorText;
            reference.normalizedUrl = referenceDraft.normalizedUrl;
            reference.referenceCount = referenceDraft.referenceCount;
            reference.persist();
        }
    }

    public String rewriteArticleExternalLinks(String html) {
        if (html == null || html.isBlank()) {
            return html;
        }

        List<Link> articleLinks = Link.list("type = ?1 and status = 1", LinkType.EXTERNAL_ARTICLE);
        if (articleLinks == null || articleLinks.isEmpty()) {
            return html;
        }

        Map<String, Link> linkByNormalizedUrl = new HashMap<>();
        for (Link link : articleLinks) {
            String normalizedUrl = normalizeUrl(link.url);
            if (normalizedUrl != null) {
                linkByNormalizedUrl.put(normalizedUrl, link);
            }
        }

        Document document = Jsoup.parseBodyFragment(html);
        Elements anchors = document.select("a[href]");
        for (Element anchor : anchors) {
            String href = anchor.attr("href");
            String normalizedHref = normalizeUrl(href);
            if (normalizedHref == null) {
                continue;
            }

            Link link = linkByNormalizedUrl.get(normalizedHref);
            if (link == null) {
                continue;
            }

            String publicToken = linkPublicTokenService.ensurePublicToken(link);
            if (publicToken == null || publicToken.isBlank()) {
                continue;
            }

            anchor.attr("href", "/link/go/" + publicToken);
            anchor.attr("target", "_blank");
            anchor.attr("rel", "nofollow noopener");
            anchor.addClass("article-external-link");
            anchor.attr("data-article-link-preview", "true");
            anchor.attr("data-link-name", safe(link.name));
            anchor.attr("data-link-url", safe(link.url));
            anchor.attr("data-link-description", safe(link.description));
            anchor.attr("data-link-icon", safe(link.icon));
        }

        return document.body().html();
    }

    public Link findArticleLinkByUrl(String rawUrl) {
        String normalizedRawUrl = normalizeUrl(rawUrl);
        if (normalizedRawUrl == null) {
            return null;
        }

        List<Link> articleLinks = Link.list("type = ?1 order by updatedAt desc", LinkType.EXTERNAL_ARTICLE);
        for (Link link : articleLinks) {
            String normalizedLinkUrl = normalizeUrl(link.url);
            if (normalizedRawUrl.equals(normalizedLinkUrl)) {
                return link;
            }
        }
        return null;
    }

    public List<LinkArticleReference> listReferences(Long linkId) {
        if (linkId == null) {
            return java.util.Collections.emptyList();
        }
        return LinkArticleReference.list("link.id = ?1 order by updatedAt desc", linkId);
    }

    public long countReferencedPosts(Long linkId) {
        if (linkId == null) {
            return 0L;
        }
        return LinkArticleReference.count("link.id = ?1", linkId);
    }

    public long countReferences(Long linkId) {
        if (linkId == null) {
            return 0L;
        }
        List<LinkArticleReference> references = listReferences(linkId);
        long count = 0L;
        for (LinkArticleReference reference : references) {
            if (reference.referenceCount != null) {
                count = count + reference.referenceCount.longValue();
            }
        }
        return count;
    }

    private void syncSingleMarkdown(Post post, String markdown, Map<Long, LinkReferenceDraft> referenceDraftByLinkId) {
        if (markdown == null || markdown.isBlank()) {
            return;
        }

        Matcher matcher = MARKDOWN_LINK_PATTERN.matcher(markdown);
        while (matcher.find()) {
            String linkText = matcher.group(1);
            String rawUrl = matcher.group(2);
            Link link = syncOneMarkdownLink(post, linkText, rawUrl);
            if (link != null) {
                addReferenceDraft(referenceDraftByLinkId, link, linkText, rawUrl);
            }
        }
    }

    private Link syncOneMarkdownLink(Post post, String linkText, String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        if (rawUrl.startsWith("#")) {
            return null;
        }
        if (rawUrl.startsWith("/")) {
            return null;
        }

        URI uri = parseUri(rawUrl);
        if (uri == null) {
            return null;
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return null;
        }
        if (isFirstPartyHost(uri.getHost())) {
            return null;
        }

        Link existingLink = findAnyLinkByUrl(rawUrl);
        if (existingLink != null) {
            if (existingLink.type != LinkType.EXTERNAL_ARTICLE) {
                return null;
            }
            updateExistingArticleLink(existingLink, post, linkText);
            return existingLink;
        }

        Link link = new Link();
        link.name = resolveLinkName(linkText, uri);
        link.url = rawUrl.trim();
        link.description = "来自文章：" + post.slug;
        link.image = null;
        link.icon = buildFaviconUrl(uri);
        link.sortOrder = 0;
        link.type = LinkType.EXTERNAL_ARTICLE;
        link.status = 1;
        link.applicationStatus = 1;
        link.availabilityStatus = "UNKNOWN";
        link.backlinkStatus = "UNKNOWN";
        link.target = "_blank";
        link.redirectType = 2;
        link.showUrl = true;
        link.content = null;
        link.email = null;
        link.callbackUrl = null;
        link.note = "由文章 Markdown 自动登记，文章 ID：" + post.id;
        link.publicToken = linkPublicTokenService.ensurePublicToken(link);
        link.createdAt = OffsetDateTime.now();
        link.updatedAt = OffsetDateTime.now();
        link.persist();
        dataSyncEvent.fire(new DataSyncEvent("LINK", link.id, "UPSERT"));
        return link;
    }

    private void addReferenceDraft(Map<Long, LinkReferenceDraft> referenceDraftByLinkId,
                                   Link link,
                                   String linkText,
                                   String rawUrl) {
        if (link == null || link.id == null) {
            return;
        }

        LinkReferenceDraft referenceDraft = referenceDraftByLinkId.get(link.id);
        if (referenceDraft == null) {
            referenceDraft = new LinkReferenceDraft();
            referenceDraft.link = link;
            referenceDraft.anchorText = linkText;
            referenceDraft.normalizedUrl = normalizeUrl(rawUrl);
            referenceDraft.referenceCount = 1;
            referenceDraftByLinkId.put(link.id, referenceDraft);
            return;
        }

        referenceDraft.referenceCount = referenceDraft.referenceCount + 1;
        if (referenceDraft.anchorText == null || referenceDraft.anchorText.isBlank()) {
            referenceDraft.anchorText = linkText;
        }
    }

    private void updateExistingArticleLink(Link existingLink, Post post, String linkText) {
        boolean changed = false;
        if (existingLink.name == null || existingLink.name.isBlank()) {
            existingLink.name = resolveLinkName(linkText, parseUri(existingLink.url));
            changed = true;
        }
        if (existingLink.description == null || existingLink.description.isBlank()) {
            existingLink.description = "来自文章：" + post.slug;
            changed = true;
        }
        if (existingLink.status != 1) {
            existingLink.status = 1;
            changed = true;
        }
        if (existingLink.redirectType != 2) {
            existingLink.redirectType = 2;
            changed = true;
        }
        if (changed) {
            existingLink.updatedAt = OffsetDateTime.now();
            dataSyncEvent.fire(new DataSyncEvent("LINK", existingLink.id, "UPSERT"));
        }
    }

    private Link findAnyLinkByUrl(String rawUrl) {
        String normalizedRawUrl = normalizeUrl(rawUrl);
        if (normalizedRawUrl == null) {
            return null;
        }

        List<Link> links = Link.listAll();
        for (Link link : links) {
            String normalizedLinkUrl = normalizeUrl(link.url);
            if (normalizedRawUrl.equals(normalizedLinkUrl)) {
                return link;
            }
        }
        return null;
    }

    private String resolveLinkName(String linkText, URI uri) {
        if (linkText != null && !linkText.isBlank()) {
            return linkText.trim();
        }
        if (uri != null && uri.getHost() != null) {
            return uri.getHost();
        }
        return "文章外链";
    }

    private boolean isFirstPartyHost(String host) {
        String normalizedHost = normalizeDomain(host);
        String normalizedPrimaryDomain = normalizeDomain(primaryDomain);
        if (normalizedHost == null) {
            return false;
        }
        if (normalizedPrimaryDomain == null) {
            return false;
        }
        return normalizedHost.equals(normalizedPrimaryDomain);
    }

    private URI parseUri(String rawUrl) {
        try {
            return URI.create(rawUrl.trim());
        } catch (Exception exception) {
            return null;
        }
    }

    private String buildFaviconUrl(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return null;
        }
        return uri.getScheme() + "://" + uri.getHost() + "/favicon.ico";
    }

    private String normalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }

        URI uri = parseUri(rawUrl);
        if (uri == null) {
            return rawUrl.trim();
        }

        String scheme = uri.getScheme();
        String host = normalizeDomain(uri.getHost());
        if (scheme == null || host == null) {
            return rawUrl.trim();
        }

        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }

        String query = uri.getQuery();
        String normalizedUrl = scheme.toLowerCase() + "://" + host + path;
        if (query == null || query.isBlank()) {
            return normalizedUrl;
        }
        return normalizedUrl + "?" + query;
    }

    private String normalizeDomain(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String normalizedHost = host.trim().toLowerCase();
        if (normalizedHost.startsWith("www.")) {
            normalizedHost = normalizedHost.substring(4);
        }
        return normalizedHost;
    }

    private String safe(String text) {
        if (text == null) {
            return "";
        }
        return text;
    }

    private static class LinkReferenceDraft {
        Link link;
        String anchorText;
        String normalizedUrl;
        Integer referenceCount;
    }
}
