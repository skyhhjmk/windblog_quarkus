package com.biliwind.blog.service.elasticsearch;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.service.edge.NodeRoleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Elasticsearch index management service
 * Responsible for initializing ILM policies, index templates and indices, supports async initialization and service degradation
 */
@ApplicationScoped
public class ElasticsearchIndexService {

    private static final Logger log = Logger.getLogger(ElasticsearchIndexService.class);

    private final AtomicBoolean indexInitialized = new AtomicBoolean(false);
    @Inject
    ElasticsearchConnectionManager connectionManager;
    @Inject
    NodeRoleService nodeRoleService;
    @Inject
    ObjectMapper objectMapper;

    private static final String ILM_POLICY_NAME = "windblog-logs-policy";
    private static final String INDEX_TEMPLATE_NAME = "windblog-logs-template";
    private static final String INDEX_PATTERN = "windblog-logs-*";
    private static final String WRITE_ALIAS = "windblog-logs";
    private static final String INITIAL_INDEX = "windblog-logs-000001";

    @PostConstruct
    void postConstruct() {
        log.debug("[LOG INDEX] ElasticsearchIndexService @PostConstruct called - bean is being initialized");
    }

    void onStart(@Observes StartupEvent event) {
        if (nodeRoleService.isEdgeNode()) {
            log.info("当前节点是边缘节点，跳过 Elasticsearch 日志索引初始化");
            return;
        }

        log.debug("ElasticsearchIndexService startup initiated");
        log.debug("connectionManager status: " + (connectionManager != null ? "injected" : "NULL"));
        log.debug("Registering log index initialization callback...");
        try {
            connectionManager.onAvailable(() -> {
                log.debug("[LOG INDEX] Received Elasticsearch available notification, starting log index initialization...");
                try {
                    initializeIndex();
                } catch (Exception e) {
                    log.error("[LOG INDEX] Exception during initializeIndex()", e);
                }
            });
            log.debug("Callback registration completed");
        } catch (Exception e) {
            log.error("Exception during callback registration", e);
        }
    }

