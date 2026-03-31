package com.biliwind.blog.service.elasticsearch;

import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Elasticsearch 文章搜索服务
 * 负责文章索引的创建、更新和搜索
 */
@ApplicationScoped
public class ElasticsearchPostSearchService {

    private static final Logger log = Logger.getLogger(ElasticsearchPostSearchService.class);

    @Inject
    HttpClient httpClient;

    @ConfigProperty(name = "quarkus.log.handler.elasticsearch.hosts")
    String elasticsearchHosts;

    @ConfigProperty(name = "quarkus.log.handler.elasticsearch.username", defaultValue = "")
    String username;

    @ConfigProperty(name = "quarkus.log.handler.elasticsearch.password", defaultValue = "")
    String password;

    @ConfigProperty(name = "quarkus.log.handler.elasticsearch.ssl-trust-all", defaultValue = "false")
    boolean sslTrustAll;

    private static final String POST_INDEX_TEMPLATE = "windblog-posts-template";
    private static final String POST_INDEX_PATTERN = "windblog-posts-*";
    private static final String POST_INDEX_ALIAS = "windblog-posts";

    /**
     * Initialize article index template on application startup
     */
    void onStart(@Observes StartupEvent event) {
        log.info("Initializing Elasticsearch article index...");
        
        try {
            waitForElasticsearch();
            createPostIndexTemplate();
            createInitialPostIndex();
            
            log.info("Elasticsearch article index initialization completed");
        } catch (Exception e) {
            log.error("Elasticsearch article index initialization failed", e);
        }
    }

    /**
     * Wait for Elasticsearch service to be ready
     */
    private void waitForElasticsearch() throws InterruptedException {
        log.info("Waiting for Elasticsearch to start...");
        
        int maxAttempts = 30;
        int attempt = 0;
        
        while (attempt < maxAttempts) {
            try {
                var request = HttpRequest.newBuilder()
                    .uri(URI.create(elasticsearchHosts + "/_cluster/health"))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .GET()
                    .build();
                
                var response = sendRequest(request);
                if (response.statusCode() == 200) {
                    log.info("Elasticsearch is ready");
                    return;
                }
            } catch (Exception e) {
                attempt++;
                log.infof("Waiting for Elasticsearch... (attempt %d/%d)", attempt, maxAttempts);
                TimeUnit.SECONDS.sleep(5);
            }
        }
        
        throw new RuntimeException("Elasticsearch startup timeout");
    }

    /**
     * Create article index template
     */
    private void createPostIndexTemplate() throws IOException, InterruptedException {
        log.info("Creating article index template: " + POST_INDEX_TEMPLATE);
        
        String templateJson = """
            {
              "index_patterns": ["windblog-posts-*"],
              "template": {
                "settings": {
                  "number_of_shards": 1,
                  "number_of_replicas": 0,
                  "index.refresh_interval": "5s",
                  "analysis": {
                    "analyzer": {
                      "chinese_analyzer": {
                        "type": "standard",
                        "stopwords": "_none_"
                      }
                    }
                  }
                },
                "mappings": {
                  "properties": {
                    "id": {
                      "type": "long"
                    },
                    "title": {
                      "type": "text",
                      "analyzer": "chinese_analyzer",
                      "search_analyzer": "chinese_analyzer",
                      "fields": {
                        "keyword": {
                          "type": "keyword"
                        }
                      }
                    },
                    "content": {
                      "type": "text",
                      "analyzer": "chinese_analyzer",
                      "search_analyzer": "chinese_analyzer"
                    },
                    "contentHtml": {
                      "type": "text",
                      "analyzer": "chinese_analyzer"
                    },
                    "summary": {
                      "type": "text",
                      "analyzer": "chinese_analyzer"
                    },
                    "slug": {
                      "type": "keyword"
                    },
                    "status": {
                      "type": "keyword"
                    },
                    "visibility": {
                      "type": "keyword"
                    },
                    "authorId": {
                      "type": "long"
                    },
                    "authorName": {
                      "type": "keyword"
                    },
                    "categoryId": {
                      "type": "long"
                    },
                    "categoryName": {
                      "type": "keyword"
                    },
                    "categoryPath": {
                      "type": "keyword"
                    },
                    "tags": {
                      "type": "keyword"
                    },
                    "viewCount": {
                      "type": "long"
                    },
                    "featured": {
                      "type": "boolean"
                    },
                    "allowComment": {
                      "type": "boolean"
                    },
                    "seoTitle": {
                      "type": "text",
                      "analyzer": "chinese_analyzer"
                    },
                    "seoKeywords": {
                      "type": "keyword"
                    },
                    "seoDescription": {
                      "type": "text",
                      "analyzer": "chinese_analyzer"
                    },
                    "publishedAt": {
                      "type": "date",
                      "format": "strict_date_optional_time||epoch_millis"
                    },
                    "createdAt": {
                      "type": "date",
                      "format": "strict_date_optional_time||epoch_millis"
                    },
                    "updatedAt": {
                      "type": "date",
                      "format": "strict_date_optional_time||epoch_millis"
                    }
                  }
                }
              },
              "priority": 200,
              "version": 1,
              "_meta": {
                "description": "Template for windblog posts index"
              }
            }
            """;
        
        var request = HttpRequest.newBuilder()
            .uri(URI.create(elasticsearchHosts + "/_index_template/" + POST_INDEX_TEMPLATE))
            .PUT(HttpRequest.BodyPublishers.ofString(templateJson))
            .header("Content-Type", "application/json")
            .build();
        
        var response = sendRequest(request);
        
        if (response.statusCode() == 200) {
            log.info("Article index template created successfully");
        } else {
            log.error("Article index template creation failed: " + response.body());
            throw new RuntimeException("Article index template creation failed: " + response.statusCode());
        }
    }

