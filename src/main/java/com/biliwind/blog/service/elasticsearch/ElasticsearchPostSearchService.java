package com.biliwind.blog.service.elasticsearch;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PostAuthorHelper;
import com.biliwind.blog.common.helper.SearchContentHelper;
import com.biliwind.blog.common.dto.SearchHighlightSegment;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.PostTag;
import com.biliwind.blog.service.edge.NodeRoleService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Elasticsearch article search service
 * Responsible for article index creation, update and search, supports service degradation
 */
@ApplicationScoped
public class ElasticsearchPostSearchService {

    private static final Logger log = Logger.getLogger(ElasticsearchPostSearchService.class);

    private static final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private final AtomicBoolean indexInitialized = new AtomicBoolean(false);

    private static final String POST_INDEX_TEMPLATE = "windblog-posts-template";
    private static final String POST_INDEX_PATTERN = "windblog-posts-*";
    private static final String POST_INDEX_ALIAS = "windblog-posts";
    private static final String ILM_POLICY_NAME = "windblog-posts-policy";
    @Inject
    ElasticsearchConnectionManager connectionManager;
    @Inject
    NodeRoleService nodeRoleService;

    void onStart(@Observes StartupEvent event) {
        if (nodeRoleService.isEdgeNode()) {
            log.info("当前节点是边缘节点，跳过 Elasticsearch 文章索引初始化");
            return;
        }

        log.debug("ElasticsearchPostSearchService startup initiated");
        log.debug("Registering article index initialization callback...");
        connectionManager.onAvailable(this::initializeIndex);
        log.debug("Callback registration completed");
    }

