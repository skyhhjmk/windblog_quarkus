package com.biliwind.blog.service.elasticsearch;

import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.PostTag;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Elasticsearch article search service
 * Responsible for article index creation, update and search, supports service degradation
 */
@ApplicationScoped
public class ElasticsearchPostSearchService {

    private static final Logger log = Logger.getLogger(ElasticsearchPostSearchService.class);

    private final AtomicBoolean indexInitialized = new AtomicBoolean(false);

    @ConfigProperty(name = "quarkus.log.handler.elasticsearch.hosts")
    String elasticsearchHosts;

    private static final String POST_INDEX_TEMPLATE = "windblog-posts-template";
    private static final String POST_INDEX_PATTERN = "windblog-posts-*";
    private static final String POST_INDEX_ALIAS = "windblog-posts";
    private static final String ILM_POLICY_NAME = "windblog-posts-policy";
    @Inject
    ElasticsearchConnectionManager connectionManager;

    void onStart(@Observes StartupEvent event) {
        log.info(">>> ElasticsearchPostSearchService startup initiated");
        log.info(">>> Registering article index initialization callback...");
        connectionManager.onAvailable(this::initializeIndex);
        log.info(">>> Callback registration completed");
    }

    /**
     * Initialize index (called when connection is available)
     */
    private void initializeIndex() {
        log.info(">>> [POST INDEX] ====== initializeIndex() CALLED ======");
        log.info(">>> [POST INDEX] Starting article index initialization...");
        log.info(">>> [POST INDEX] elasticsearchHosts config: " + (elasticsearchHosts != null ? elasticsearchHosts : "NULL"));
        log.info(">>> [POST INDEX] connectionManager injected: " + (connectionManager != null ? "YES" : "NO"));

        int maxRetries = 3;
        int attempt = 0;

        while (attempt < maxRetries) {
            try {
                log.info(">>> [POST INDEX] Attempt " + (attempt + 1) + "/" + maxRetries);
                log.info(">>> [POST INDEX] Calling createIlmPolicy()...");
                createIlmPolicy();
                log.info(">>> [POST INDEX] ILM policy created");
                log.info(">>> [POST INDEX] Calling createPostIndexTemplate()...");
                createPostIndexTemplate();
                log.info(">>> [POST INDEX] Index template created");
                log.info(">>> [POST INDEX] Calling createInitialPostIndex()...");
                createInitialPostIndex();
                log.info(">>> [POST INDEX] Initial index created");
                indexInitialized.set(true);
                log.info(">>> [POST INDEX] ====== initializeIndex() COMPLETED ======");
                log.info(">>> [POST INDEX] Elasticsearch article index initialization completed");
                return;
            } catch (Exception e) {
                attempt++;
                log.error(">>> [POST INDEX] Failed to initialize article index (attempt " + attempt + "/" + maxRetries + "): " + e.getMessage(), e);
                log.error(">>> [POST INDEX] Exception type: " + e.getClass().getName());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn(">>> [POST INDEX] Interrupted during retry sleep");
                        break;
                    }
                }
            }
        }

        log.warn(">>> [POST INDEX] Elasticsearch article index initialization failed, search will fallback to database");
    }

    /**
     * Check if service is available
     */
    public boolean isAvailable() {
        return connectionManager.isAvailable() && indexInitialized.get();
    }

    /**
     * Get service status
     */
    public ServiceStatus getServiceStatus() {
        return new ServiceStatus(
                connectionManager.isAvailable(),
                indexInitialized.get(),
                connectionManager.getHealthStatus().name()
        );
    }

    private void createIlmPolicy() throws Exception {
        log.info("Creating ILM policy: " + ILM_POLICY_NAME);

        String policyJson = """
                {
                  "policy": {
                    "phases": {
                      "hot": {
                        "min_age": "0ms",
                        "actions": {
                          "set_priority": { "priority": 100 },
                          "rollover": { "max_age": "30d", "max_primary_shard_size": "50gb" }
                        }
                      },
                      "warm": {
                        "min_age": "7d",
                        "actions": {
                          "set_priority": { "priority": 50 },
                          "forcemerge": { "max_num_segments": 1 },
                          "shrink": { "number_of_shards": 1 }
                        }
                      },
                      "cold": {
                        "min_age": "30d",
                        "actions": {
                          "set_priority": { "priority": 0 },
                          "freeze": {}
                        }
                      },
                      "delete": {
                        "min_age": "365d",
                        "actions": { "delete": {} }
                      }
                    }
                  }
                }
                """;

        var request = HttpRequest.newBuilder()
                .uri(URI.create(elasticsearchHosts + "/_ilm/policy/" + ILM_POLICY_NAME))
                .PUT(HttpRequest.BodyPublishers.ofString(policyJson))
                .header("Content-Type", "application/json")
                .build();

        var response = connectionManager.sendRequest(request);

        if (response.statusCode() == 200) {
            log.info("ILM policy created successfully");
        } else {
            log.warn("ILM policy creation failed or already exists: " + response.body());
        }
    }

    private void createPostIndexTemplate() throws Exception {
        log.info("Creating article index template: " + POST_INDEX_TEMPLATE);

        String templateJson = """
            {
              "index_patterns": ["windblog-posts-*"],
              "template": {
                "settings": {
                  "number_of_shards": 1,
                  "number_of_replicas": 0,
                  "index.refresh_interval": "5s",
                      "index.lifecycle.name": "%s",
                      "index.lifecycle.rollover_alias": "%s",
                  "analysis": {
                    "analyzer": {
                          "ik_smart_analyzer": {
                            "type": "custom",
                            "tokenizer": "ik_smart",
                            "filter": ["lowercase"]
                          },
                          "ik_max_word_analyzer": {
                            "type": "custom",
                            "tokenizer": "ik_max_word",
                            "filter": ["lowercase"]
                      }
                    }
                  }
                },
                "mappings": {
                  "properties": {
                        "id": { "type": "long" },
                    "title": {
                      "type": "text",
                          "analyzer": "ik_max_word_analyzer",
                          "search_analyzer": "ik_smart_analyzer",
                          "fields": { "keyword": { "type": "keyword", "ignore_above": 256 } }
                    },
                    "content": {
                      "type": "text",
                          "analyzer": "ik_max_word_analyzer",
                          "search_analyzer": "ik_smart_analyzer"
                    },
                    "contentHtml": {
                      "type": "text",
                          "analyzer": "ik_max_word_analyzer",
                          "search_analyzer": "ik_smart_analyzer"
                    },
                    "summary": {
                      "type": "text",
                          "analyzer": "ik_max_word_analyzer",
                          "search_analyzer": "ik_smart_analyzer"
                    },
                        "slug": { "type": "keyword" },
                        "status": { "type": "keyword" },
                        "visibility": { "type": "keyword" },
                        "authorId": { "type": "long" },
                        "authorName": { "type": "keyword" },
                        "categoryId": { "type": "long" },
                        "categoryName": { "type": "keyword" },
                        "categoryPath": { "type": "keyword" },
                        "tags": { "type": "keyword" },
                        "viewCount": { "type": "long" },
                        "featured": { "type": "boolean" },
                        "allowComment": { "type": "boolean" },
                    "seoTitle": {
                      "type": "text",
                          "analyzer": "ik_max_word_analyzer",
                          "search_analyzer": "ik_smart_analyzer"
                    },
                        "seoKeywords": { "type": "keyword" },
                    "seoDescription": {
                      "type": "text",
                          "analyzer": "ik_max_word_analyzer",
                          "search_analyzer": "ik_smart_analyzer"
                    },
                        "publishedAt": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "createdAt": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "updatedAt": { "type": "date", "format": "strict_date_optional_time||epoch_millis" }
                  }
                }
              },
              "priority": 200,
                  "version": 2,
                  "_meta": { "description": "Template for windblog posts index with IK Chinese tokenizer" }
                }
                """.formatted(ILM_POLICY_NAME, POST_INDEX_ALIAS);

        var request = HttpRequest.newBuilder()
            .uri(URI.create(elasticsearchHosts + "/_index_template/" + POST_INDEX_TEMPLATE))
            .PUT(HttpRequest.BodyPublishers.ofString(templateJson))
            .header("Content-Type", "application/json")
            .build();

        var response = connectionManager.sendRequest(request);

        if (response.statusCode() == 200) {
            log.info("Article index template created successfully");
        } else {
            log.error("Article index template creation failed: " + response.body());
            throw new RuntimeException("Article index template creation failed: " + response.statusCode());
        }
    }

    private void createInitialPostIndex() throws Exception {
        log.info("Creating initial article index: " + POST_INDEX_PATTERN);

        String indexBody = """
            {
              "aliases": {
                "%s": {}
              }
            }
            """.formatted(POST_INDEX_ALIAS);

        var request = HttpRequest.newBuilder()
            .uri(URI.create(elasticsearchHosts + "/windblog-posts-000001"))
            .PUT(HttpRequest.BodyPublishers.ofString(indexBody))
            .header("Content-Type", "application/json")
            .build();

        var response = connectionManager.sendRequest(request);

        if (response.statusCode() == 200) {
            log.info("Initial article index created successfully");
        } else if (response.statusCode() == 400) {
            log.warn("Initial article index may already exist: " + response.body());
        } else {
            log.error("Initial article index creation failed: " + response.body());
            throw new RuntimeException("Initial article index creation failed: " + response.statusCode());
        }
    }

    /**
     * Index article to Elasticsearch
     */
    public void indexPost(Post post) throws Exception {
        indexPost(post, null);
    }

    /**
     * Index article to Elasticsearch (with tags)
     */
    public void indexPost(Post post, List<String> tags) throws Exception {
        if (!connectionManager.isAvailable()) {
            log.debugf("Elasticsearch unavailable, skipping article index: %d", post.id);
            return;
        }

        log.infof("Indexing article: %d - %s", post.id, post.slug);

        if (post.status != PostStatus.PUBLISHED) {
            log.debugf("Skipping unpublished article index: %d", post.id);
            return;
        }

        String lang = "en";
        String defaultTitle = LanguageHelper.resolveLocalizedValue(post.title, lang);
        String defaultSummary = LanguageHelper.resolveLocalizedValue(post.summary, lang);

        Map<String, Object> document = new java.util.HashMap<>();
        document.put("id", post.id);
        document.put("title", defaultTitle != null ? defaultTitle : "");
        document.put("slug", post.slug != null ? post.slug : "");
        document.put("status", post.status != null ? post.status.name() : "DRAFT");
        document.put("visibility", post.visibility == 0 ? "PUBLIC" : (post.visibility == 1 ? "PRIVATE" : "PASSWORD"));
        document.put("authorId", post.user != null ? post.user.id : 0);
        document.put("authorName", post.user != null && post.user.username != null ? post.user.username : "");
        document.put("categoryId", post.category != null ? post.category.id : 0);
        document.put("categoryName", post.category != null && post.category.name != null ?
                LanguageHelper.resolveLocalizedValue(post.category.name, lang) : "");
        document.put("categoryPath", post.category != null && post.category.path != null ? post.category.path : "");
        document.put("seoTitle", post.seoTitle != null ? post.seoTitle : "");
        document.put("seoKeywords", post.seoKeywords != null ? post.seoKeywords : "");
        document.put("seoDescription", post.seoDescription != null ? post.seoDescription : "");
        document.put("viewCount", post.viewCount != null ? post.viewCount : 0L);
        document.put("featured", post.featured != null ? post.featured : false);
        document.put("allowComment", post.allowComment != null ? post.allowComment : true);

        if (post.publishedAt != null) {
            document.put("publishedAt", post.publishedAt.toString());
        }
        if (post.createdAt != null) {
            document.put("createdAt", post.createdAt.toString());
        }
        if (post.updatedAt != null) {
            document.put("updatedAt", post.updatedAt.toString());
        }

        if (post.currentRevision != null) {
            String content = post.currentRevision.contentMarkdown != null ?
                    LanguageHelper.resolveLocalizedValue(post.currentRevision.contentMarkdown, lang) : "";
            document.put("content", content);
            document.put("contentHtml", content);
            document.put("summary", defaultSummary != null ? defaultSummary : "");
        } else {
            document.put("content", "");
            document.put("contentHtml", "");
            document.put("summary", defaultSummary != null ? defaultSummary : "");
        }

        if (tags != null && !tags.isEmpty()) {
            document.put("tags", tags);
        } else {
            document.put("tags", List.of());
        }

        String documentJson = new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(document);

        var request = HttpRequest.newBuilder()
            .uri(URI.create(elasticsearchHosts + "/" + POST_INDEX_ALIAS + "/_doc/" + post.id))
            .PUT(HttpRequest.BodyPublishers.ofString(documentJson))
            .header("Content-Type", "application/json")
            .build();

        var response = connectionManager.sendRequest(request);

        if (response.statusCode() == 200 || response.statusCode() == 201) {
            log.infof("Article indexed successfully: %d", post.id);
        } else {
            log.errorf("Article indexing failed: %d - %s", post.id, response.body());
        }
    }

    /**
     * Delete article index
     */
    public void deletePostIndex(Long postId) throws Exception {
        if (!connectionManager.isAvailable()) {
            log.debugf("Elasticsearch unavailable, skipping delete article index: %d", postId);
            return;
        }

        log.infof("Deleting article index: %d", postId);

        var request = HttpRequest.newBuilder()
            .uri(URI.create(elasticsearchHosts + "/" + POST_INDEX_ALIAS + "/_doc/" + postId))
            .DELETE()
            .build();

        var response = connectionManager.sendRequest(request);

        if (response.statusCode() == 200 || response.statusCode() == 404) {
            log.infof("Article index deleted successfully: %d", postId);
        } else {
            log.errorf("Article index deletion failed: %d - %s", postId, response.body());
        }
    }

    /**
     * Search articles (with pagination)
     * Throws exception if Elasticsearch is unavailable, caller decides fallback strategy
     */
    public SearchResult searchPosts(String query, int page, int size,
                                    String status, String category, List<String> tags)
            throws Exception {

        if (!connectionManager.isAvailable()) {
            throw new ElasticsearchUnavailableException("Elasticsearch service is currently unavailable");
        }

        log.infof("Searching articles: query=%s, page=%d, size=%d, status=%s, category=%s",
                 query, page, size, status, category);

        int from = (page - 1) * size;

        StringBuilder searchBody;
        if (query != null && !query.trim().isEmpty()) {
            searchBody = new StringBuilder(String.format("""
                    {
                      "from": %d,
                      "size": %d,
                      "sort": [
                        { "_score": { "order": "desc" } },
                        { "publishedAt": { "order": "desc" } }
                      ],
                      "query": {
                        "bool": {
                          "must": [
                            {
                              "multi_match": {
                                "query": "%s",
                                "fields": ["title^3", "content", "summary", "seoTitle^2", "seoDescription", "tags"],
                                "type": "best_fields"
                              }
                            }
                          ],
                          "filter": [
                            { "term": { "status": "PUBLISHED" } }
                          ]
                        }
                      },
                      "highlight": {
                        "fields": {
                          "title": {},
                          "content": { "fragment_size": 150, "number_of_fragments": 3 },
                          "summary": {}
                        },
                        "pre_tags": ["<em>"],
                        "post_tags": ["</em>"]
                      }
                    }
                    """, from, size, escapeJson(query)));
        } else {
            searchBody = new StringBuilder(String.format("""
                {
                  "from": %d,
                  "size": %d,
                  "sort": [
                    { "publishedAt": { "order": "desc" } }
                  ],
                  "query": {
                    "bool": {
                      "must": [
                            { "match_all": {} }
                      ],
                      "filter": [
                        { "term": { "status": "PUBLISHED" } }
                      ]
                    }
                  }
                }
                    """, from, size));
        }

        var request = HttpRequest.newBuilder()
            .uri(URI.create(elasticsearchHosts + "/" + POST_INDEX_ALIAS + "/_search"))
            .POST(HttpRequest.BodyPublishers.ofString(searchBody.toString()))
            .header("Content-Type", "application/json")
            .build();

        var response = connectionManager.sendRequest(request);

        if (response.statusCode() == 200) {
            return parseSearchResponse(response.body());
        } else {
            log.error("Search failed: " + response.body());
            throw new ElasticsearchUnavailableException("Search failed: " + response.statusCode());
        }
    }

    private SearchResult parseSearchResponse(String responseBody) throws IOException {
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var rootNode = objectMapper.readTree(responseBody);

        long total = rootNode.path("hits").path("total").path("value").asLong();
        var hits = rootNode.path("hits").path("hits");

        List<SearchedPost> posts = new ArrayList<>();
        for (var hit : hits) {
            var source = hit.path("_source");
            var highlight = hit.path("highlight");

            List<String> tagList = new ArrayList<>();
            var tagsNode = source.path("tags");
            if (tagsNode.isArray()) {
                for (var tag : tagsNode) {
                    tagList.add(tag.asText());
                }
            }

            String highlightTitle = highlight.path("title").isArray() && highlight.path("title").size() > 0
                    ? highlight.path("title").get(0).asText() : source.path("title").asText("");

            SearchedPost post = new SearchedPost(
                source.path("id").asLong(),
                    highlightTitle,
                source.path("summary").asText(""),
                source.path("slug").asText(""),
                source.path("authorName").asText(""),
                source.path("categoryName").asText(""),
                source.path("categoryPath").asText(""),
                    tagList,
                source.path("viewCount").asInt(0),
                source.path("featured").asBoolean(false),
                source.path("publishedAt").asText("")
            );
            posts.add(post);
        }

        return new SearchResult(total, posts);
    }

    /**
     * Get article tags
     */
    public List<String> getPostTags(Long postId) {
        List<PostTag> postTags = PostTag.find("post.id", postId).list();
        List<String> tags = new ArrayList<>();
        for (PostTag pt : postTags) {
            if (pt.tag != null && pt.tag.name != null) {
                String tagName = pt.tag.name.get("en");
                if (tagName == null) {
                    tagName = pt.tag.name.values().iterator().next();
                }
                if (tagName != null) {
                    tags.add(tagName);
                }
            }
        }
        return tags;
    }

    /**
     * Check Elasticsearch health status
     */
    public HealthResult checkHealth() {
        boolean healthy = false;
        boolean ilmPolicyExists = false;
        boolean indexTemplateExists = false;

        if (!connectionManager.isAvailable()) {
            return new HealthResult("unavailable", false, false);
        }

        try {
            var healthRequest = HttpRequest.newBuilder()
                    .uri(URI.create(elasticsearchHosts + "/_cluster/health"))
                    .GET()
                    .build();
            var healthResponse = connectionManager.sendRequest(healthRequest);
            healthy = healthResponse.statusCode() == 200;
        } catch (Exception e) {
            log.error("Health check failed", e);
        }

        try {
            var ilmRequest = HttpRequest.newBuilder()
                    .uri(URI.create(elasticsearchHosts + "/_ilm/policy/" + ILM_POLICY_NAME))
                    .GET()
                    .build();
            var ilmResponse = connectionManager.sendRequest(ilmRequest);
            ilmPolicyExists = ilmResponse.statusCode() == 200;
        } catch (Exception e) {
            log.debug("ILM policy check failed", e);
        }

        try {
            var templateRequest = HttpRequest.newBuilder()
                    .uri(URI.create(elasticsearchHosts + "/_index_template/" + POST_INDEX_TEMPLATE))
                    .GET()
                    .build();
            var templateResponse = connectionManager.sendRequest(templateRequest);
            indexTemplateExists = templateResponse.statusCode() == 200;
        } catch (Exception e) {
            log.debug("Index template check failed", e);
        }

        String status = healthy ? "healthy" : "degraded";
        return new HealthResult(status, ilmPolicyExists, indexTemplateExists);
    }

    /**
     * Get ILM policy information
     */
    public String getIlmPolicy() throws Exception {
        if (!connectionManager.isAvailable()) {
            throw new ElasticsearchUnavailableException("Elasticsearch service is currently unavailable");
        }

        var request = HttpRequest.newBuilder()
                .uri(URI.create(elasticsearchHosts + "/_ilm/policy/" + ILM_POLICY_NAME))
                .GET()
                .build();

        var response = connectionManager.sendRequest(request);
        return response.body();
    }

    /**
     * Get index template information
     */
    public String getIndexTemplate() throws Exception {
        if (!connectionManager.isAvailable()) {
            throw new ElasticsearchUnavailableException("Elasticsearch service is currently unavailable");
        }

        var request = HttpRequest.newBuilder()
                .uri(URI.create(elasticsearchHosts + "/_index_template/" + POST_INDEX_TEMPLATE))
                .GET()
                .build();

        var response = connectionManager.sendRequest(request);
        return response.body();
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    public record SearchResult(long total, List<SearchedPost> posts) {}

    public record SearchedPost(
        Long id,
        String title,
        String summary,
        String slug,
        String authorName,
        String categoryName,
        String categoryPath,
        List<String> tags,
        int viewCount,
        boolean featured,
        String publishedAt
    ) {}

    public record HealthResult(String status, boolean ilmPolicyExists, boolean indexTemplateExists) {
    }

    public record ServiceStatus(boolean connectionAvailable, boolean indexInitialized, String healthStatus) {
    }

    /**
     * Elasticsearch unavailable exception
     */
    public static class ElasticsearchUnavailableException extends Exception {
        public ElasticsearchUnavailableException(String message) {
            super(message);
        }
    }
}