    /**
     * Create initial article index
     */
    private void createInitialPostIndex() throws IOException, InterruptedException {
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
        
        var response = sendRequest(request);
        
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
     * Index article
     */
    public void indexPost(Post post) throws IOException, InterruptedException {
        log.infof("Indexing article: %d - %s", post.id, post.slug);
        
        // Only index published articles
        if (post.status != PostStatus.PUBLISHED) {
            log.debugf("Skipping unpublished article indexing: %d", post.id);
            return;
        }
        
        // 从 JSON 多语言字段中提取默认语言（英文）的值
        String defaultTitle = post.title != null ? post.title.getOrDefault("en", post.title.values().iterator().next()) : "";
        String defaultSummary = post.summary != null ? post.summary.getOrDefault("en", "") : "";
        
        Map<String, Object> document = new java.util.HashMap<>();
        document.put("id", post.id);
        document.put("title", defaultTitle);
        document.put("slug", post.slug != null ? post.slug : "");
        document.put("status", post.status != null ? post.status.name() : "DRAFT");
        document.put("visibility", post.visibility == 0 ? "PUBLIC" : (post.visibility == 1 ? "PRIVATE" : "PASSWORD"));
        document.put("authorId", post.user != null ? post.user.id : 0);
        document.put("authorName", post.user != null && post.user.username != null ? post.user.username : "");
        document.put("categoryId", post.category != null ? post.category.id : 0);
        document.put("categoryName", post.category != null && post.category.name != null ? 
            post.category.name.getOrDefault("en", "") : "");
        document.put("categoryPath", post.category != null && post.category.path != null ? post.category.path : "");
        document.put("seoTitle", post.seoTitle != null ? post.seoTitle : "");
        document.put("seoKeywords", post.seoKeywords != null ? post.seoKeywords : "");
        document.put("seoDescription", post.seoDescription != null ? post.seoDescription : "");
        document.put("viewCount", 0L); // TODO: 从 Post 模型添加 viewCount 字段
        document.put("featured", false); // TODO: 从 Post 模型添加 featured 字段
        document.put("allowComment", true); // TODO: 从 Post 模型添加 allowComment 字段
        
        if (post.publishedAt != null) {
            document.put("publishedAt", post.publishedAt.toString());
        }
        if (post.createdAt != null) {
            document.put("createdAt", post.createdAt.toString());
        }
        if (post.updatedAt != null) {
            document.put("updatedAt", post.updatedAt.toString());
        }
        
        // 获取文章内容（从 PostRevision 获取）
        if (post.currentRevision != null) {
            // 从 JSON 多语言字段中提取默认语言（英文）的值
            String content = post.currentRevision.contentMarkdown != null ? 
                post.currentRevision.contentMarkdown.getOrDefault("en", "") : "";
            document.put("content", content);
            document.put("contentHtml", content); // TODO: 如果有渲染后的 HTML，使用 HTML
            document.put("summary", defaultSummary);
        } else {
            document.put("content", "");
            document.put("contentHtml", "");
            document.put("summary", defaultSummary);
        }
        
        // TODO: 添加 tags 字段（需要从 PostTag 关联表获取）
        document.put("tags", List.of());
        
        String documentJson = new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(document);
        
        var request = HttpRequest.newBuilder()
            .uri(URI.create(elasticsearchHosts + "/" + POST_INDEX_ALIAS + "/_doc/" + post.id))
            .PUT(HttpRequest.BodyPublishers.ofString(documentJson))
            .header("Content-Type", "application/json")
            .build();
        
        var response = sendRequest(request);
        
        if (response.statusCode() == 200 || response.statusCode() == 201) {
            log.infof("Article indexed successfully: %d", post.id);
        } else {
            log.errorf("Article indexing failed: %d - %s", post.id, response.body());
        }
    }

    /**
     * Delete article index
     */
    public void deletePostIndex(Long postId) throws IOException, InterruptedException {
        log.infof("Deleting article index: %d", postId);
        
        var request = HttpRequest.newBuilder()
            .uri(URI.create(elasticsearchHosts + "/" + POST_INDEX_ALIAS + "/_doc/" + postId))
            .DELETE()
            .build();
        
        var response = sendRequest(request);
        
        if (response.statusCode() == 200 || response.statusCode() == 404) {
            log.infof("Article index deleted successfully: %d", postId);
        } else {
            log.errorf("Article index deletion failed: %d - %s", postId, response.body());
        }
    }

    /**
     * Search articles
     */
    public SearchResult searchPosts(String query, int page, int size, 
                                   String status, String category, List<String> tags) 
            throws IOException, InterruptedException {
        
        log.infof("Searching articles: query=%s, page=%d, size=%d, status=%s, category=%s", 
                 query, page, size, status, category);
        
        // 构建搜索请求
        StringBuilder searchBody = new StringBuilder("""
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
            """.formatted((page - 1) * size, size));
        
        // 如果有搜索关键词，添加到 must 子句
        if (query != null && !query.trim().isEmpty()) {
            searchBody = new StringBuilder("""
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
                            "fields": ["title^3", "content", "summary", "seoTitle^2", "tags"]
                          }
                        }
                      ],
                      "filter": [
                        { "term": { "status": "PUBLISHED" } }
                      ]
                    }
                  }
                }
                """.formatted((page - 1) * size, size, escapeJson(query)));
        }
        
        var request = HttpRequest.newBuilder()
            .uri(URI.create(elasticsearchHosts + "/" + POST_INDEX_ALIAS + "/_search"))
            .POST(HttpRequest.BodyPublishers.ofString(searchBody.toString()))
            .header("Content-Type", "application/json")
            .build();
        
        var response = sendRequest(request);
        
        if (response.statusCode() == 200) {
            return parseSearchResponse(response.body());
        } else {
            log.error("Search failed: " + response.body());
            throw new RuntimeException("Search failed: " + response.statusCode());
        }
    }

    /**
     * Parse search response
     */
    private SearchResult parseSearchResponse(String responseBody) throws IOException {
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var rootNode = objectMapper.readTree(responseBody);
        
        long total = rootNode.path("hits").path("total").path("value").asLong();
        var hits = rootNode.path("hits").path("hits");
        
        List<SearchedPost> posts = new java.util.ArrayList<>();
        for (var hit : hits) {
            var source = hit.path("_source");
            var post = new SearchedPost(
                source.path("id").asLong(),
                source.path("title").asText(""),
                source.path("summary").asText(""),
                source.path("slug").asText(""),
                source.path("authorName").asText(""),
                source.path("categoryName").asText(""),
                source.path("categoryPath").asText(""),
                source.path("tags").findValues("text").stream()
                    .map(node -> node.asText())
                    .toList(),
                source.path("viewCount").asInt(0),
                source.path("featured").asBoolean(false),
                source.path("publishedAt").asText("")
            );
            posts.add(post);
        }
        
        return new SearchResult(total, posts);
    }

    /**
     * 发送 HTTP 请求到 Elasticsearch
     * 注意：需要重新构建请求以添加认证头，同时保留原始请求体
     */
    private HttpResponse<String> sendRequest(HttpRequest request) throws IOException, InterruptedException {
        var requestUri = request.uri();
        log.debugf("准备发送请求到：%s", requestUri);
        
        // 重新构建请求以添加认证头，同时保留原始请求体
        var builder = HttpRequest.newBuilder()
                .uri(request.uri())
                .timeout(request.timeout().orElse(java.time.Duration.ofSeconds(30)));
        
        // 复制原始请求头
        request.headers().map().forEach((name, values) -> {
            for (String value : values) {
                builder.header(name, value);
            }
        });
        
        // 添加认证头
        if (username != null && !username.isEmpty() && password != null) {
            String auth = username + ":" + password;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + encodedAuth);
            log.debugf("Adding authentication header, user: %s", username);
        } else {
            log.debug("No authentication configured, using anonymous connection");
        }
        
        // 复制请求方法和请求体
        String method = request.method();
        if (method.equals("GET")) {
            builder.GET();
        } else if (method.equals("PUT")) {
            request.bodyPublisher().ifPresentOrElse(
                    bodyPublisher -> builder.PUT(bodyPublisher),
                    () -> builder.PUT(HttpRequest.BodyPublishers.noBody())
            );
        } else if (method.equals("POST")) {
            request.bodyPublisher().ifPresentOrElse(
                    bodyPublisher -> builder.POST(bodyPublisher),
                    () -> builder.POST(HttpRequest.BodyPublishers.noBody())
            );
        } else if (method.equals("DELETE")) {
            builder.DELETE();
        } else {
            // 其他方法，使用通用方法设置
            request.bodyPublisher().ifPresentOrElse(
                    bodyPublisher -> builder.method(method, bodyPublisher),
                    () -> builder.method(method, HttpRequest.BodyPublishers.noBody())
            );
        }
        
        try {
            var httpRequest = builder.build();
            log.debugf("Sending HTTP request: %s %s", httpRequest.method(), httpRequest.uri());
            var response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            log.debugf("Response status code: %d", response.statusCode());
            return response;
        } catch (Exception e) {
            log.errorf(e, "Request failed: %s", requestUri);
            throw e;
        }
    }

    /**
     * Escape JSON string
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    /**
     * 搜索结果 DTO
     */
    public record SearchResult(long total, List<SearchedPost> posts) {}

    /**
     * 搜索到的文章 DTO
     */
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
}
