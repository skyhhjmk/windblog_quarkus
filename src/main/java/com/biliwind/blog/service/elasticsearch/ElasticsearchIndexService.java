package com.biliwind.blog.service.elasticsearch;

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
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Elasticsearch 索引管理服务
 * 负责初始化 ILM 策略、索引模板和索引
 */
@ApplicationScoped
public class ElasticsearchIndexService {

    private static final Logger log = Logger.getLogger(ElasticsearchIndexService.class);

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

    private static final String ILM_POLICY_NAME = "windblog-logs-policy";
    private static final String INDEX_TEMPLATE_NAME = "windblog-logs-template";
    private static final String INDEX_PATTERN = "windblog-logs-*";
    private static final String WRITE_ALIAS = "windblog-logs";

    /**
     * Initialize Elasticsearch index on application startup
     */
    void onStart(@Observes StartupEvent event) {
        log.info("Starting Elasticsearch index initialization...");

        try {
            waitForElasticsearch();
            createIlmPolicy();
            createIndexTemplate();
            createInitialIndex();

            log.info("Elasticsearch index initialization completed");
        } catch (Exception e) {
            log.error("Elasticsearch index initialization failed", e);
        }
    }

    /**
     * Wait for Elasticsearch service to be ready
     */
    private void waitForElasticsearch() throws InterruptedException {
        log.info("Waiting for Elasticsearch to start...");
        log.infof("Elasticsearch host: %s", elasticsearchHosts);
        log.infof("SSL certificate verification: %s", sslTrustAll ? "disabled" : "enabled");
        
        if (username != null && !username.isEmpty()) {
            log.infof("Authentication user: %s", username);
        } else {
            log.info("Authentication: none");
        }
        
        if (elasticsearchHosts == null || elasticsearchHosts.trim().isEmpty()) {
            log.error("Elasticsearch host is empty! Please check configuration quarkus.log.handler.elasticsearch.hosts");
            throw new RuntimeException("Elasticsearch host not configured");
        }
        
        int maxAttempts = 30;
        int attempt = 0;
        
        while (attempt < maxAttempts) {
            try {
                var uriString = elasticsearchHosts + "/_cluster/health";
                log.debugf("Building URI: %s", uriString);
                
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(uriString))
                        .timeout(java.time.Duration.ofSeconds(5))
                        .GET()
                        .build();
                
                log.debug("Sending health check request...");
                var response = sendRequest(request);
                log.debugf("Response status code: %d", response.statusCode());
                
                if (response.statusCode() == 200) {
                    log.info("Elasticsearch is ready");
                    return;
                } else {
                    log.warnf("Elasticsearch returned non-200 status code: %d", response.statusCode());
                }
            } catch (java.net.ConnectException e) {
                log.debugf("Connection failed: %s", e.getMessage());
            } catch (Exception e) {
                log.debugf("Health check failed: %s", e.getMessage());
            }
            
            attempt++;
            if (attempt < maxAttempts) {
                log.infof("Waiting for Elasticsearch... (attempt %d/%d)", attempt, maxAttempts);
                TimeUnit.SECONDS.sleep(5);
            }
        }
        
