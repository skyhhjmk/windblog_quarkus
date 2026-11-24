package com.biliwind.blog.service;

import com.biliwind.blog.common.CacheService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP 归属地查询服务，基于 ipnest community 接口做标准化解析、本地/内网短路与多级缓存。
 */
@ApplicationScoped
public class IpLocationService {

    private static final Logger LOG = Logger.getLogger(IpLocationService.class);
    private static final String DEFAULT_BASE_URL = "https://ipnest.mliev.com/api/v1/lookup";
    private static final String DEFAULT_DATASET = "community";
    private static final String LOCAL_NETWORK_LABEL = "本地/内网";
    private static final int MAX_IP_LENGTH = 64;
    private static final int MAX_LOCATION_LENGTH = 255;
    private static final int MAX_MEMORY_CACHE_SIZE = 2048;
    private static final Duration SUCCESS_CACHE_TTL = Duration.ofHours(12);
    private static final Duration NEGATIVE_CACHE_TTL = Duration.ofMinutes(3);

    private final Map<String, CachedEntry> memoryCache = new ConcurrentHashMap<>();
    private volatile HttpClient httpClient;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    CacheService cacheService;

    @ConfigProperty(name = "windblog.ip-lookup.enabled", defaultValue = "true")
    boolean enabled = true;

    @ConfigProperty(name = "windblog.ip-lookup.base-url", defaultValue = DEFAULT_BASE_URL)
    String baseUrl = DEFAULT_BASE_URL;

    @ConfigProperty(name = "windblog.ip-lookup.dataset", defaultValue = DEFAULT_DATASET)
    String dataset = DEFAULT_DATASET;

    @ConfigProperty(name = "windblog.ip-lookup.timeout-ms", defaultValue = "3000")
    long timeoutMs = 3000L;

    public IpLocationService() {
    }

    IpLocationService(ObjectMapper objectMapper, CacheService cacheService,
                      boolean enabled, String baseUrl, String dataset, long timeoutMs) {
        this.objectMapper = objectMapper;
        this.cacheService = cacheService;
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.dataset = dataset;
        this.timeoutMs = timeoutMs;
    }

    /**
     * 规范化客户端 IP 字面量，剔除引号、端口和 IPv6 方括号，非法值返回 null。
     */
    public String normalizeIp(String rawIp) {
        if (rawIp == null || rawIp.isBlank()) {
            return null;
        }
        String candidate = rawIp.trim();
        if (candidate.length() >= 2 && candidate.startsWith("\"") && candidate.endsWith("\"")) {
            candidate = candidate.substring(1, candidate.length() - 1).trim();
        }
        if ("unknown".equalsIgnoreCase(candidate)) {
            return null;
        }
        if (candidate.startsWith("[")) {
            int closeIdx = candidate.indexOf(']');
            if (closeIdx <= 1) {
                return null;
            }
            candidate = candidate.substring(1, closeIdx).trim();
        } else {
            int colonIdx = candidate.indexOf(':');
            if (colonIdx > 0 && colonIdx == candidate.lastIndexOf(':') && candidate.indexOf('.') > 0) {
                candidate = candidate.substring(0, colonIdx).trim();
            }
        }
        int zoneIdx = candidate.indexOf('%');
        if (zoneIdx > 0) {
            candidate = candidate.substring(0, zoneIdx).trim();
        }
        if (candidate.isEmpty() || candidate.length() > MAX_IP_LENGTH || !isAddressLiteral(candidate)) {
            return null;
        }
        InetAddress parsed = parseLiteralAddress(candidate);
        if (parsed == null) {
            return null;
        }
        String hostAddress = parsed.getHostAddress();
        if (hostAddress == null || hostAddress.length() > MAX_IP_LENGTH) {
            return null;
        }
        return hostAddress;
    }

