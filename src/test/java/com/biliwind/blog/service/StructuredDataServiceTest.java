package com.biliwind.blog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StructuredDataServiceTest {
    private static final String ORIGIN = "https://blog.example.com";
    private final ObjectMapper mapper = new ObjectMapper();
    private StructuredDataService service;

    @BeforeEach
    void setup() {
        service = new StructuredDataService();
        service.mapper = mapper;
        service.canonicalUrlService = new CanonicalUrlService() {
            @Override public String getBaseUrl() { return ORIGIN; }
        };
        service.configManager = new ConfigManager() {
            @Override public String getString(String key, String field, String fallback) {
                return switch (field) {
                    case "title" -> "Test Blog";
                    case "author" -> "Alice";
                    case "author_url" -> "/about";
                    case "logo_url" -> "/brand-logo.svg";
                    default -> fallback;
                };
            }
            @Override public JsonNode get(String key) {
                return mapper.createObjectNode().set("author_same_as",
                        mapper.createArrayNode().add("https://social.example/alice").add("javascript:alert(1)"));
            }
        };
    }

    @Test
    void scriptEmbeddingPreservesValuesWithoutCreatingHtmlElements() throws Exception {
        String attack = "</ScRiPt><img src=x onerror=alert(1)><!-- & \u2028\u2029";
        String json = service.article(snapshot("Alice", 0), attack, attack,
                ORIGIN + "/post/test", "zh-CN", "");
        var html = Jsoup.parse("<head><script type='application/ld+json'>" + json + "</script></head>");
        assertEquals(1, html.select("script").size());
        assertTrue(html.select("img").isEmpty());
        assertFalse(json.contains("<"));
        assertFalse(json.contains("&"));
        JsonNode article = mapper.readTree(html.selectFirst("script").data()).path("@graph").get(0);
        assertEquals(attack, article.path("headline").asText());
        assertEquals(attack, article.path("description").asText());
    }

    @Test
    void articleContainsPublicImagesDatesPublisherAndAuthorIdentity() throws Exception {
        JsonNode graph = article("Alice", 0, "<img src='/uploads/a.jpg'><img src='/uploads/a.jpg'>"
                + "<img src='javascript:alert(1)'><img src='data:image/png;base64,AA'>"
                + "<amp-img src='//cdn.example/b.jpg'></amp-img>");
        JsonNode article = graph.get(0);
        assertEquals(2, article.path("image").size());
        assertEquals(ORIGIN + "/uploads/a.jpg", article.path("image").get(0).asText());
        assertEquals("https://cdn.example/b.jpg", article.path("image").get(1).asText());
        assertEquals("2026-10-01T08:00+08:00", article.path("datePublished").asText());
        assertFalse(article.has("dateModified"));
        assertEquals(ORIGIN + "/about", article.path("author").path("url").asText());
        assertEquals(1, article.path("author").path("sameAs").size());
        assertEquals(ORIGIN + "/brand-logo.svg", graph.get(1).path("logo").path("url").asText());
        assertTrue(article.path("isAccessibleForFree").asBoolean());
        assertFalse(article.has("hasPart"));
        JsonNode crumbs = graph.get(2).path("itemListElement");
        assertEquals(3, crumbs.size());
        for (int i = 0; i < crumbs.size(); i++) assertEquals(i + 1, crumbs.get(i).path("position").asInt());
        assertEquals(ORIGIN + "/post/test", crumbs.get(2).path("item").asText());
    }

    @Test
    void paidArticleMarksMatchingPaywallAndDoesNotInventImagesOrGuestLinks() throws Exception {
        JsonNode article = article("Guest", 100, "").get(0);
        assertFalse(article.path("isAccessibleForFree").asBoolean());
        assertFalse(article.path("hasPart").path("isAccessibleForFree").asBoolean());
        assertEquals(".post-paywall", article.path("hasPart").path("cssSelector").asText());
        assertFalse(article.has("image"));
        assertFalse(article.path("author").has("url"));
        assertFalse(article.path("author").has("sameAs"));
        assertFalse(article("Unknown", 0, "").get(0).has("author"));
    }

    @Test
    void homeAndCollectionHaveDistinctSchemasAndOrderedBreadcrumbs() throws Exception {
        JsonNode home = mapper.readTree(service.home(1, "zh-CN")).path("@graph");
        assertEquals("WebSite", home.get(0).path("@type").asText());
        assertEquals(ORIGIN + "/", home.get(0).path("url").asText());
        assertEquals("Organization", home.get(1).path("@type").asText());
        JsonNode next = mapper.readTree(service.home(2, "zh-CN")).path("@graph");
        assertEquals("CollectionPage", next.get(0).path("@type").asText());
        JsonNode category = mapper.readTree(service.collection("Java", "/category/java", "en",
                List.of(new StructuredDataService.Breadcrumb("Tech", "/category/tech"),
                        new StructuredDataService.Breadcrumb("Java", "/category/java")))).path("@graph");
        assertEquals(ORIGIN + "/category/java#breadcrumb", category.get(0).path("breadcrumb").path("@id").asText());
        assertEquals("en", category.get(0).path("inLanguage").asText());
        JsonNode items = category.get(1).path("itemListElement");
        assertEquals(3, items.size());
        assertEquals(1, items.get(0).path("position").asInt());
        assertEquals(3, items.get(2).path("position").asInt());
    }

    @Test
    void collectionAlwaysIncludesCurrentPageWhenLegacyTrailIsEmptyOrIncomplete() throws Exception {
        for (List<StructuredDataService.Breadcrumb> trail : List.of(
                List.<StructuredDataService.Breadcrumb>of(),
                List.of(new StructuredDataService.Breadcrumb("Tech", "/category/tech")))) {
            JsonNode graph = mapper.readTree(service.collection("Cloud Native", "/category/cloud-native",
                    "zh-CN", trail)).path("@graph");
            JsonNode items = graph.get(1).path("itemListElement");
            assertEquals(trail.size() + 2, items.size());
            JsonNode current = items.get(items.size() - 1);
            assertEquals("Cloud Native", current.path("name").asText());
            assertEquals(ORIGIN + "/category/cloud-native", current.path("item").asText());
            assertEquals(items.size(), current.path("position").asInt());
        }
    }

    @Test
    void missingBundledDefaultLogoIsNotAdvertisedAsAnImage() throws Exception {
        service.configManager = new ConfigManager() {
            @Override public String getString(String key, String field, String fallback) {
                return "logo_url".equals(field) ? "/logo.png" : fallback;
            }
        };
        assertNull(service.publisherLogoUrl());
        JsonNode publisher = mapper.readTree(service.home(1, "zh-CN")).path("@graph").get(1);
        assertFalse(publisher.has("logo"));
    }

    @Test
    void invalidUrlsAreOmittedAndImageCountIsBounded() {
        assertNull(StructuredDataService.absoluteUrl("javascript:alert(1)", ORIGIN));
        assertNull(StructuredDataService.absoluteUrl("https://user:password@example.com/x", ORIGIN));
        assertNull(StructuredDataService.absoluteUrl("http://[bad", ORIGIN));
        assertNull(StructuredDataService.absoluteUrl("", ORIGIN));
        assertEquals(List.of(ORIGIN + "/1", ORIGIN + "/2", ORIGIN + "/3"),
                StructuredDataService.imageUrls("<img src='/1'><img src='/2'><img src='/3'><img src='/4'>", ORIGIN));
    }

    @Test
    void serializationFailureOmitsMarkupInsteadOfReturningAnEmptyObject() {
        service.mapper = new ObjectMapper() {
            @Override public String writeValueAsString(Object value) throws JsonProcessingException {
                throw new JsonProcessingException("test failure") {};
            }
        };
        assertNull(service.home(1, "zh-CN"));
    }

    private JsonNode article(String author, long price, String html) throws Exception {
        return mapper.readTree(service.article(snapshot(author, price), "Title", "Summary",
                ORIGIN + "/post/test", "zh-CN", html)).path("@graph");
    }

    private PublicCacheRefreshService.PublicPostSnapshot snapshot(String author, long price) {
        return new PublicCacheRefreshService.PublicPostSnapshot(
                1L, "test", 1L, Map.of("zh-CN", "Title"), Map.of(), Map.of(), null,
                Map.of(), null, null, null, null, Map.of("zh-CN", "Tech"), "tech", List.of(), author,
                OffsetDateTime.parse("2026-10-01T08:00:00+08:00"), null,
                0L, false, true, (short) 0, price, List.of(), List.of(), List.of(), List.of(), null, List.of());
    }
}
