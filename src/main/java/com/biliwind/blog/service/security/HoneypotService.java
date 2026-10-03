package com.biliwind.blog.service.security;

import com.biliwind.blog.model.HoneypotEvent;
import com.biliwind.blog.model.HoneypotEventSample;
import com.biliwind.blog.model.HoneypotRule;
import com.biliwind.blog.service.AuditService;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@ApplicationScoped
public class HoneypotService {

    public enum RuleAction { OBSERVE, BLOCK }

    public record RuleDefinition(String key, String label, Pattern pattern) { }

    private static final List<RuleDefinition> DEFINITIONS = List.of(
            new RuleDefinition("sql_injection", "SQL 注入", Pattern.compile(
                    "(?is)(?:['\\\"]?\\s*\\b(?:or|and)\\b\\s+\\d+\\s*=\\s*\\d+|union\\s+(?:all\\s+)?select|(?:sleep|benchmark)\\s*\\()")),
            new RuleDefinition("xss", "跨站脚本注入", Pattern.compile(
                    "(?is)<\\s*script\\b|javascript\\s*:|on(?:error|load|click)\\s*=|<\\s*svg\\b")),
            new RuleDefinition("path_traversal", "目录穿越", Pattern.compile(
                    "(?i)(?:\\.\\.[/\\\\]|%2e%2e(?:%2f|%5c|[/\\\\]))")),
            new RuleDefinition("command_injection", "命令注入", Pattern.compile(
                    "(?i)(?:;|\\||&&|\\$\\()\\s*(?:whoami|id|cat|curl|wget|nc|bash|sh|cmd|powershell)\\b")),
            new RuleDefinition("template_injection", "模板注入", Pattern.compile(
                    "(?is)(?:\\$\\{\\s*\\d+\\s*\\*|#\\{\\s*T\\(|\\{\\{\\s*['\\\"]?\\s*7\\s*\\*|<%\\s*=)")),
            new RuleDefinition("scanner_probe", "扫描器探测", Pattern.compile("(?!)")),
            new RuleDefinition("honeypot_paths", "诱捕路径访问", Pattern.compile("(?!)"))
    );
    private static final Map<String, RuleDefinition> DEFINITIONS_BY_KEY = definitionsByKey();
    private static final List<String> DECOY_PATHS = List.of(
            "/.env", "/.git/config", "/wp-login.php", "/wp-admin", "/xmlrpc.php",
            "/phpmyadmin", "/phpmyadmin/", "/actuator/env", "/vendor/phpunit/phpunit/src/util/php/eval-stdin.php");
    private static final Pattern SCANNER_PATH = Pattern.compile(
            "(?i)(?:/wp-[^/]*|/xmlrpc\\.php|/phpmyadmin(?:/|$)|/\\.env(?:$|/)|/\\.git(?:/|$)|/actuator/env|/vendor/phpunit/)");

    private volatile Map<String, RuleState> rules = defaultRuleStates();

    @Inject
    AuditService auditService;

    @Inject
    EntityManager entityManager;

    @Transactional
    void loadRules(@Observes StartupEvent ignored) {
        refreshRuleCache();
    }

    @Scheduled(every = "30s", identity = "honeypot-rule-refresh")
    @Transactional
    void refreshRules() {
        refreshRuleCache();
    }

    private void refreshRuleCache() {
        Map<String, RuleState> refreshed = defaultRuleStates();
        for (HoneypotRule rule : HoneypotRule.<HoneypotRule>listAll()) {
            refreshed.put(rule.ruleKey, new RuleState(rule.enabled, parseAction(rule.action)));
        }
        rules = Map.copyOf(refreshed);
    }

    public List<RuleView> listRules() {
        return DEFINITIONS.stream().map(definition -> {
            RuleState state = state(definition.key());
            return new RuleView(definition.key(), definition.label(), state.enabled(), state.action().name());
        }).toList();
    }

    @Transactional
    public RuleView updateRule(String key, boolean enabled, String action) {
        RuleDefinition definition = DEFINITIONS_BY_KEY.get(key);
        if (definition == null) {
            throw new IllegalArgumentException("未知的蜜罐规则");
        }
        RuleAction ruleAction = parseAction(action);
        HoneypotRule rule = HoneypotRule.findById(key);
        if (rule == null) {
            rule = new HoneypotRule();
            rule.ruleKey = key;
        }
        String oldValue = state(key).enabled() + ":" + state(key).action().name();
        rule.enabled = enabled;
        rule.action = ruleAction.name();
        rule.updatedAt = OffsetDateTime.now();
        rule.persist();
        Map<String, RuleState> updated = new LinkedHashMap<>(rules);
        updated.put(key, new RuleState(enabled, ruleAction));
        rules = Map.copyOf(updated);
        auditService.log("honeypot_rule", key, "update", Map.of("state", oldValue),
                Map.of("enabled", enabled, "action", ruleAction.name()));
        return new RuleView(key, definition.label(), enabled, ruleAction.name());
    }