    /**
     * Initialize index (called when connection is available)
     */
    private void initializeIndex() {
        log.debug("[POST INDEX] ====== initializeIndex() CALLED ======");
        log.debug("[POST INDEX] Starting article index initialization...");
        log.debug("[POST INDEX] Elasticsearch address: " + connectionManager.getCurrentSettings().hosts());
        log.debug("[POST INDEX] connectionManager injected: " + (connectionManager != null ? "YES" : "NO"));

        int maxRetries = 3;
        int attempt = 0;

        while (attempt < maxRetries) {
            try {
                log.debug("[POST INDEX] Attempt " + (attempt + 1) + "/" + maxRetries);
                log.debug("[POST INDEX] Calling createIlmPolicy()...");
                createIlmPolicy();
                log.debug("[POST INDEX] ILM policy created");
                log.debug("[POST INDEX] Calling createPostIndexTemplate()...");
                createPostIndexTemplate();
                log.debug("[POST INDEX] Index template created");
                log.debug("[POST INDEX] Calling createInitialPostIndex()...");
                createInitialPostIndex();
                log.debug("[POST INDEX] Initial index created");
                indexInitialized.set(true);
                log.debug("[POST INDEX] ====== initializeIndex() COMPLETED ======");
                log.debug("[POST INDEX] Elasticsearch article index initialization completed");
                return;
            } catch (Exception e) {
                attempt++;
                log.error("[POST INDEX] Failed to initialize article index (attempt " + attempt + "/"
                        + maxRetries + "): " + SensitiveMessageSanitizer.sanitize(e.getMessage()), e);
                log.error("[POST INDEX] Exception type: " + e.getClass().getName());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("[POST INDEX] Interrupted during retry sleep");
                        break;
                    }
                }
            }
        }

        log.warn("[POST INDEX] Elasticsearch article index initialization failed, search will fallback to database");
    }

    /**
     * Check if service is available
     */
    public boolean isAvailable() {
        if (nodeRoleService.isEdgeNode()) {
            return false;
        }
        if (!connectionManager.isAvailable()) {
            return false;
        }
        return isPostIndexInitialized();
    }

    /**
     * Get service status
     */
    public ServiceStatus getServiceStatus() {
        if (nodeRoleService.isEdgeNode()) {
            return new ServiceStatus(false, false, "DISABLED");
        }
        boolean connectionAvailable = connectionManager.isAvailable();
        boolean postIndexInitialized = false;
        if (connectionAvailable) {
            postIndexInitialized = isPostIndexInitialized();
        }
        return new ServiceStatus(
                connectionAvailable,
                postIndexInitialized,
                connectionManager.getHealthStatus().name()
        );
    }

    private boolean isPostIndexInitialized() {
        if (indexInitialized.get()) {
            return true;
        }

        HealthResult healthResult = checkHealth();
        boolean existingIndexSetupReady = "healthy".equals(healthResult.status())
                && healthResult.ilmPolicyExists()
                && healthResult.indexTemplateExists()
                && healthResult.postAliasExists();
        if (existingIndexSetupReady) {
            indexInitialized.set(true);
            log.info("Elasticsearch article index status restored from existing ES metadata");
            return true;
        }
        return false;
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
                          "forcemerge": { "max_num_segments": 1 }
                        }
                      }
                    }
                  }
                }
                """;

        var request = HttpRequest.newBuilder()
                .uri(connectionManager.resolveUri("/_ilm/policy/" + ILM_POLICY_NAME))
                .PUT(HttpRequest.BodyPublishers.ofString(policyJson))
                .header("Content-Type", "application/json")
                .build();

        var response = connectionManager.sendRequest(request);

        if (response.statusCode() == 200) {
            log.info("ILM policy created successfully");
        } else {
            log.warn("ILM policy creation failed or already exists: "
                    + SensitiveMessageSanitizer.sanitize(response.body()));
        }
    }

    private void createPostIndexTemplate() throws Exception {
        log.info("Creating article index template: " + POST_INDEX_TEMPLATE);

        ElasticsearchSettingsService.ElasticsearchSettings settings = connectionManager.getCurrentSettings();
        String indexTokenizer = settings.analyzer();
        String searchTokenizer = settings.analyzer();
        if (!"standard".equals(settings.analyzer())) {
            searchTokenizer = "ik_smart";
        }

        String indexAnalyzerFiltersJson = "[\"lowercase\"]";
        String searchAnalyzerFiltersJson = "[\"lowercase\"]";
        String synonymFilterJson = "";
        if (!settings.synonyms().isEmpty()) {
            searchAnalyzerFiltersJson = "[\"lowercase\", \"windblog_synonyms\"]";
            synonymFilterJson = """
                    ,
                    "filter": {
                      "windblog_synonyms": {
                        "type": "synonym_graph",
                        "lenient": true,
                        "synonyms": %s
                      }
                    }
                    """.formatted(objectMapper.writeValueAsString(settings.synonyms()));
        }

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
                          "windblog_index_analyzer": {
                            "type": "custom",
                            "tokenizer": "%s",
                            "filter": %s
                          },
                          "windblog_search_analyzer": {
                            "type": "custom",
                            "tokenizer": "%s",
                            "filter": %s
                      }
                    }%s
                  }
                },
                "mappings": {
                  "properties": {
                        "id": { "type": "long" },
                    "title": {
                      "type": "text",
                          "analyzer": "windblog_index_analyzer",
                          "search_analyzer": "windblog_search_analyzer",
                          "fields": { "keyword": { "type": "keyword", "ignore_above": 256 } }
                    },
                    "content": {
                      "type": "text",
                          "analyzer": "windblog_index_analyzer",
                          "search_analyzer": "windblog_search_analyzer"
                    },
                    "contentHtml": {
                      "type": "text",
                          "analyzer": "windblog_index_analyzer",
                          "search_analyzer": "windblog_search_analyzer"
                    },
                    "summary": {
                      "type": "text",
                          "analyzer": "windblog_index_analyzer",
                          "search_analyzer": "windblog_search_analyzer"
                    },
                    "translations": {
                      "type": "text",
                          "analyzer": "windblog_index_analyzer",
                          "search_analyzer": "windblog_search_analyzer"
                    },
                        "aiSummary": {
                          "type": "text",
                              "analyzer": "windblog_index_analyzer",
                              "search_analyzer": "windblog_search_analyzer"
                        },
                        "slug": { "type": "keyword" },
                        "status": { "type": "keyword" },
                        "visibility": { "type": "keyword" },
                        "authorId": { "type": "long" },
                        "authorName": { "type": "keyword" },
                        "categoryId": { "type": "long" },
                        "categoryName": { "type": "keyword" },
                        "categorySlug": { "type": "keyword" },
                        "categoryPath": { "type": "keyword" },
                        "categories": {
                          "properties": {
                            "name": { "type": "keyword" },
                            "slug": { "type": "keyword" }
                          }
                        },
                        "tags": { "type": "keyword" },
                        "viewCount": { "type": "long" },
                        "featured": { "type": "boolean" },
                        "allowComment": { "type": "boolean" },
                    "seoTitle": {
                      "type": "text",
                          "analyzer": "windblog_index_analyzer",
                          "search_analyzer": "windblog_search_analyzer"
                    },
                        "seoKeywords": { "type": "keyword" },
                    "seoDescription": {
                      "type": "text",
                          "analyzer": "windblog_index_analyzer",
                          "search_analyzer": "windblog_search_analyzer"
                    },
                        "publishedAt": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "createdAt": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                            "updatedAt": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                                "visibilityRegions": { "type": "keyword" },
                                "suggest": {
                                  "type": "completion",
                                  "analyzer": "windblog_index_analyzer",
                                  "search_analyzer": "windblog_search_analyzer"
                                }
                  }
                }
              },
              "priority": 200,
                  "version": 3,
                  "_meta": { "description": "Template for windblog posts index with IK Chinese tokenizer and translated fields" }
                }
                """.formatted(
                ILM_POLICY_NAME,
                POST_INDEX_ALIAS,
                indexTokenizer,
                indexAnalyzerFiltersJson,
                searchTokenizer,
                searchAnalyzerFiltersJson,
                synonymFilterJson
        );

        var request = HttpRequest.newBuilder()
            .uri(connectionManager.resolveUri("/_index_template/" + POST_INDEX_TEMPLATE))
            .PUT(HttpRequest.BodyPublishers.ofString(templateJson))
            .header("Content-Type", "application/json")
            .build();

        var response = connectionManager.sendRequest(request);

        if (response.statusCode() == 200) {
            log.info("Article index template created successfully");
        } else {
            log.error("Article index template creation failed: "
                    + SensitiveMessageSanitizer.sanitize(response.body()));
            throw new RuntimeException("Article index template creation failed: " + response.statusCode());
        }
    }

    private void createInitialPostIndex() throws Exception {
        ensurePostIndexAlias();
    }

    public void applyIndexConfiguration() throws Exception {
        createIlmPolicy();
        createPostIndexTemplate();
        createInitialPostIndex();
        indexInitialized.set(true);
    }

    /**
     * Ensures that the article write alias exists. If an earlier failed rebuild left
     * an index behind without its alias, reattach the newest existing generation.
     */
    public void ensurePostIndexReady() throws Exception {
        if (!connectionManager.isAvailable()) {
            throw new ElasticsearchUnavailableException("Elasticsearch service is currently unavailable");
        }
        if (!indexInitialized.get()) {
            createIlmPolicy();
            createPostIndexTemplate();
        }
        ensurePostIndexAlias();
        indexInitialized.set(true);
    }

    private void ensurePostIndexAlias() throws Exception {
        HttpResponse<String> aliasResponse = connectionManager.sendRequest(HttpRequest.newBuilder()
                .uri(connectionManager.resolveUri("/_alias/" + POST_INDEX_ALIAS))
                .GET()
                .build());
        if (aliasResponse.statusCode() == 200) {
            com.fasterxml.jackson.databind.JsonNode aliases = objectMapper.readTree(aliasResponse.body());
            if (aliases.isObject() && !aliases.isEmpty()) {
                return;
            }
            throw new IllegalStateException("Elasticsearch article alias has no index targets");
        }
        if (aliasResponse.statusCode() != 404) {
            throw new IllegalStateException("Unable to inspect Elasticsearch article alias: "
                    + aliasResponse.statusCode());
        }

        List<String> existingIndices = getPostIndexNames();
        if (postIndexNameExists()) {
            String targetIndex = createEmptyPostIndexGeneration();
            HttpResponse<String> deleteLegacyResponse = connectionManager.sendRequest(HttpRequest.newBuilder()
                    .uri(connectionManager.resolveUri("/" + POST_INDEX_ALIAS))
                    .DELETE()
                    .build());
            if (deleteLegacyResponse.statusCode() != 200 && deleteLegacyResponse.statusCode() != 404) {
                throw new IllegalStateException("Unable to remove legacy concrete article index: "
                        + deleteLegacyResponse.statusCode() + " - "
                        + SensitiveMessageSanitizer.sanitize(deleteLegacyResponse.body()));
            }
            addPostIndexAlias(targetIndex);
            log.warnf("Migrated legacy concrete article index to alias %s -> %s; database sync will repopulate it",
                    POST_INDEX_ALIAS, targetIndex);
            return;
        }

        String targetIndex = existingIndices.stream()
                .max(Comparator.comparingLong(ElasticsearchPostSearchService::indexGeneration)
                        .thenComparing(Comparator.naturalOrder()))
                .orElse("windblog-posts-000001");

        if (existingIndices.isEmpty()) {
            String indexBody = objectMapper.writeValueAsString(Map.of(
                    "aliases", Map.of(POST_INDEX_ALIAS, Map.of("is_write_index", true))));
            HttpResponse<String> createResponse = connectionManager.sendRequest(HttpRequest.newBuilder()
                    .uri(connectionManager.resolveUri("/" + targetIndex))
                    .PUT(HttpRequest.BodyPublishers.ofString(indexBody))
                    .header("Content-Type", "application/json")
                    .build());
            if (createResponse.statusCode() == 200 || createResponse.statusCode() == 201) {
                log.infof("Created article index %s with write alias %s", targetIndex, POST_INDEX_ALIAS);
                return;
            }
            if (createResponse.statusCode() == 400 && postAliasExists()) {
                return;
            }
            throw new IllegalStateException("Unable to create Elasticsearch article index: "
                    + createResponse.statusCode() + " - "
                    + SensitiveMessageSanitizer.sanitize(createResponse.body()));
        }

        addPostIndexAlias(targetIndex);
        log.warnf("Reattached missing article alias %s to existing index %s", POST_INDEX_ALIAS, targetIndex);
    }

    private boolean postIndexNameExists() throws Exception {
        HttpResponse<String> response = connectionManager.sendRequest(HttpRequest.newBuilder()
                .uri(connectionManager.resolveUri("/" + POST_INDEX_ALIAS))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build());
        if (response.statusCode() == 200) {
            return true;
        }
        if (response.statusCode() == 404) {
            return false;
        }
        throw new IllegalStateException("Unable to inspect legacy concrete article index: "
                + response.statusCode());
    }

    private void addPostIndexAlias(String targetIndex) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode body = objectMapper.createObjectNode();
        body.putArray("actions").addObject().putObject("add")
                .put("index", targetIndex)
                .put("alias", POST_INDEX_ALIAS)
                .put("is_write_index", true);
        HttpResponse<String> response = connectionManager.sendRequest(HttpRequest.newBuilder()
                .uri(connectionManager.resolveUri("/_aliases"))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .header("Content-Type", "application/json")
                .build());
        if (response.statusCode() == 200 || (response.statusCode() == 400 && postAliasExists())) {
            return;
        }
        throw new IllegalStateException("Unable to attach Elasticsearch article alias: "
                + response.statusCode() + " - "
                + SensitiveMessageSanitizer.sanitize(response.body()));
    }

    private boolean postAliasExists() throws Exception {
        HttpResponse<String> response = connectionManager.sendRequest(HttpRequest.newBuilder()
                .uri(connectionManager.resolveUri("/_alias/" + POST_INDEX_ALIAS))
                .GET()
                .build());
        return response.statusCode() == 200;
    }

    private List<String> getPostIndexNames() throws Exception {
        HttpResponse<String> response = connectionManager.sendRequest(HttpRequest.newBuilder()
                .uri(connectionManager.resolveUri("/_cat/indices/" + POST_INDEX_PATTERN + "?h=index"))
                .GET()
                .build());
        if (response.statusCode() == 404) {
            return List.of();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Unable to list Elasticsearch article indices: "
                    + response.statusCode());
        }
        return response.body().lines().map(String::trim).filter(name -> !name.isEmpty()).toList();
    }

    private static long indexGeneration(String indexName) {
        Matcher matcher = Pattern.compile("windblog-posts-(\\d+)$").matcher(indexName);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    public String createRebuildIndex() throws Exception {
        ensurePostIndexReady();
        return createEmptyPostIndexGeneration();
    }

    private String createEmptyPostIndexGeneration() throws Exception {
        long generation = getPostIndexNames().stream()
                .mapToLong(ElasticsearchPostSearchService::indexGeneration)
                .max()
                .orElse(0) + 1;
        for (int attempt = 0; attempt < 10; attempt++, generation++) {
            String indexName = "windblog-posts-%06d".formatted(generation);
            HttpResponse<String> response = connectionManager.sendRequest(HttpRequest.newBuilder()
                    .uri(connectionManager.resolveUri("/" + indexName))
                    .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                    .header("Content-Type", "application/json")
                    .build());
            if (response.statusCode() == 200 || response.statusCode() == 201) {
                return indexName;
            }
            if (response.statusCode() != 400) {
                throw new IllegalStateException("Unable to create replacement article index: "
                        + response.statusCode() + " - "
                        + SensitiveMessageSanitizer.sanitize(response.body()));
            }
        }
        throw new IllegalStateException("Unable to reserve a new Elasticsearch article index generation");
    }

    public void promoteRebuildIndex(String newIndex) throws Exception {
        HttpResponse<String> aliasResponse = connectionManager.sendRequest(HttpRequest.newBuilder()
                .uri(connectionManager.resolveUri("/_alias/" + POST_INDEX_ALIAS))
                .GET()
                .build());
        if (aliasResponse.statusCode() != 200 && aliasResponse.statusCode() != 404) {
            throw new IllegalStateException("Unable to inspect article alias before promotion: "
                    + aliasResponse.statusCode());
        }

        com.fasterxml.jackson.databind.node.ArrayNode actions = objectMapper.createArrayNode();
        if (aliasResponse.statusCode() == 200) {
            com.fasterxml.jackson.databind.JsonNode aliasRoot = objectMapper.readTree(aliasResponse.body());
            java.util.Iterator<String> indexNames = aliasRoot.fieldNames();
            while (indexNames.hasNext()) {
                String indexName = indexNames.next();
                if (!newIndex.equals(indexName)) {
                    actions.addObject().putObject("remove")
                            .put("index", indexName)
                            .put("alias", POST_INDEX_ALIAS);
                }
            }
        }
        actions.addObject().putObject("add")
                .put("index", newIndex)
                .put("alias", POST_INDEX_ALIAS)
                .put("is_write_index", true);
        com.fasterxml.jackson.databind.node.ObjectNode body = objectMapper.createObjectNode();
        body.set("actions", actions);

        HttpResponse<String> response = connectionManager.sendRequest(HttpRequest.newBuilder()
                .uri(connectionManager.resolveUri("/_aliases"))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .header("Content-Type", "application/json")
                .build());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Unable to promote replacement article index: "
                    + response.statusCode() + " - "
                    + SensitiveMessageSanitizer.sanitize(response.body()));
        }
        indexInitialized.set(true);
    }

    public boolean isPostIndexAliasTarget(String indexName) throws Exception {
        HttpResponse<String> response = connectionManager.sendRequest(HttpRequest.newBuilder()
                .uri(connectionManager.resolveUri("/_alias/" + POST_INDEX_ALIAS))
                .GET()
                .build());
        if (response.statusCode() == 404) {
            return false;
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Unable to verify article alias target: " + response.statusCode());
        }
        com.fasterxml.jackson.databind.JsonNode aliasRoot = objectMapper.readTree(response.body());
        return aliasRoot.path(indexName).path("aliases").has(POST_INDEX_ALIAS);
    }

    public void deleteIndexGeneration(String indexName) {
        try {
            HttpResponse<String> response = connectionManager.sendRequest(HttpRequest.newBuilder()
                    .uri(connectionManager.resolveUri("/" + indexName))
                    .DELETE()
                    .build());
            if (response.statusCode() != 200 && response.statusCode() != 404) {
                log.warnf("Could not remove failed replacement index %s: %d", indexName, response.statusCode());
            }
        } catch (Exception exception) {
            log.warnf("Could not remove failed replacement index %s: %s", indexName,
                    SensitiveMessageSanitizer.sanitize(exception.getMessage()));
        }
    }

    public List<String> analyzeText(String text) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("analyzer", "windblog_search_analyzer");
        requestBody.put("text", text);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(connectionManager.resolveUri("/" + POST_INDEX_ALIAS + "/_analyze"))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = connectionManager.sendRequest(request);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("分词预览失败: "
                    + SensitiveMessageSanitizer.sanitize(response.body()));
        }

        List<String> tokens = new ArrayList<>();
        com.fasterxml.jackson.databind.JsonNode tokenNodes = objectMapper.readTree(response.body()).path("tokens");
        if (tokenNodes.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode tokenNode : tokenNodes) {
                String token = tokenNode.path("token").asText("");
                if (!token.isBlank()) {
                    tokens.add(token);
                }
            }
        }
        return tokens;
    }

    /**
     * Concatenates every available language version into one search-only field
     * so a translated article is discoverable from either language. The display
     * fields (title/content) keep the site default language.
     */
    private String translationSearchText(Post post) {
        StringBuilder text = new StringBuilder();
        appendLocalized(text, post.title);
        appendLocalized(text, post.summary);
        if (post.publishedRevision != null) {
            appendLocalized(text, post.publishedRevision.contentMarkdown);
        }
        return text.toString().trim();
    }

    private void appendLocalized(StringBuilder buffer, Map<String, String> values) {
        if (values == null) {
            return;
        }
        for (String value : values.values()) {
            if (value != null && !value.isBlank()) {
                buffer.append(value).append('\n');
            }
        }
    }

    /**
     * Index article to Elasticsearch
     */
    @jakarta.transaction.Transactional
    public void indexPost(Post post) throws Exception {
        indexPost(post, null);
    }

    /**
     * Index article to Elasticsearch (with tags)
     */
    @jakarta.transaction.Transactional
    public void indexPost(Post post, List<String> tags) throws Exception {
        indexPost(post, tags, POST_INDEX_ALIAS);
    }

    @jakarta.transaction.Transactional
    public boolean indexPublicPostById(Long postId, String targetIndex) throws Exception {
        Post post = Post.findById(postId);
        if (!canIndexPost(post)) {
            deletePostIndex(postId, targetIndex);
            return false;
        }
        indexPost(post, getPostTags(postId), targetIndex);
        return true;
    }

    @jakarta.transaction.Transactional
    public void indexPost(Post post, List<String> tags, String targetIndex) throws Exception {
        validateIndexName(targetIndex);
        if (!connectionManager.isAvailable()) {
            throw new ElasticsearchUnavailableException("Elasticsearch service is currently unavailable");
        }

        log.infof("Indexing article: %d - %s", post.id, post.slug);

        if (!canIndexPost(post)) {
            log.debugf("Skipping non-public article index: %d", post.id);
            deletePostIndex(post.id, targetIndex);
            return;
        }

        String lang = LanguageConstant.DEFAULT_LANG;
        String defaultTitle = LanguageHelper.resolveLocalizedValue(post.title, lang);
        String defaultSummary = LanguageHelper.resolveLocalizedValue(post.summary, lang);

        Map<String, Object> document = new java.util.HashMap<>();
        document.put("id", post.id);
        document.put("title", defaultTitle != null ? defaultTitle : "");
        document.put("slug", post.slug != null ? post.slug : "");
        document.put("status", post.status != null ? post.status.name() : "DRAFT");
        document.put("visibility", post.visibility == 0 ? "PUBLIC" : (post.visibility == 1 ? "PRIVATE" : "PASSWORD"));
        document.put("authorId", post.user != null ? post.user.id : 0);
        document.put("authorName", PostAuthorHelper.displayName(post));
        document.put("categoryId", post.category != null ? post.category.id : 0);
        document.put("categoryName", post.category != null && post.category.name != null ?
                LanguageHelper.resolveLocalizedValue(post.category.name, lang) : "");
        document.put("categorySlug", post.category != null ? post.category.slug : "");
        document.put("categoryPath", post.category != null && post.category.path != null ? post.category.path : "");
        List<Map<String, String>> categoryDocuments = post.categories.stream()
                .filter(category -> category != null && category.enabled && category.slug != null)
                .map(category -> {
                    String name = category.name == null ? "" : LanguageHelper.resolveLocalizedValue(category.name, lang);
                    return Map.of("name", name == null ? "" : name, "slug", category.slug);
                })
                .toList();
        if (categoryDocuments.isEmpty() && post.category != null && post.category.enabled && post.category.slug != null) {
            String name = post.category.name == null ? "" : LanguageHelper.resolveLocalizedValue(post.category.name, lang);
            categoryDocuments = List.of(Map.of("name", name == null ? "" : name, "slug", post.category.slug));
        }
        document.put("categories", categoryDocuments);
        document.put("seoTitle", post.seoTitle != null ? post.seoTitle : "");
        document.put("seoKeywords", post.seoKeywords != null ? post.seoKeywords : "");
        document.put("seoDescription", post.seoDescription != null ? post.seoDescription : "");
        document.put("viewCount", post.viewCount != null ? post.viewCount : 0L);
        document.put("featured", post.featured != null ? post.featured : false);
        document.put("allowComment", post.allowComment != null ? post.allowComment : true);
        document.put("visibilityRegions", post.visibilityRegions != null ? post.visibilityRegions : List.of());

        if (post.publishedAt != null) {
            document.put("publishedAt", post.publishedAt.toString());
        }
        if (post.createdAt != null) {
            document.put("createdAt", post.createdAt.toString());
        }
        if (post.updatedAt != null) {
            document.put("updatedAt", post.updatedAt.toString());
        }

        PostRevision publishedRevision = post.publishedRevision;
        if (publishedRevision != null) {
            String content = "";
            if (publishedRevision.contentMarkdown != null) {
                content = LanguageHelper.resolveLocalizedValue(publishedRevision.contentMarkdown, lang);
            }
            String searchableContent = SearchContentHelper.toSearchableText(content, post.renderType);
            document.put("content", searchableContent);
            document.put("contentHtml", searchableContent);
            document.put("summary", defaultSummary != null ? defaultSummary : "");

            String localizedAiSummary = post.aiSummary != null ?
                    LanguageHelper.resolveLocalizedValue(post.aiSummary, lang) : "";
            document.put("aiSummary", localizedAiSummary != null ? localizedAiSummary : "");
        } else {
            document.put("content", "");
            document.put("contentHtml", "");
            document.put("summary", defaultSummary != null ? defaultSummary : "");
            document.put("aiSummary", "");
        }

        if (tags != null && !tags.isEmpty()) {
            document.put("tags", tags);
        } else {
            document.put("tags", List.of());
        }
        document.put("translations", translationSearchText(post));

        // 填充搜索建议字段 (Completion Suggester)
        List<String> suggestions = new ArrayList<>();
        if (defaultTitle != null && !defaultTitle.isBlank()) {
            suggestions.add(defaultTitle);
        }
        if (tags != null) {
            suggestions.addAll(tags);
        }
        document.put("suggest", suggestions);

        String documentJson = objectMapper
            .writeValueAsString(document);

        var request = HttpRequest.newBuilder()
            .uri(connectionManager.resolveUri("/" + targetIndex + "/_doc/" + post.id))
            .PUT(HttpRequest.BodyPublishers.ofString(documentJson))
            .header("Content-Type", "application/json")
            .build();

        var response = connectionManager.sendRequest(request);

        if (response.statusCode() == 200 || response.statusCode() == 201) {
            log.infof("Article indexed successfully: %d", post.id);
        } else {
            throw new IllegalStateException("Article indexing failed: " + response.statusCode() + " - "
                    + SensitiveMessageSanitizer.sanitize(response.body()));
        }
    }

    /**
     * Delete article index
     */
    public void deletePostIndex(Long postId) throws Exception {
        deletePostIndex(postId, POST_INDEX_ALIAS);
    }

    public void deletePostIndex(Long postId, String indexName) throws Exception {
        validateIndexName(indexName);
        if (!connectionManager.isAvailable()) {
            throw new ElasticsearchUnavailableException("Elasticsearch service is currently unavailable");
        }

        log.infof("Deleting article index: %d", postId);

        var request = HttpRequest.newBuilder()
            .uri(connectionManager.resolveUri("/" + indexName + "/_doc/" + postId))
            .DELETE()
            .build();

        var response = connectionManager.sendRequest(request);

        if (response.statusCode() == 200 || response.statusCode() == 202 || response.statusCode() == 404) {
            log.infof("Article index deleted successfully: %d", postId);
        } else {
            throw new IllegalStateException("Article index deletion failed: " + response.statusCode() + " - "
                    + SensitiveMessageSanitizer.sanitize(response.body()));
        }
    }

    public long getPostIndexCount(String indexName) throws Exception {
        validateIndexName(indexName);
        HttpResponse<String> response = connectionManager.sendRequest(HttpRequest.newBuilder()
                .uri(connectionManager.resolveUri("/" + indexName + "/_count"))
                .GET()
                .build());
        if (response.statusCode() != 200) {
            throw new ElasticsearchUnavailableException("Unable to count article index: " + response.statusCode());
        }
        return objectMapper.readTree(response.body()).path("count").asLong(0);
    }

    public Set<Long> getIndexedPostIds(String indexName) throws Exception {
        validateIndexName(indexName);
        Set<Long> ids = new LinkedHashSet<>();
        Long searchAfterId = null;
        while (true) {
            com.fasterxml.jackson.databind.node.ObjectNode body = objectMapper.createObjectNode();
            body.put("size", 1000);
            body.put("_source", false);
            body.putObject("query").putObject("match_all");
            body.putArray("sort").addObject().putObject("id").put("order", "asc");
            if (searchAfterId != null) {
                body.putArray("search_after").add(searchAfterId);
            }

            HttpResponse<String> response = connectionManager.sendRequest(HttpRequest.newBuilder()
                    .uri(connectionManager.resolveUri("/" + indexName + "/_search"))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .header("Content-Type", "application/json")
                    .build());
            if (response.statusCode() != 200) {
                throw new ElasticsearchUnavailableException("Unable to inspect article index documents: "
                        + response.statusCode());
            }

            com.fasterxml.jackson.databind.JsonNode hits = objectMapper.readTree(response.body())
                    .path("hits").path("hits");
            if (!hits.isArray() || hits.isEmpty()) {
                break;
            }
            Long lastId = null;
            for (com.fasterxml.jackson.databind.JsonNode hit : hits) {
                long id = hit.path("_id").asLong(-1);
                if (id < 0) {
                    throw new IllegalStateException("Article index contains a non-numeric document id");
                }
                ids.add(id);
                com.fasterxml.jackson.databind.JsonNode sort = hit.path("sort");
                if (!sort.isArray() || sort.isEmpty()) {
                    throw new IllegalStateException("Article index response omitted the stable sort key");
                }
                lastId = sort.get(0).asLong();
            }
            if (hits.size() < 1000 || lastId == null || lastId.equals(searchAfterId)) {
                break;
            }
            searchAfterId = lastId;
        }
        return ids;
    }

    public void refreshPostIndex(String indexName) throws Exception {
        validateIndexName(indexName);
        HttpResponse<String> response = connectionManager.sendRequest(HttpRequest.newBuilder()
                .uri(connectionManager.resolveUri("/" + indexName + "/_refresh"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        if (response.statusCode() != 200) {
            throw new ElasticsearchUnavailableException("Unable to refresh article index: " + response.statusCode());
        }
    }

    private void validateIndexName(String indexName) {
        if (indexName == null || !(POST_INDEX_ALIAS.equals(indexName)
                || indexName.matches("windblog-posts-[0-9]{6,}"))) {
            throw new IllegalArgumentException("Invalid article index target");
        }
    }

    /**
     * Search articles (with pagination)
     * Throws exception if Elasticsearch is unavailable, caller decides fallback strategy
     */
    public SearchResult searchPosts(String query, int page, int size,
                                    String status, String category, List<String> tags, String region)
            throws Exception {

        if (!connectionManager.isAvailable()) {
            throw new ElasticsearchUnavailableException("Elasticsearch service is currently unavailable");
        }

        log.debugf("Searching articles: query=%s, page=%d, size=%d, status=%s, category=%s",
                 query, page, size, status, category);

        int from = (page - 1) * size;

        StringBuilder searchBody;
        HighlightMarkers highlightMarkers = null;
        if (query != null && !query.trim().isEmpty()) {
            highlightMarkers = HighlightMarkers.create();
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
                                "fields": ["title^3", "content", "summary", "translations^2", "seoTitle^2", "seoDescription", "tags"],
                                "type": "best_fields"
                              }
                            }
                          ],
                          "filter": [
                            { "term": { "status": "PUBLISHED" } },
                            {
                              "bool": {
                                "should": [
                                  { "bool": { "must_not": { "exists": { "field": "visibilityRegions" } } } },
                                  { "term": { "visibilityRegions": "%s" } }
                                ]
                              }
                            }
                          ]
                        }
                      },
                      "highlight": {
                        "fields": {
                          "title": {},
                          "content": { "fragment_size": 150, "number_of_fragments": 3 },
                          "summary": {}
                        },
                        "pre_tags": ["%s"],
                        "post_tags": ["%s"]
                      }
                    }
                    """, from, size, escapeJson(query), region,
                    highlightMarkers.preTag(), highlightMarkers.postTag()));
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
                            { "term": { "status": "PUBLISHED" } },
                            {
                              "bool": {
                                "should": [
                                  { "bool": { "must_not": { "exists": { "field": "visibilityRegions" } } } },
                                  { "term": { "visibilityRegions": "%s" } }
                                ]
                              }
                            }
                          ]
                        }
                      }
                    }
                    """, from, size, region));
        }

        var request = HttpRequest.newBuilder()
            .uri(connectionManager.resolveUri("/" + POST_INDEX_ALIAS + "/_search"))
            .POST(HttpRequest.BodyPublishers.ofString(searchBody.toString()))
            .header("Content-Type", "application/json")
            .build();

        var response = connectionManager.sendRequest(request);

        if (response.statusCode() == 200) {
            return parseSearchResponse(response.body(), highlightMarkers);
        } else {
            log.error("Search failed: " + SensitiveMessageSanitizer.sanitize(response.body()));
            throw new ElasticsearchUnavailableException("Search failed: " + response.statusCode());
        }
    }

    /**
     * 获取搜索建议 (Completion Suggester)
     */
    public List<String> suggestPosts(String query) throws Exception {
        if (!connectionManager.isAvailable()) {
            return List.of();
        }

        if (query == null || query.trim().isEmpty()) {
            return List.of();
        }

        String suggestBody = String.format("""
                {
                  "suggest": {
                    "post-suggest": {
                      "prefix": "%s",
                      "completion": {
                        "field": "suggest",
                        "size": 10,
                        "skip_duplicates": true
                      }
                    }
                  }
                }
                """, escapeJson(query));

        var request = HttpRequest.newBuilder()
                .uri(connectionManager.resolveUri("/" + POST_INDEX_ALIAS + "/_search"))
                .POST(HttpRequest.BodyPublishers.ofString(suggestBody))
                .header("Content-Type", "application/json")
                .build();

        var response = connectionManager.sendRequest(request);

        List<String> results = new ArrayList<>();
        if (response.statusCode() == 200) {
            var rootNode = objectMapper.readTree(response.body());
            var options = rootNode.path("suggest").path("post-suggest").get(0).path("options");

            if (options.isArray()) {
                for (var option : options) {
                    results.add(option.path("text").asText());
                }
            }
        } else {
            log.warn("Suggest failed: " + SensitiveMessageSanitizer.sanitize(response.body()));
        }

        return results;
    }

    private SearchResult parseSearchResponse(String responseBody, HighlightMarkers highlightMarkers) throws IOException {
        var rootNode = objectMapper.readTree(responseBody);

        long total = rootNode.path("hits").path("total").path("value").asLong();
        var hits = rootNode.path("hits").path("hits");

        List<SearchedPost> posts = new ArrayList<>();
        for (var hit : hits) {
            var source = hit.path("_source");
            var highlight = hit.path("highlight");

            String title = source.path("title").asText("");
            String summary = source.path("summary").asText("");
            List<SearchHighlightSegment> titleSegments = highlightedFieldSegments(
                    highlight.path("title"), title, highlightMarkers);
            List<SearchHighlightSegment> summarySegments = highlightedFieldSegments(
                    highlight.path("summary"), summary, highlightMarkers);
            List<List<SearchHighlightSegment>> contentSnippets = highlightedSnippets(
                    highlight.path("content"), highlightMarkers);

            List<String> tagList = new ArrayList<>();
            var tagsNode = source.path("tags");
            if (tagsNode.isArray()) {
                for (var tag : tagsNode) {
                    tagList.add(tag.asText());
                }
            }

            List<SearchCategory> categories = new ArrayList<>();
            var categoriesNode = source.path("categories");
            if (categoriesNode.isArray()) {
                for (var category : categoriesNode) {
                    categories.add(new SearchCategory(category.path("name").asText(""),
                            category.path("slug").asText("")));
                }
            }

            SearchedPost post = new SearchedPost(
                source.path("id").asLong(),
                    source.path("authorId").asLong(0),
                    title,
                summary,
                    source.path("aiSummary").asText(""),
                source.path("slug").asText(""),
                source.path("authorName").asText(""),
                source.path("categoryName").asText(""),
                    source.path("categorySlug").asText(""),
                    source.path("categoryPath").asText(""),
                    categories,
                    tagList,
                source.path("viewCount").asInt(0),
                source.path("featured").asBoolean(false),
                source.path("publishedAt").asText(""),
                    source.path("createdAt").asText(""),
                    titleSegments,
                    summarySegments,
                    contentSnippets
            );
            posts.add(post);
        }

        return new SearchResult(total, posts);
    }

    private List<SearchHighlightSegment> highlightedFieldSegments(
            com.fasterxml.jackson.databind.JsonNode fragments, String fallback, HighlightMarkers markers) {
        if (markers != null && fragments.isArray() && !fragments.isEmpty()) {
            return parseHighlightedFragment(fragments.get(0).asText(""), markers);
        }
        return fallback == null || fallback.isEmpty()
                ? List.of()
                : List.of(new SearchHighlightSegment(fallback, false));
    }

    private List<List<SearchHighlightSegment>> highlightedSnippets(
            com.fasterxml.jackson.databind.JsonNode fragments, HighlightMarkers markers) {
        if (markers == null || !fragments.isArray()) {
            return List.of();
        }
        List<List<SearchHighlightSegment>> snippets = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode fragment : fragments) {
            List<SearchHighlightSegment> segments = parseHighlightedFragment(fragment.asText(""), markers);
            if (segments.stream().anyMatch(SearchHighlightSegment::highlighted)) {
                snippets.add(segments);
            }
        }
        return snippets;
    }

    private List<SearchHighlightSegment> parseHighlightedFragment(String fragment, HighlightMarkers markers) {
        if (fragment == null || fragment.isEmpty() || markers == null) {
            return List.of();
        }
        List<SearchHighlightSegment> segments = new ArrayList<>();
        int cursor = 0;
        while (cursor < fragment.length()) {
            int start = fragment.indexOf(markers.preTag(), cursor);
            if (start < 0) {
                addHighlightSegment(segments, fragment.substring(cursor), false);
                break;
            }
            addHighlightSegment(segments, fragment.substring(cursor, start), false);
            int highlightedStart = start + markers.preTag().length();
            int end = fragment.indexOf(markers.postTag(), highlightedStart);
            if (end < 0) {
                addHighlightSegment(segments, fragment.substring(start), false);
                break;
            }
            addHighlightSegment(segments, fragment.substring(highlightedStart, end), true);
            cursor = end + markers.postTag().length();
        }
        return segments;
    }

    private void addHighlightSegment(List<SearchHighlightSegment> segments, String text, boolean highlighted) {
        if (text.isEmpty()) {
            return;
        }
        if (!segments.isEmpty() && segments.get(segments.size() - 1).highlighted() == highlighted) {
            SearchHighlightSegment previous = segments.remove(segments.size() - 1);
            segments.add(new SearchHighlightSegment(previous.text() + text, highlighted));
        } else {
            segments.add(new SearchHighlightSegment(text, highlighted));
        }
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
        boolean postAliasExists = false;

        if (!connectionManager.isAvailable()) {
            return new HealthResult("unavailable", false, false, false);
        }

        try {
            var healthRequest = HttpRequest.newBuilder()
                    .uri(connectionManager.resolveUri("/_cluster/health"))
                    .GET()
                    .build();
            var healthResponse = connectionManager.sendRequest(healthRequest);
            healthy = healthResponse.statusCode() == 200;
        } catch (Exception e) {
            log.error("Health check failed", e);
        }

        try {
            var ilmRequest = HttpRequest.newBuilder()
                    .uri(connectionManager.resolveUri("/_ilm/policy/" + ILM_POLICY_NAME))
                    .GET()
                    .build();
            var ilmResponse = connectionManager.sendRequest(ilmRequest);
            ilmPolicyExists = ilmResponse.statusCode() == 200;
        } catch (Exception e) {
            log.debug("ILM policy check failed", e);
        }

        try {
            var templateRequest = HttpRequest.newBuilder()
                    .uri(connectionManager.resolveUri("/_index_template/" + POST_INDEX_TEMPLATE))
                    .GET()
                    .build();
            var templateResponse = connectionManager.sendRequest(templateRequest);
            indexTemplateExists = templateResponse.statusCode() == 200;
        } catch (Exception e) {
            log.debug("Index template check failed", e);
        }

        try {
            var aliasRequest = HttpRequest.newBuilder()
                    .uri(connectionManager.resolveUri("/_alias/" + POST_INDEX_ALIAS))
                    .GET()
                    .build();
            var aliasResponse = connectionManager.sendRequest(aliasRequest);
            if (aliasResponse.statusCode() == 200) {
                com.fasterxml.jackson.databind.JsonNode aliases = objectMapper.readTree(aliasResponse.body());
                postAliasExists = aliases.isObject() && !aliases.isEmpty();
            }
        } catch (Exception e) {
            log.debug("Article index alias check failed", e);
        }

        String status = healthy ? "healthy" : "degraded";
        return new HealthResult(status, ilmPolicyExists, indexTemplateExists, postAliasExists);
    }

    /**
     * Get ILM policy information
     */
    public String getIlmPolicy() throws Exception {
        if (!connectionManager.isAvailable()) {
            throw new ElasticsearchUnavailableException("Elasticsearch service is currently unavailable");
        }

        var request = HttpRequest.newBuilder()
                .uri(connectionManager.resolveUri("/_ilm/policy/" + ILM_POLICY_NAME))
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
                .uri(connectionManager.resolveUri("/_index_template/" + POST_INDEX_TEMPLATE))
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

    private boolean canIndexPost(Post post) {
        if (post == null) {
            return false;
        }
        if (post.status != PostStatus.PUBLISHED) {
            return false;
        }
        if (post.deletedAt != null) {
            return false;
        }
        if (post.visibility != 0) {
            return false;
        }
        return post.publishedRevision != null;
    }

    public record SearchResult(long total, List<SearchedPost> posts) {}

    public record SearchCategory(String name, String slug) {}

    private record HighlightMarkers(String preTag, String postTag) {
        private static HighlightMarkers create() {
            String marker = UUID.randomUUID().toString().replace("-", "");
            return new HighlightMarkers("__WBHL_" + marker + "_START__", "__WBHL_" + marker + "_END__");
        }
    }

    public record SearchedPost(
        Long id,
        Long authorId,
        String title,
        String summary,
        String aiSummary,
        String slug,
        String authorName,
        String categoryName,
        String categorySlug,
        String categoryPath,
        List<SearchCategory> categories,
        List<String> tags,
        int viewCount,
        boolean featured,
        String publishedAt,
        String createdAt,
        List<SearchHighlightSegment> titleSegments,
        List<SearchHighlightSegment> summarySegments,
        List<List<SearchHighlightSegment>> contentSnippets
    ) {}

    public record HealthResult(String status, boolean ilmPolicyExists, boolean indexTemplateExists,
                               boolean postAliasExists) {
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