    /**
     * 查询 IP 归属地，保证永不抛出异常。
     */
    public IpLocationResult lookup(String rawIp) {
        String normalizedIp = normalizeIp(rawIp);
        if (normalizedIp == null) {
            return IpLocationResult.empty(null);
        }

        InetAddress address = parseLiteralAddress(normalizedIp);
        if (address != null && isSpecialOrPrivateIp(address)) {
            return new IpLocationResult(
                    normalizedIp, false, "special",
                    null, null, null, null, null, null,
                    null, LOCAL_NETWORK_LABEL);
        }

        long now = System.currentTimeMillis();
        CachedEntry memHit = memoryCache.get(normalizedIp);
        if (memHit != null) {
            if (memHit.expiresAtMillis() > now) {
                return memHit.result();
            }
            memoryCache.remove(normalizedIp, memHit);
        }

        if (cacheService != null) {
            Optional<IpLocationResult> redisHit = cacheService.get(
                    CacheService.Keys.ipLocation(normalizedIp), IpLocationResult.class);
            if (redisHit.isPresent()) {
                IpLocationResult cached = redisHit.get();
                putMemoryCache(normalizedIp, cached, SUCCESS_CACHE_TTL);
                return cached;
            }
        }

        if (!enabled) {
            return IpLocationResult.empty(normalizedIp);
        }

        try {
            String rawJson = fetchLookupJson(normalizedIp);
            if (rawJson == null || rawJson.isBlank()) {
                IpLocationResult fallback = IpLocationResult.empty(normalizedIp);
                putMemoryCache(normalizedIp, fallback, NEGATIVE_CACHE_TTL);
                return fallback;
            }
            IpLocationResult parsed = parseLookupResponse(normalizedIp, rawJson);
            if (parsed.found() || "special".equalsIgnoreCase(parsed.scope())) {
                putMemoryCache(normalizedIp, parsed, SUCCESS_CACHE_TTL);
                if (cacheService != null) {
                    cacheService.set(CacheService.Keys.ipLocation(normalizedIp), parsed, SUCCESS_CACHE_TTL);
                }
            } else {
                putMemoryCache(normalizedIp, parsed, NEGATIVE_CACHE_TTL);
            }
            return parsed;
        } catch (Exception ex) {
            LOG.debugf("IP 归属地查询失败 (%s): %s", normalizedIp, ex.getMessage());
            IpLocationResult fallback = IpLocationResult.empty(normalizedIp);
            putMemoryCache(normalizedIp, fallback, NEGATIVE_CACHE_TTL);
            return fallback;
        }
    }

    /**
     * 便捷解析后台展示用的地理位置文本（包含运营商与内网标识）。
     */
    public String resolveLocationText(String rawIp) {
        return lookup(rawIp).fullLocation();
    }

    protected String fetchLookupJson(String normalizedIp) throws Exception {
        String effectiveBase = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        String effectiveDataset = (dataset == null || dataset.isBlank()) ? DEFAULT_DATASET : dataset.trim();
        String separator = effectiveBase.contains("?") ? "&" : "?";
        String requestUrl = effectiveBase
                + separator
                + "ip=" + URLEncoder.encode(normalizedIp, StandardCharsets.UTF_8)
                + "&dataset=" + URLEncoder.encode(effectiveDataset, StandardCharsets.UTF_8);

        long safeTimeout = Math.max(500L, Math.min(timeoutMs, 10000L));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(requestUrl))
                .timeout(Duration.ofMillis(safeTimeout))
                .header("Accept", "application/json")
                .header("User-Agent", "WindBlog-IP-Lookup/1.0")
                .GET()
                .build();

