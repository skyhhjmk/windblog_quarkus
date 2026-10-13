package com.biliwind.blog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jsoup.Jsoup;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Builds public metadata only; never loads a protected revision or private author account. */
@ApplicationScoped
public class StructuredDataService {
    private static final Logger LOG = Logger.getLogger(StructuredDataService.class);

    @Inject ObjectMapper mapper;
    @Inject ConfigManager configManager;
    @Inject CanonicalUrlService canonicalUrlService;

    public record Breadcrumb(String name, String path) {}

    public String home(int page, String language) {
        String home = canonicalUrlService.buildPath("/");
        if (page > 1) {
            return collection("首页 - 页码 " + page, "/page/" + page, language,
                    List.of(new Breadcrumb("首页 - 页码 " + page, "/page/" + page)));
        }
        ObjectNode website = node("WebSite", home + "#website");
        website.put("url", home);
        website.put("name", siteName());
        website.put("description", configManager.getString("site_info", "description", ""));
        website.put("inLanguage", language);
        website.putObject("publisher").put("@id", home + "#publisher");
        return graph(List.of(website, publisher()));
    }

    public String collection(String title, String path, String language, List<Breadcrumb> trail) {
        String url = canonicalUrlService.buildPath(path);
        ObjectNode page = node("CollectionPage", url + "#webpage");
        page.put("url", url);
        page.put("name", title);
        page.put("inLanguage", language);
        page.putObject("breadcrumb").put("@id", url + "#breadcrumb");
        return graph(List.of(page, breadcrumb(url, trail)));
    }

    public String article(PublicCacheRefreshService.PublicPostSnapshot snapshot, String title,
                          String description, String canonicalUrl, String language, String publicHtml) {
        ObjectNode article = node("BlogPosting", canonicalUrl + "#article");
        article.put("headline", title);
        article.put("description", description == null ? "" : description);
        article.put("url", canonicalUrl);
        article.put("inLanguage", language);
        article.putObject("mainEntityOfPage").put("@type", "WebPage").put("@id", canonicalUrl);
        if (snapshot.publishedAt() != null) article.put("datePublished", snapshot.publishedAt().toString());
        if (snapshot.updatedAt() != null) article.put("dateModified", snapshot.updatedAt().toString());
        String name = snapshot.authorName();
        if (name != null && !name.isBlank() && !"Unknown".equals(name)) {
            ObjectNode author = article.putObject("author");
            author.put("@type", "Person");
            author.put("name", name.trim());
            // Site-owner links must not be assigned to guest or AI authors.
            if (name.trim().equals(configManager.getString("site_info", "author", "").trim())) {
                String url = absoluteUrl(configManager.getString("site_info", "author_url", ""), canonicalUrl);
                if (url != null) author.put("url", url);
                JsonNode info = configManager.get("site_info");
                JsonNode links = info == null ? null : info.get("author_same_as");
                if (links != null && links.isArray()) {
                    ArrayNode sameAs = mapper.createArrayNode();
                    for (JsonNode link : links) {
                        String resolved = absoluteUrl(link.asText(), canonicalUrl);
                        if (resolved != null) sameAs.add(resolved);
                    }
                    if (!sameAs.isEmpty()) author.set("sameAs", sameAs);
                }
            }
        }
        List<String> images = imageUrls(publicHtml, canonicalUrl);
        if (!images.isEmpty()) {
            ArrayNode array = article.putArray("image");
            images.forEach(array::add);
        }
        ObjectNode publisher = publisher();
        article.putObject("publisher").put("@id", publisher.path("@id").asText());
        boolean paid = snapshot.postPrice() > 0;
        article.put("isAccessibleForFree", !paid);
        if (paid) {
            article.putObject("hasPart").put("@type", "WebPageElement")
                    .put("isAccessibleForFree", false).put("cssSelector", ".post-paywall");
        }
        List<Breadcrumb> trail = new ArrayList<>();
        if (snapshot.categorySlug() != null && !snapshot.categorySlug().isBlank()) {
            String category = com.biliwind.blog.common.helper.LanguageHelper.resolveLocalizedValue(
                    snapshot.categoryName(), language);
            trail.add(new Breadcrumb(category == null || category.isBlank() ? snapshot.categorySlug() : category,
                    "/category/" + snapshot.categorySlug()));
        }
        trail.add(new Breadcrumb(title, canonicalUrl));
        return graph(List.of(article, publisher, breadcrumb(canonicalUrl, trail)));
    }

    private ObjectNode publisher() {
        String home = canonicalUrlService.buildPath("/");
        ObjectNode publisher = node("Organization", home + "#publisher");
        publisher.put("name", siteName());
        publisher.put("url", home);
        String logo = absoluteUrl(configManager.getString("appearance", "logo_url", "/logo.png"), home);
        if (logo != null) publisher.putObject("logo").put("@type", "ImageObject").put("url", logo);
        return publisher;
    }

    private String siteName() {
        return configManager.getString("site_info", "title", "WindBlog");
    }

    private ObjectNode node(String type, String id) {
        return mapper.createObjectNode().put("@type", type).put("@id", id);
    }

    private ObjectNode breadcrumb(String pageUrl, List<Breadcrumb> trail) {
        ObjectNode breadcrumb = node("BreadcrumbList", pageUrl + "#breadcrumb");
        ArrayNode items = breadcrumb.putArray("itemListElement");
        addBreadcrumb(items, "首页", canonicalUrlService.buildPath("/"));
        for (Breadcrumb item : trail) {
            String url = absoluteUrl(item.path(), canonicalUrlService.buildPath("/"));
            if (url != null) addBreadcrumb(items, item.name(), url);
        }
        return breadcrumb;
    }

    private void addBreadcrumb(ArrayNode items, String name, String url) {
        int position = items.size() + 1;
        items.addObject().put("@type", "ListItem").put("position", position)
                .put("name", name).put("item", url);
    }

    public static List<String> imageUrls(String html, String pageUrl) {
        LinkedHashSet<String> images = new LinkedHashSet<>();
        for (var image : Jsoup.parseBodyFragment(html == null ? "" : html).select("img[src], amp-img[src]")) {
            String url = absoluteUrl(image.attr("src"), pageUrl);
            if (url != null) images.add(url);
            if (images.size() == 3) break;
        }
        return List.copyOf(images);
    }

    public static String absoluteUrl(String value, String base) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(base).resolve(value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) return null;
            return uri.toASCIIString();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String graph(List<ObjectNode> nodes) {
        ObjectNode root = mapper.createObjectNode().put("@context", "https://schema.org");
        ArrayNode graph = root.putArray("@graph");
        nodes.forEach(graph::add);
        try {
            return scriptSafeJson(mapper.writeValueAsString(root));
        } catch (JsonProcessingException exception) {
            LOG.error("Unable to serialize SEO structured data", exception);
            return null;
        }
    }

    static String scriptSafeJson(String json) {
        // JSON escaping preserves values while preventing the HTML parser from closing script.
        return json.replace("&", "\\u0026").replace("<", "\\u003c").replace(">", "\\u003e")
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }
}
