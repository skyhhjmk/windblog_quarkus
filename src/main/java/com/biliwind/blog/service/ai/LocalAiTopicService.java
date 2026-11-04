package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiTopicPoolEntry;
import com.biliwind.blog.model.AiTopicPoolSeed;
import com.biliwind.blog.model.AiTopicPoolSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * WindBlog-owned AI topic pool. Topic discovery, review and draft linking are
 * all served by a configured AI provider (or polling group); no external
 * Codex Creator service is involved.
 */
@ApplicationScoped
public class LocalAiTopicService {

    private static final Set<String> RECOMMENDATIONS = Set.of("WRITE", "MONITOR", "IGNORE", "MANUAL");
    private static final Set<String> REVIEWABLE_STATUSES = Set.of("SUGGESTED", "APPROVED");

    @Inject
    ObjectMapper objectMapper;

    @Inject
    AiTopicRunService runService;

    @Inject
    AiTopicRunner runner;

    @Transactional
    public Object dispatch(String command, JsonNode payload, String actorId, String traceId) {
        return switch (command) {
            case "settings.read" -> settingsView(settings());
            case "settings.update" -> updateSettings(payload, actorId);
            case "seeds.list" -> Map.of("items", seedsView());
            case "seeds.upsert" -> upsertSeed(optionalId(payload, "id"), payload);
            case "seeds.delete" -> {
                deleteSeed(requiredId(payload, "id"));
                yield Map.of("success", true);
            }
            case "topics.list" -> listTopics(text(payload, "status", ""),
                    integer(payload, "page", 1), integer(payload, "pageSize", 20));
            case "topics.read" -> topicView(requiredId(payload, "id"));
            case "topics.import" -> importTopics(payload, actorId, traceId);
            case "topics.review" -> reviewTopic(requiredId(payload, "id"),
                    text(payload, "decision", ""), text(payload, "note", ""));
            case "topics.bindDraft" -> bindDraft(requiredId(payload, "topicId"), payload);
            case "runs.start" -> startRun(payload, actorId, traceId);
            case "runs.read" -> runService.view(requiredId(payload, "id"));
            case "runs.list" -> runService.list(integer(payload, "page", 1),
                    integer(payload, "pageSize", 20));
            default -> throw new IllegalArgumentException("不支持的话题命令: " + command);
        };
    }

    /**
     * Creates an asynchronous discovery run and returns immediately. The public
     * admin request must not stay open for the full provider generation time.
     */
    private Map<String, Object> startRun(JsonNode payload, String actorId, String traceId) {
        Map<String, Object> active = runService.activeRun();
        if (active != null) return active;

        AiTopicPoolSettings settings = settings();
        Long providerId = payload != null && payload.hasNonNull("providerId")
                ? payload.path("providerId").asLong() : null;
        int maxTopics = Math.max(1, Math.min(50,
                integer(payload, "maxTopics", settings.maxTopicsPerRun)));
        String language = text(payload, "language", "zh-CN");
        List<AiTopicDiscoveryService.Seed> seeds = parseSeeds(payload == null ? null : payload.get("seeds"));
        if (seeds.isEmpty()) {
            seeds = seedsFromDb(settings.maxSeedsPerRun);
        }
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException("话题种子为空，请先配置主题查询种子");
        }