        HttpResponse<String> response = getHttpClient(safeTimeout).send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            LOG.debugf("ipnest 返回非 200 状态码: %d (ip=%s)", response.statusCode(), normalizedIp);
            return null;
        }
        return response.body();
    }

    IpLocationResult parseLookupResponse(String normalizedIp, String rawJson) {
        try {
            ObjectMapper mapper = objectMapper != null ? objectMapper : new ObjectMapper();
            JsonNode root = mapper.readTree(rawJson);
            if (root == null || !root.isObject() || root.path("code").asInt(-1) != 0) {
                return IpLocationResult.empty(normalizedIp);
            }
            JsonNode data = root.path("data");
            if (!data.isObject()) {
                return IpLocationResult.empty(normalizedIp);
            }

            boolean found = data.path("found").asBoolean(false);
            String scope = textOrNull(data, "scope");
            String country = textOrNull(data, "country");
            String region = textOrNull(data, "region");
            String city = textOrNull(data, "city");
            String district = textOrNull(data, "district");
            String isp = textOrNull(data, "isp");
            String countryCode = textOrNull(data, "country_code");

            if (!found && "special".equalsIgnoreCase(scope)) {
                return new IpLocationResult(
                        normalizedIp, false, scope,
                        country, region, city, district, isp, countryCode,
                        null, LOCAL_NETWORK_LABEL);
            }

            String displayLocation = buildDisplayLocation(country, region, city, district);
            String fullLocation = buildFullLocation(found, scope, displayLocation, isp);
            return new IpLocationResult(
                    normalizedIp, found, scope,
                    country, region, city, district, isp, countryCode,
                    displayLocation, fullLocation);
        } catch (Exception ex) {
            LOG.debugf("解析 ipnest 响应失败 (%s): %s", normalizedIp, ex.getMessage());
            return IpLocationResult.empty(normalizedIp);
        }
    }

    private static String buildDisplayLocation(String country, String region, String city, String district) {
        List<String> parts = new ArrayList<>(4);
        appendUniquePart(parts, country);
        appendUniquePart(parts, region);
        appendUniquePart(parts, city);
        appendUniquePart(parts, district);
        if (parts.isEmpty()) {
            return null;
        }
        return truncate(String.join(" ", parts), MAX_LOCATION_LENGTH);
    }

    private static void appendUniquePart(List<String> parts, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return;
        }
        String trimmed = candidate.trim();
        if (!parts.isEmpty() && parts.get(parts.size() - 1).equalsIgnoreCase(trimmed)) {
            return;
        }
        parts.add(trimmed);
    }

    private static String buildFullLocation(boolean found, String scope, String displayLocation, String isp) {
        if (!found) {
            return "special".equalsIgnoreCase(scope) ? LOCAL_NETWORK_LABEL : null;
        }
        if (displayLocation == null || displayLocation.isBlank()) {
            return (isp == null || isp.isBlank()) ? null : truncate(isp.trim(), MAX_LOCATION_LENGTH);
        }
        if (isp != null && !isp.isBlank()) {
            return truncate(displayLocation + " (" + isp.trim() + ")", MAX_LOCATION_LENGTH);
        }
        return truncate(displayLocation, MAX_LOCATION_LENGTH);
    }

    private static String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }

    private static String textOrNull(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            return null;
        }
        String text = field.asText(null);
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private HttpClient getHttpClient(long safeTimeoutMs) {
        HttpClient existing = this.httpClient;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (this.httpClient == null) {
                long connectTimeout = Math.max(500L, Math.min(safeTimeoutMs, 2000L));
                this.httpClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(connectTimeout))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
            }
            return this.httpClient;
        }
    }

    private void putMemoryCache(String ip, IpLocationResult result, Duration ttl) {
        long now = System.currentTimeMillis();
        if (memoryCache.size() >= MAX_MEMORY_CACHE_SIZE) {
            memoryCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
            if (memoryCache.size() >= MAX_MEMORY_CACHE_SIZE) {
                memoryCache.clear();
            }
        }
        memoryCache.put(ip, new CachedEntry(result, now + ttl.toMillis()));
    }

    static boolean isSpecialOrPrivateIp(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            // 0.0.0.0/8 (this network)
            if (b0 == 0) {
                return true;
            }
            // 100.64.0.0/10 (CGNAT)
            if (b0 == 100 && (b1 >= 64 && b1 <= 127)) {
                return true;
            }
            // 198.18.0.0/15 (benchmarking)
            if (b0 == 198 && (b1 == 18 || b1 == 19)) {
                return true;
            }
            // 240.0.0.0/4 (reserved)
            if (b0 >= 240) {
                return true;
            }
        } else if (bytes.length == 16) {
            // fc00::/7 (Unique Local Address)
            return (bytes[0] & 0xFE) == 0xFC;
        }
        return false;
    }

    private static InetAddress parseLiteralAddress(String literal) {
        if (!isAddressLiteral(literal)) {
            return null;
        }
        try {
            return InetAddress.getByName(literal);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isAddressLiteral(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim();
        if (normalized.indexOf(':') >= 0) {
            for (int i = 0; i < normalized.length(); i++) {
                char c = normalized.charAt(i);
                boolean hex = (c >= '0' && c <= '9')
                        || (c >= 'a' && c <= 'f')
                        || (c >= 'A' && c <= 'F');
                if (!hex && c != ':' && c != '.') {
                    return false;
                }
            }
            return true;
        }
        String[] octets = normalized.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            int part = 0;
            for (int i = 0; i < octet.length(); i++) {
                char c = octet.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
                part = part * 10 + (c - '0');
            }
            if (part > 255) {
                return false;
            }
        }
        return true;
    }

    private record CachedEntry(IpLocationResult result, long expiresAtMillis) {
    }

    @RegisterForReflection
    public record IpLocationResult(
            String ip,
            boolean found,
            String scope,
            String country,
            String region,
            String city,
            String district,
            String isp,
            String countryCode,
            String displayLocation,
            String fullLocation) {

        public static IpLocationResult empty(String ip) {
            return new IpLocationResult(ip, false, null, null, null, null, null, null, null, null, null);
        }
    }
}