    /**
     * Initialize index (called when connection is available)
     */
    private void initializeIndex() {
        log.debug("[LOG INDEX] ====== initializeIndex() CALLED ======");
        log.debug("[LOG INDEX] Starting log index initialization...");
        log.debug("[LOG INDEX] Elasticsearch address: " + connectionManager.getCurrentSettings().hosts());
        log.debug("[LOG INDEX] connectionManager injected: " + (connectionManager != null ? "YES" : "NO"));

        int maxRetries = 3;
        int attempt = 0;

        while (attempt < maxRetries) {
            try {
                log.debug("[LOG INDEX] Attempt " + (attempt + 1) + "/" + maxRetries);
                log.debug("[LOG INDEX] Calling deleteLegacyTemplates()...");
                deleteLegacyTemplates();
                log.debug("[LOG INDEX] Legacy templates cleaned up");
                log.debug("[LOG INDEX] Calling createIlmPolicy()...");
                createIlmPolicy();
                log.debug("[LOG INDEX] ILM policy created");
                log.debug("[LOG INDEX] Calling createIndexTemplate()...");
                createIndexTemplate();
                log.debug("[LOG INDEX] Index template created");
                log.debug("[LOG INDEX] Calling createInitialIndex()...");
                createInitialIndex();
                log.debug("[LOG INDEX] Initial index created");
                indexInitialized.set(true);
                log.debug("[LOG INDEX] ====== initializeIndex() COMPLETED ======");
                log.debug("[LOG INDEX] Elasticsearch log index initialization completed");
                return;
            } catch (Exception e) {
                attempt++;
                log.error("[LOG INDEX] Failed to initialize log index (attempt " + attempt + "/"
                        + maxRetries + "): " + SensitiveMessageSanitizer.sanitize(e.getMessage()), e);
                log.error("[LOG INDEX] Exception type: " + e.getClass().getName());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("[LOG INDEX] Interrupted during retry sleep");
                        break;
                    }
                }
            }
        }

        log.warn("[LOG INDEX] Elasticsearch log index initialization failed after all retries, logs will be cached to local file");
    }

    /**
     * Delete legacy templates that might conflict with our configuration
     */
    private void deleteLegacyTemplates() throws Exception {
        log.info("Checking for legacy templates that might conflict...");

        String[] legacyTemplates = {
                "windblog-logs",
                "windblog-posts"
        };

        for (String templateName : legacyTemplates) {
            try {
                log.info("Checking legacy template: " + templateName);

                var checkRequest = HttpRequest.newBuilder()
                        .uri(connectionManager.resolveUri("/_index_template/" + templateName))
                        .GET()
                        .build();

                var checkResponse = connectionManager.sendRequest(checkRequest);

                if (checkResponse.statusCode() == 200) {
                    log.info("Found legacy template " + templateName + ", deleting it...");

                    var deleteRequest = HttpRequest.newBuilder()
                            .uri(connectionManager.resolveUri("/_index_template/" + templateName))
                            .DELETE()
                            .build();

                    var deleteResponse = connectionManager.sendRequest(deleteRequest);

                    if (deleteResponse.statusCode() == 200) {
                        log.info("Legacy template " + templateName + " deleted successfully");
                    }
                }
            } catch (Exception e) {
                log.warn("Error checking/deleting legacy template " + templateName + ": "
                        + SensitiveMessageSanitizer.sanitize(e.getMessage()));
            }
        }
    }

    /**
     * Check if service is available
     */
    public boolean isAvailable() {
        if (nodeRoleService.isEdgeNode()) {
            return false;
        }
        return connectionManager.isAvailable() && indexInitialized.get();
    }

    /**
     * Get service status
     */
    public ServiceStatus getServiceStatus() {
        if (nodeRoleService.isEdgeNode()) {
            return new ServiceStatus(false, false, "DISABLED");
        }
        return new ServiceStatus(
                connectionManager.isAvailable(),
                indexInitialized.get(),
                connectionManager.getHealthStatus().name()
        );
    }

    /**
     * Create ILM (Index Lifecycle Management) policy
     */
    private void createIlmPolicy() throws Exception {
        log.info("Creating ILM policy: " + ILM_POLICY_NAME);

        String policyJson = readResourceFile("elasticsearch/ilm-policy.json");

        var request = HttpRequest.newBuilder()
                .uri(connectionManager.resolveUri("/_ilm/policy/" + ILM_POLICY_NAME))
                .PUT(HttpRequest.BodyPublishers.ofString(policyJson))
                .header("Content-Type", "application/json")
                .build();

        var response = connectionManager.sendRequest(request);

        if (response.statusCode() == 200) {
            log.info("ILM policy created successfully");
        } else {
            log.error("ILM policy creation failed: " + SensitiveMessageSanitizer.sanitize(response.body()));
            throw new RuntimeException("ILM policy creation failed: " + response.statusCode());
        }
    }

    /**
     * Create index template
     */
    private void createIndexTemplate() throws Exception {
        log.info("Creating index template: " + INDEX_TEMPLATE_NAME);

        String templateJson = readResourceFile("elasticsearch/index-template.json");

        var request = HttpRequest.newBuilder()
                .uri(connectionManager.resolveUri("/_index_template/" + INDEX_TEMPLATE_NAME))
                .PUT(HttpRequest.BodyPublishers.ofString(templateJson))
                .header("Content-Type", "application/json")
                .build();

        var response = connectionManager.sendRequest(request);

        if (response.statusCode() == 200) {
            log.info("Index template created successfully");
        } else {
            log.error("Index template creation failed: " + SensitiveMessageSanitizer.sanitize(response.body()));
            throw new RuntimeException("Index template creation failed: " + response.statusCode());
        }
    }

    /**
     * Create initial index and write alias
     */
    private void createInitialIndex() throws Exception {
        log.info("Creating initial index: " + INDEX_PATTERN);

        if (writeAliasExists()) {
            log.info("日志写入别名已存在，跳过初始索引 PUT，直接校正别名状态");
            repairWriteAlias();
            return;
        }

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
                .uri(connectionManager.resolveUri("/" + INITIAL_INDEX))
                .PUT(HttpRequest.BodyPublishers.ofString(indexBody))
                .header("Content-Type", "application/json")
                .build();

        var response = connectionManager.sendRequest(request);

        if (response.statusCode() == 200) {
            log.info("Initial index created successfully");
        } else if (response.statusCode() == 400 || response.body().contains("more than one write index")) {
            log.warn("Initial index already exists or its write alias needs repair: "
                    + SensitiveMessageSanitizer.sanitize(response.body()));
        } else {
            log.error("Initial index creation failed: " + SensitiveMessageSanitizer.sanitize(response.body()));
            throw new RuntimeException("Initial index creation failed: " + response.statusCode());
        }

        repairWriteAlias();
    }

    /**
     * Reconcile a rollover alias after an old setup or an interrupted rollover.
     * Elasticsearch rejects writes when more than one index is marked as the write index.
     */
    public void repairWriteAlias() throws Exception {
        HttpResponse<String> aliasResponse = readWriteAlias();

        ElasticsearchWriteAliasRepair.AliasRepairPlan plan;
        if (aliasResponse.statusCode() == 404) {
            plan = ElasticsearchWriteAliasRepair.plan(
                    objectMapper,
                    "{}",
                    WRITE_ALIAS,
                    INITIAL_INDEX
            );
        } else if (aliasResponse.statusCode() == 200) {
            plan = ElasticsearchWriteAliasRepair.plan(
                    objectMapper,
                    aliasResponse.body(),
                    WRITE_ALIAS,
                    INITIAL_INDEX
            );
        } else {
            throw new IllegalStateException("读取 Elasticsearch 日志别名失败: " + aliasResponse.statusCode());
        }

        if (!plan.repairRequired()) {
            log.debug("Elasticsearch 日志写入别名已正确指向 " + plan.selectedWriteIndex());
            return;
        }

        String requestBody = ElasticsearchWriteAliasRepair.buildRequestBody(
                objectMapper,
                plan,
                WRITE_ALIAS
        );
        HttpRequest repairRequest = HttpRequest.newBuilder()
                .uri(connectionManager.resolveUri("/_aliases"))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> repairResponse = connectionManager.sendRequest(repairRequest);
        if (repairResponse.statusCode() != 200) {
            throw new IllegalStateException("修复 Elasticsearch 日志别名失败: "
                    + repairResponse.statusCode() + " "
                    + SensitiveMessageSanitizer.sanitize(repairResponse.body()));
        }
        log.warn("已修复 Elasticsearch 日志写入别名，当前写入索引: " + plan.selectedWriteIndex()
                + "；历史索引未删除");
    }

    private boolean writeAliasExists() throws Exception {
        HttpResponse<String> aliasResponse = readWriteAlias();
        if (aliasResponse.statusCode() == 200) {
            return true;
        }
        if (aliasResponse.statusCode() == 404) {
            return false;
        }
        throw new IllegalStateException("读取 Elasticsearch 日志别名失败: " + aliasResponse.statusCode());
    }

    private HttpResponse<String> readWriteAlias() throws Exception {
        HttpRequest aliasRequest = HttpRequest.newBuilder()
                .uri(connectionManager.resolveUri("/_alias/" + WRITE_ALIAS))
                .GET()
                .build();
        return connectionManager.sendRequest(aliasRequest);
    }

    /**
     * Read JSON configuration from resource file
     */
    private String readResourceFile(String resourceName) throws Exception {
        log.debug("[LOG INDEX] Reading resource file: " + resourceName);

        // Try different classloader approaches
        InputStream inputStream = null;

        // First try: current thread's context class loader
        inputStream = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(resourceName);

        // Second try: use class's class loader
        if (inputStream == null) {
            inputStream = getClass().getClassLoader().getResourceAsStream(resourceName);
        }

        // Third try: use class's resource path (with leading slash)
        if (inputStream == null) {
            inputStream = getClass().getResourceAsStream("/" + resourceName);
        }

        if (inputStream == null) {
            log.error("[LOG INDEX] Resource file not found: " + resourceName);
            log.error("[LOG INDEX] Tried multiple classloader approaches but all failed");
            throw new Exception("Resource file not found: " + resourceName + ". Please ensure the file exists in src/main/resources/" + resourceName);
        }

        try (BufferedReader reader = new BufferedReader(
                     new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            log.debug("[LOG INDEX] Resource file read successfully: " + resourceName);
            return content.toString();
        }
    }

    /**
     * Verify if ILM policy exists
     */
    public boolean verifyIlmPolicy() throws Exception {
        if (!connectionManager.isAvailable()) {
            throw new ElasticsearchUnavailableException("Elasticsearch service is currently unavailable");
        }

        var request = HttpRequest.newBuilder()
                .uri(connectionManager.resolveUri("/_ilm/policy/" + ILM_POLICY_NAME))
                .GET()
                .build();

        var response = connectionManager.sendRequest(request);
        return response.statusCode() == 200;
    }

    /**
     * Verify if index template exists
     */
    public boolean verifyIndexTemplate() throws Exception {
        if (!connectionManager.isAvailable()) {
            throw new ElasticsearchUnavailableException("Elasticsearch service is currently unavailable");
        }

        var request = HttpRequest.newBuilder()
                .uri(connectionManager.resolveUri("/_index_template/" + INDEX_TEMPLATE_NAME))
                .GET()
                .build();

        var response = connectionManager.sendRequest(request);
        return response.statusCode() == 200;
    }

    /**
     * Get ILM policy information
     */
    public String getIlmPolicyInfo() throws Exception {
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
    public String getIndexTemplateInfo() throws Exception {
        if (!connectionManager.isAvailable()) {
            throw new ElasticsearchUnavailableException("Elasticsearch service is currently unavailable");
        }

        var request = HttpRequest.newBuilder()
                .uri(connectionManager.resolveUri("/_index_template/" + INDEX_TEMPLATE_NAME))
                .GET()
                .build();

        var response = connectionManager.sendRequest(request);
        return response.body();
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