        Map<String, Object> run = runService.create(providerId, seeds.size(), traceId);
        Long runId = requiredId(objectMapper.valueToTree(run), "id");
        runner.start(runId, providerId, seeds, maxTopics, language, actorId, traceId);
        return run;
    }

    private List<AiTopicDiscoveryService.Seed> parseSeeds(JsonNode values) {
        List<AiTopicDiscoveryService.Seed> seeds = new ArrayList<>();
        if (values == null || !values.isArray()) return seeds;
        for (JsonNode value : values) {
            long id = value.path("id").asLong(0);
            String query = text(value, "query", "");
            if (id <= 0 || query.isBlank()) continue;
            seeds.add(new AiTopicDiscoveryService.Seed(id,
                    text(value, "name", ""), query, text(value, "language", "zh-CN")));
        }
        return seeds;
    }

    private List<AiTopicDiscoveryService.Seed> seedsFromDb(int maxSeeds) {
        return AiTopicPoolSeed.<AiTopicPoolSeed>find("enabled = true order by sortOrder asc, id asc")
                .page(0, Math.max(1, maxSeeds)).list().stream()
                .map(seed -> new AiTopicDiscoveryService.Seed(
                        seed.id, seed.name, seed.query, seed.language))
                .toList();
    }

    private AiTopicPoolSettings settings() {
        AiTopicPoolSettings settings = AiTopicPoolSettings.findById(1L);
        if (settings == null) {
            settings = new AiTopicPoolSettings();
            settings.updatedAt = OffsetDateTime.now();
            settings.persist();
        }
        return settings;
    }

    private Map<String, Object> settingsView(AiTopicPoolSettings settings) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", settings.id);
        view.put("enabled", settings.enabled);
        view.put("intervalMinutes", settings.intervalMinutes);
        view.put("maxSeedsPerRun", settings.maxSeedsPerRun);
        view.put("maxTopicsPerRun", settings.maxTopicsPerRun);
        view.put("nextRunAt", null);
        view.put("lastRunAt", settings.lastRunAt);
        view.put("lastSuccessAt", settings.lastSuccessAt);
        view.put("lastError", settings.lastError == null ? "" : settings.lastError);
        view.put("promotionEnabled", settings.promotionEnabled);
        view.put("promotionMarkdown", settings.promotionMarkdown == null ? "" : settings.promotionMarkdown);
        view.put("updatedAt", settings.updatedAt);
        view.put("storage", "LOCAL");
        return view;
    }

    private Map<String, Object> updateSettings(JsonNode payload, String actorId) {
        AiTopicPoolSettings settings = settings();
        boolean enabled = booleanValue(payload, "enabled", settings.enabled);
        int interval = integer(payload, "intervalMinutes", settings.intervalMinutes);
        int maxSeeds = integer(payload, "maxSeedsPerRun", settings.maxSeedsPerRun);
        int maxTopics = integer(payload, "maxTopicsPerRun", settings.maxTopicsPerRun);
        boolean promotionEnabled = booleanValue(payload, "promotionEnabled", settings.promotionEnabled);
        String promotionMarkdown = nullableText(payload, "promotionMarkdown", settings.promotionMarkdown);
        if (interval < 1 || interval > 43200) {
            throw new IllegalArgumentException("intervalMinutes must be between 1 and 43200");
        }
        if (maxSeeds < 1 || maxSeeds > 50) {
            throw new IllegalArgumentException("maxSeedsPerRun must be between 1 and 50");
        }
        if (maxTopics < 1 || maxTopics > 100) {
            throw new IllegalArgumentException("maxTopicsPerRun must be between 1 and 100");
        }
        if (promotionMarkdown != null && promotionMarkdown.length() > 8_000) {
            throw new IllegalArgumentException("promotionMarkdown must be at most 8000 characters");
        }
        if (promotionEnabled && (promotionMarkdown == null || promotionMarkdown.isBlank())) {
            throw new IllegalArgumentException("promotionMarkdown is required when promotion is enabled");
        }
        settings.enabled = enabled;
        settings.intervalMinutes = interval;
        settings.maxSeedsPerRun = maxSeeds;
        settings.maxTopicsPerRun = maxTopics;
        settings.promotionEnabled = promotionEnabled;
        settings.promotionMarkdown = promotionMarkdown == null || promotionMarkdown.isBlank()
                ? null : promotionMarkdown.trim();
        settings.lastError = null;
        settings.updatedAt = OffsetDateTime.now();
        settings.persist();
        return settingsView(settings);
    }

    private List<Map<String, Object>> seedsView() {
        return AiTopicPoolSeed.<AiTopicPoolSeed>find("order by sortOrder asc, id asc")
                .list().stream().map(this::seedMap).toList();
    }

    private Map<String, Object> upsertSeed(Long id, JsonNode payload) {
        AiTopicPoolSeed seed = id == null ? null : AiTopicPoolSeed.findById(id);
        if (id != null && seed == null) {
            throw new IllegalArgumentException("topic query seed not found");
        }
        String name = text(payload, "name", seed == null ? null : seed.name);
        String query = text(payload, "query", seed == null ? null : seed.query);
        String language = text(payload, "language", seed == null ? "zh-CN" : seed.language);
        String region = nullableText(payload, "region", seed == null ? null : seed.region);
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("seed name is required and must be at most 120 characters");
        }
        if (query == null || query.isBlank() || query.length() > 1_000) {
            throw new IllegalArgumentException("seed query is required and must be at most 1000 characters");
        }
        if (region != null && region.length() > 64) {
            throw new IllegalArgumentException("seed region is too long");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (seed == null) {
            seed = new AiTopicPoolSeed();
            seed.createdAt = now;
        }
        seed.name = name.trim();
        seed.query = query.trim();
        seed.language = language == null || language.isBlank() ? "zh-CN" : language.trim();
        seed.region = region == null || region.isBlank() ? null : region.trim();
        seed.enabled = booleanValue(payload, "enabled", seed.enabled || id == null);
        seed.sortOrder = integer(payload, "sortOrder", seed.sortOrder);
        if (seed.sortOrder < 0 || seed.sortOrder > 1_000_000) {
            throw new IllegalArgumentException("invalid seed sortOrder");
        }
        seed.updatedAt = now;
        seed.persist();
        return seedMap(seed);
    }

    private void deleteSeed(Long id) {
        AiTopicPoolSeed seed = AiTopicPoolSeed.findById(id);
        if (seed == null) {
            throw new IllegalArgumentException("topic query seed not found");
        }
        seed.delete();
    }

    private Map<String, Object> listTopics(String status, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(100, pageSize));
        String normalizedStatus = status == null ? "" : status.trim();
        PanacheQuery<AiTopicPoolEntry> query = normalizedStatus.isBlank()
                ? AiTopicPoolEntry.<AiTopicPoolEntry>find("order by lastSeenAt desc, id desc")
                : "ASSIGNED".equalsIgnoreCase(normalizedStatus)
                        ? AiTopicPoolEntry.<AiTopicPoolEntry>find(
                                "status in ?1 order by lastSeenAt desc, id desc",
                                List.of("WRITING", "DRAFT_CREATED", "FAILED"))
                        : AiTopicPoolEntry.<AiTopicPoolEntry>find(
                                "status = ?1 order by lastSeenAt desc, id desc", normalizedStatus);
        long total = query.count();
        List<Map<String, Object>> items = query.page(Page.of(safePage - 1, safePageSize))
                .list().stream().map(this::topicMap).toList();
        return Map.of("items", items, "total", total, "page", safePage, "pageSize", safePageSize);
    }

    private Map<String, Object> topicView(Long id) {
        AiTopicPoolEntry topic = AiTopicPoolEntry.findById(id);
        if (topic == null) {
            throw new IllegalArgumentException("topic not found");
        }
        return topicMap(topic);
    }

    private Map<String, Object> importTopics(JsonNode payload, String actorId, String traceId) {
        JsonNode values = payload == null ? null : payload.get("topics");
        if (values == null || !values.isArray() || values.isEmpty() || values.size() > 50) {
            throw new IllegalArgumentException("topics must contain 1 to 50 items");
        }
        OffsetDateTime now = OffsetDateTime.now();
        int created = 0;
        int updated = 0;
        for (JsonNode value : values) {
            String title = text(value, "title", "").trim();
            if (title.isBlank() || title.length() > 160) {
                throw new IllegalArgumentException("topic title is required and must be at most 160 characters");
            }
            String rationale = nullableText(value, "rationale", null);
            if (rationale != null && rationale.length() > 2_000) {
                throw new IllegalArgumentException("topic rationale is too long");
            }
            String recommendation = nullableText(value, "recommendation", "MANUAL");
            recommendation = recommendation == null
                    ? "MANUAL" : recommendation.trim().toUpperCase(Locale.ROOT);
            if (!RECOMMENDATIONS.contains(recommendation)) {
                throw new IllegalArgumentException("unknown topic recommendation: " + recommendation);
            }
            String key = dedupeKey(title);
            AiTopicPoolEntry topic = AiTopicPoolEntry.find("dedupeKey", key).firstResult();
            if (topic == null) {
                topic = new AiTopicPoolEntry();
                topic.title = title;
                topic.dedupeKey = key;
                topic.status = "SUGGESTED";
                topic.createdAt = now;
                topic.occurrenceCount = 1;
                created++;
            } else {
                topic.title = title;
                topic.occurrenceCount++;
                updated++;
            }
            topic.rationale = rationale;
            topic.recommendation = recommendation;
            topic.lastSeenAt = now;
            topic.updatedAt = now;
            topic.source = mergeSource(topic.source, value, actorId, traceId, now);
            topic.persist();
        }
        return Map.of("created", created, "updated", updated, "total", values.size());
    }

    private String mergeSource(String existing, JsonNode value, String actorId,
                               String traceId, OffsetDateTime now) {
        ObjectNode source = readObject(existing);
        source.put("discoveredAt", now.toString());
        source.put("importedBy", actorId == null ? "" : actorId);
        source.put("traceId", traceId == null ? "" : traceId);
        String provider = text(value, "provider", "");
        if (!provider.isBlank()) source.put("provider", provider);
        String model = text(value, "model", "");
        if (!model.isBlank()) source.put("model", model);
        String language = text(value, "language", "");
        if (!language.isBlank()) source.put("language", language);
        JsonNode seedId = value.get("seedId");
        if (seedId != null && seedId.canConvertToLong()) {
            long id = seedId.asLong();
            source.put("seedId", id);
            ObjectNode seed = objectMapper.createObjectNode().put("id", id);
            ArrayNode seeds = objectMapper.createArrayNode();
            seeds.add(seed);
            source.set("seeds", seeds);
            source.set("primarySeed", seed.deepCopy());
            if (!source.has("queries")) source.set("queries", objectMapper.createArrayNode());
        }
        JsonNode keywords = value.get("keywords");
        if (keywords != null && keywords.isArray()) {
            ArrayNode target = source.putArray("keywords");
            keywords.forEach(item -> {
                if (item.isTextual() && target.size() < 12) target.add(item.asText());
            });
        }
        return source.toString();
    }

    private Map<String, Object> reviewTopic(Long id, String decision, String note) {
        AiTopicPoolEntry topic = AiTopicPoolEntry.findById(id);
        if (topic == null) {
            throw new IllegalArgumentException("topic not found");
        }
        String normalized = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
        if (!REVIEWABLE_STATUSES.contains(topic.status)) {
            throw new IllegalStateException("topic is no longer reviewable");
        }
        if (!Set.of("APPROVE", "DISMISS").contains(normalized)) {
            throw new IllegalArgumentException("decision must be APPROVE or DISMISS");
        }
        topic.status = "APPROVE".equals(normalized) ? "APPROVED" : "DISMISSED";
        topic.reviewNote = note == null || note.isBlank() ? null : note.trim();
        topic.reviewedAt = OffsetDateTime.now();
        topic.updatedAt = topic.reviewedAt;
        topic.persist();
        return topicMap(topic);
    }

    private Map<String, Object> bindDraft(Long topicId, JsonNode payload) {
        AiTopicPoolEntry topic = AiTopicPoolEntry.findById(topicId);
        if (topic == null) {
            throw new IllegalArgumentException("topic not found");
        }
        long postId = payload == null ? 0 : payload.path("postId").asLong(0);
        if (postId <= 0) {
            throw new IllegalArgumentException("postId is required");
        }
        OffsetDateTime now = OffsetDateTime.now();
        topic.postId = postId;
        topic.status = "DRAFT_CREATED";
        topic.lastSeenAt = now;
        topic.updatedAt = now;
        topic.persist();
        return Map.of("topicId", topicId, "postId", postId, "status", topic.status, "local", true);
    }

    private Map<String, Object> topicMap(AiTopicPoolEntry topic) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", topic.id);
        view.put("title", topic.title);
        view.put("rationale", topic.rationale == null ? "" : topic.rationale);
        view.put("recommendation", topic.recommendation);
        view.put("status", topic.status);
        view.put("source", readObject(topic.source));
        view.put("occurrenceCount", topic.occurrenceCount);
        view.put("createdAt", topic.createdAt);
        view.put("lastSeenAt", topic.lastSeenAt);
        view.put("reviewedAt", topic.reviewedAt);
        view.put("reviewNote", topic.reviewNote == null ? "" : topic.reviewNote);
        view.put("updatedAt", topic.updatedAt);
        view.put("postId", topic.postId);
        view.put("articleJobStatus", "DRAFT_CREATED".equals(topic.status) ? "DRAFT_CREATED" : null);
        view.put("external", true);
        return view;
    }

    private Map<String, Object> seedMap(AiTopicPoolSeed seed) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", seed.id);
        view.put("name", seed.name);
        view.put("query", seed.query);
        view.put("language", seed.language);
        view.put("region", seed.region);
        view.put("enabled", seed.enabled);
        view.put("sortOrder", seed.sortOrder);
        view.put("lastUsedAt", seed.lastUsedAt);
        view.put("createdAt", seed.createdAt);
        view.put("updatedAt", seed.updatedAt);
        return view;
    }

    private ObjectNode readObject(String value) {
        try {
            JsonNode parsed = value == null || value.isBlank() ? null : objectMapper.readTree(value);
            return parsed != null && parsed.isObject()
                    ? (ObjectNode) parsed : objectMapper.createObjectNode();
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private static String dedupeKey(String title) {
        String normalized = title.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", "");
        if (normalized.isBlank()) normalized = title;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
        }
    }

    private static Long optionalId(JsonNode payload, String field) {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null || value.isNull()) return null;
        return requiredId(payload, field);
    }

    private static Long requiredId(JsonNode payload, String field) {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null || !value.canConvertToLong() || value.asLong() <= 0) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.asLong();
    }

    private static int integer(JsonNode payload, String field, int fallback) {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.canConvertToInt()) throw new IllegalArgumentException(field + " must be an integer");
        return value.asInt();
    }

    private static String text(JsonNode payload, String field, String fallback) {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be text");
        return value.asText();
    }

    private static String nullableText(JsonNode payload, String field, String fallback) {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be text");
        return value.asText();
    }

    private static boolean booleanValue(JsonNode payload, String field, boolean fallback) {
        JsonNode value = payload == null ? null : payload.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isBoolean()) throw new IllegalArgumentException(field + " must be a boolean");
        return value.asBoolean();
    }
}