        throw new RuntimeException("Elasticsearch startup timeout, attempted " + maxAttempts + " times");
    }

    /**
     * Create ILM (Index Lifecycle Management) policy
     */
    private void createIlmPolicy() throws IOException, InterruptedException {
        log.info("Creating ILM policy: " + ILM_POLICY_NAME);

        String policyJson = readResourceFile("elasticsearch/ilm-policy.json");

        var request = HttpRequest.newBuilder()
                .uri(URI.create(elasticsearchHosts + "/_ilm/policy/" + ILM_POLICY_NAME))
                .PUT(HttpRequest.BodyPublishers.ofString(policyJson))
                .header("Content-Type", "application/json")
                .build();

        var response = sendRequest(request);

        if (response.statusCode() == 200) {
            log.info("ILM policy created successfully");
        } else {
            log.error("ILM policy creation failed: " + response.body());
            throw new RuntimeException("ILM policy creation failed: " + response.statusCode());
        }
    }

    /**
     * Create index template
     */
    private void createIndexTemplate() throws IOException, InterruptedException {
        log.info("Creating index template: " + INDEX_TEMPLATE_NAME);

        String templateJson = readResourceFile("elasticsearch/index-template.json");

        var request = HttpRequest.newBuilder()
                .uri(URI.create(elasticsearchHosts + "/_index_template/" + INDEX_TEMPLATE_NAME))
                .PUT(HttpRequest.BodyPublishers.ofString(templateJson))
                .header("Content-Type", "application/json")
                .build();

        var response = sendRequest(request);

        if (response.statusCode() == 200) {
            log.info("Index template created successfully");
        } else {
            log.error("Index template creation failed: " + response.body());
            throw new RuntimeException("Index template creation failed: " + response.statusCode());
        }
    }

    /**
     * Create initial index and write alias
     */
    private void createInitialIndex() throws IOException, InterruptedException {
        log.info("Creating initial index: " + INDEX_PATTERN);

        String indexBody = """
                {
                  "aliases": {
                    "%s": {
                      "is_write_index": true
                    }
                  }
                }
                """.formatted(WRITE_ALIAS);

        var request = HttpRequest.newBuilder()
                .uri(URI.create(elasticsearchHosts + "/windblog-logs-000001"))
                .PUT(HttpRequest.BodyPublishers.ofString(indexBody))
                .header("Content-Type", "application/json")
                .build();

        var response = sendRequest(request);

        if (response.statusCode() == 200) {
            log.info("Initial index created successfully");
        } else if (response.statusCode() == 400) {
            log.warn("Initial index may already exist: " + response.body());
        } else {
            log.error("Initial index creation failed: " + response.body());
            throw new RuntimeException("Initial index creation failed: " + response.statusCode());
        }
    }

    /**
     * 从资源文件读取 JSON 配置
     */
    private String readResourceFile(String resourceName) throws IOException {
        try (InputStream inputStream = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(resourceName);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            return content.toString();
        }
    }

    /**
     * 发送 HTTP 请求到 Elasticsearch
     * 注意：需要重新构建请求以添加认证头，同时保留原始请求体
     */
    private HttpResponse<String> sendRequest(HttpRequest request) throws IOException, InterruptedException {
        var requestUri = request.uri();
        log.debugf("Preparing to send request to: %s", requestUri);
        
        // Rebuild request to add authentication header while preserving original body
        var builder = HttpRequest.newBuilder()
                .uri(request.uri())
                .timeout(request.timeout().orElse(java.time.Duration.ofSeconds(30)));
        
        // Copy original headers
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
        
        // Copy request method and body
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
            // Other methods, use generic method setting
            request.bodyPublisher().ifPresentOrElse(
                    bodyPublisher -> builder.method(method, bodyPublisher),
                    () -> builder.method(method, HttpRequest.BodyPublishers.noBody())
            );
        }
        
        try {
            var httpRequest = builder.build();
            log.debugf("Sending HTTP request: %s %s", httpRequest.method(), httpRequest.uri());
            return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.errorf(e, "Request failed: %s", requestUri);
            throw e;
        }
    }

    /**
     * 验证 ILM 策略是否存在
     */
    public boolean verifyIlmPolicy() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(elasticsearchHosts + "/_ilm/policy/" + ILM_POLICY_NAME))
                .GET()
                .build();

        var response = sendRequest(request);
        return response.statusCode() == 200;
    }

    /**
     * 验证索引模板是否存在
     */
    public boolean verifyIndexTemplate() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(elasticsearchHosts + "/_index_template/" + INDEX_TEMPLATE_NAME))
                .GET()
                .build();

        var response = sendRequest(request);
        return response.statusCode() == 200;
    }

    /**
     * 获取 ILM 策略信息
     */
    public String getIlmPolicyInfo() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(elasticsearchHosts + "/_ilm/policy/" + ILM_POLICY_NAME))
                .GET()
                .build();

        var response = sendRequest(request);
        return response.body();
    }

    /**
     * 获取索引模板信息
     */
    public String getIndexTemplateInfo() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(elasticsearchHosts + "/_index_template/" + INDEX_TEMPLATE_NAME))
                .GET()
                .build();

        var response = sendRequest(request);
        return response.body();
    }
}