    public Detection detect(String method, String requestUri, Map<String, List<String>> headers, byte[] bodySample) {
        String path = requestUri == null ? "/" : requestUri;
        StringBuilder searchable = new StringBuilder(path).append('\n');
        if (headers != null) {
            headers.forEach((name, values) -> {
                searchable.append(name).append(':');
                if (values != null) values.forEach(value -> searchable.append(value).append('\n'));
            });
        }
        if (bodySample != null && bodySample.length > 0) {
            searchable.append('\n').append(new String(bodySample, java.nio.charset.StandardCharsets.UTF_8));
        }
        String normalized = decodeForDetection(searchable.toString());
        String normalizedPath = decodeForDetection(pathOnly(path));

        List<String> matched = new ArrayList<>();
        for (RuleDefinition definition : DEFINITIONS) {
            RuleState state = state(definition.key());
            if (!state.enabled()) continue;
            boolean match = switch (definition.key()) {
                case "scanner_probe" -> SCANNER_PATH.matcher(normalizedPath).find();
                case "honeypot_paths" -> DECOY_PATHS.stream().anyMatch(decoy -> normalizedPath.equalsIgnoreCase(decoy));
                default -> definition.pattern().matcher(normalized).find();
            };
            if (match) matched.add(definition.key());
        }
        boolean blocked = matched.stream().anyMatch(key -> state(key).action() == RuleAction.BLOCK);
        return new Detection(List.copyOf(matched), blocked);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void record(String clientIp, String remoteIp, String method, String requestUri, String userAgent,
                       List<String> matchedRules, String action, Map<String, List<String>> headers,
                       byte[] bodySample, boolean bodyTruncated) {
        HoneypotEvent event = new HoneypotEvent();
        event.clientIp = safe(clientIp, "unknown");
        event.remoteIp = remoteIp;
        event.method = safe(method, "UNKNOWN");
        event.requestUri = safe(requestUri, "/");
        event.userAgent = userAgent;
        event.matchedRules = matchedRules;
        event.action = action;
        event.bodyTruncated = bodyTruncated;
        event.bodyBytes = bodySample == null ? 0 : bodySample.length;
        event.createdAt = OffsetDateTime.now();
        event.persist();
        entityManager.flush();
        HoneypotEventSample sample = new HoneypotEventSample();
        sample.eventId = event.id;
        sample.requestHeaders = headers == null ? new LinkedHashMap<>() : headers;
        sample.bodySample = bodySample;
        sample.persist();
    }

    @Scheduled(every = "1h", identity = "honeypot-event-retention")
    @Transactional
    void purgeExpiredEvents() {
        HoneypotEvent.delete("createdAt < ?1", OffsetDateTime.now().minusDays(30));
    }

    private RuleState state(String key) {
        return rules.getOrDefault(key, new RuleState(true, RuleAction.OBSERVE));
    }

    private RuleAction parseAction(String action) {
        if (action == null || action.isBlank()) return RuleAction.OBSERVE;
        try {
            return RuleAction.valueOf(action.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("规则动作必须是 OBSERVE 或 BLOCK");
        }
    }

    private String decodeForDetection(String value) {
        String result = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < 2; i++) {
            try {
                String decoded = java.net.URLDecoder.decode(result, java.nio.charset.StandardCharsets.UTF_8);
                if (decoded.equals(result)) break;
                result = decoded;
            } catch (IllegalArgumentException exception) {
                break;
            }
        }
        return result.replaceAll("(?s)/\\*.*?\\*/", " ");
    }

    private String pathOnly(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) return "/";
        try {
            java.net.URI uri = java.net.URI.create(requestUri);
            if (uri.isAbsolute() && uri.getRawPath() != null) return uri.getRawPath();
        } catch (IllegalArgumentException ignored) {
            // A malformed request target is still inspected as supplied below.
        }
        int query = requestUri.indexOf('?');
        return query < 0 ? requestUri : requestUri.substring(0, query);
    }

    private static Map<String, RuleDefinition> definitionsByKey() {
        Map<String, RuleDefinition> result = new LinkedHashMap<>();
        for (RuleDefinition definition : DEFINITIONS) result.put(definition.key(), definition);
        return Map.copyOf(result);
    }

    private static Map<String, RuleState> defaultRuleStates() {
        Map<String, RuleState> result = new LinkedHashMap<>();
        for (RuleDefinition definition : DEFINITIONS) {
            result.put(definition.key(), new RuleState(true, RuleAction.OBSERVE));
        }
        return result;
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record RuleState(boolean enabled, RuleAction action) { }
    @io.quarkus.runtime.annotations.RegisterForReflection
    public record RuleView(String key, String label, boolean enabled, String action) { }
    public record Detection(List<String> matchedRules, boolean blocked) { }
}
