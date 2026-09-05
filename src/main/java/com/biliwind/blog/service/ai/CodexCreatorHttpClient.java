package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProviderConfig;
import com.biliwind.blog.service.CodexCreatorIntegrationEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Signed, private-service HTTP boundary. It never reuses an admin JWT. */
@ApplicationScoped
public class CodexCreatorHttpClient {
    private static final String CLIENT_ID = "windblog";

    @Inject
    ObjectMapper objectMapper;

    @Inject
    CodexCreatorConnectionService connectionService;

    @ConfigProperty(name = "windblog.ai.codex-creator.endpoint", defaultValue = "http://codex-creator:8681")
    String configuredEndpoint;

    @ConfigProperty(name = "windblog.ai.codex-creator.shared-secret", defaultValue = "")
    Optional<String> configuredSecret;

    @ConfigProperty(name = "windblog.ai.codex-creator.timeout", defaultValue = "120S")
    Duration timeout;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public CompletionStage<JsonNode> infer(AiProviderConfig config, String operation, String profileId,
                                           Object input, String idempotencyKey, String traceId) {
        return inferDetailed(config, operation, profileId, input, idempotencyKey, traceId)
                .thenApply(CodexCreatorInference::output);
    }

    public CompletionStage<CodexCreatorInference> inferDetailed(AiProviderConfig config, String operation, String profileId,
                                                                 Object input, String idempotencyKey, String traceId) {
        String endpoint = endpoint(config);
        String secret = secret(config);
        if (secret.isBlank()) return CompletableFuture.failedFuture(new IllegalStateException(
                "Codex Creator shared secret is not configured"));
        Map<String, Object> body = Map.of("operation", operation, "profileId", profileId,
                "input", input == null ? Map.of() : input, "idempotencyKey", idempotencyKey,
                "traceId", traceId, "promptVersion", "1");
        return send(endpoint + "/api/v1/runtime/infer", body, secret)
                .thenApply(response -> new CodexCreatorInference(response.path("output"),
                        response.hasNonNull("taskId") ? response.path("taskId").asText() : null,
                        response.path("usage"), response.path("provenance")));
    }

    public CompletionStage<Void> publishEvent(CodexCreatorIntegrationEvent event) {
        CodexCreatorConnectionService.ConnectionSettings connection = connectionService.current();
        String secret = connection.sharedSecret();
        if (secret.isBlank()) return CompletableFuture.failedFuture(new IllegalStateException(
                "Codex Creator shared secret is not configured"));
        return send(connection.endpoint() + "/api/internal/integrations/windblog/events", event, secret)
                .thenApply(ignored -> null);
    }

    /** Calls the HMAC-only topic/article command boundary; the browser never sees this credential. */
    public CompletionStage<JsonNode> topicCommand(String command, Object payload,
                                                   String actorId, String traceId) {
        CodexCreatorConnectionService.ConnectionSettings connection = connectionService.current();
        String secret = connection.sharedSecret();
        if (secret.isBlank()) return CompletableFuture.failedFuture(new IllegalStateException(
                "Codex Creator shared secret is not configured"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command", command);
        body.put("payload", payload == null ? Map.of() : payload);
        body.put("actorId", actorId == null || actorId.isBlank() ? "WIND_BLOG" : actorId);
        body.put("traceId", traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId);
        return send(connection.endpoint() + "/api/internal/integrations/windblog/topic-automation", body, secret);
    }

    public CompletionStage<JsonNode> status() {
        try {
            CodexCreatorConnectionService.ConnectionSettings connection = connectionService.current();
            HttpRequest request = HttpRequest.newBuilder(URI.create(connection.endpoint() + "/internal/health"))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenCompose(response -> {
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            return CompletableFuture.failedFuture(new IllegalStateException(
                                    "Codex Creator health returned HTTP " + response.statusCode()));
                        }
                        try { return CompletableFuture.completedFuture(objectMapper.readTree(response.body())); }
                        catch (Exception exception) { return CompletableFuture.failedFuture(exception); }
                    });
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private CompletionStage<JsonNode> send(String url, Object body, String secret) {
        try {
            String serialized = objectMapper.writeValueAsString(body);
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString();
            String digest = digest(serialized);
            String signature = sign(secret, timestamp, nonce, digest);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("X-Codex-Client-Id", CLIENT_ID)
                    .header("X-Codex-Timestamp", timestamp)
                    .header("X-Codex-Nonce", nonce)
                    .header("X-Codex-Body-SHA256", digest)
                    .header("X-Codex-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(serialized, StandardCharsets.UTF_8))
                    .build();
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenCompose(response -> {
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            return CompletableFuture.failedFuture(new IllegalStateException(
                                    "Codex Creator returned HTTP " + response.statusCode()
                                            + codexErrorDetail(response.body())));
                        }
                        try {
                            return CompletableFuture.completedFuture(objectMapper.readTree(
                                    response.body() == null || response.body().isBlank() ? "{}" : response.body()));
                        } catch (Exception exception) {
                            return CompletableFuture.failedFuture(new IllegalStateException(
                                    "invalid Codex Creator response", exception));
                        }
                    });
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private String codexErrorDetail(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonNode value = objectMapper.readTree(body);
            String message = value.path("message").asText("").trim();
            if (message.isBlank()) message = value.path("error").asText("").trim();
            if (message.isBlank()) return "";
            return ": " + message.substring(0, Math.min(message.length(), 500));
        } catch (Exception ignored) {
            return "";
        }
    }

    private String endpoint(AiProviderConfig config) {
        String endpoint = config != null && config.endpoint != null && !config.endpoint.isBlank()
                ? config.endpoint : configuredEndpoint;
        return baseEndpoint(endpoint);
    }

    private static String baseEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return "http://codex-creator:8681";
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }

    private String secret(AiProviderConfig config) {
        if (config != null && config.apiKey != null && !config.apiKey.isBlank()) return config.apiKey;
        return configuredSecret.orElse("");
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot digest Codex Creator request", exception);
        }
    }

    private static String sign(String secret, String timestamp, String nonce, String digest) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String canonical = timestamp + "\n" + nonce + "\n" + digest + "\n" + CLIENT_ID;
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot sign Codex Creator request", exception);
        }
    }
}
