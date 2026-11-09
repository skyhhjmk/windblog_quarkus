package com.biliwind.blog.service;

import com.biliwind.blog.model.Post;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

@ApplicationScoped
public class CdnCacheDiagnosticsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CdnCacheDiagnosticsService.class);

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    int httpPort;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @RegisterForReflection
    public record ProbeResultItem(
            String id,
            String name,
            String path,
            int statusCode,
            String cacheControl,
            String vary,
            String pragma,
            boolean isSafe,
            String level, // "HEALTHY", "WARNING", "DANGER"
            String summary,
            String suggestion
    ) {}

    @RegisterForReflection
    public record DiagnosticsReport(
            String checkedAt,
            String overallStatus, // "HEALTHY", "WARNING", "DANGER"
            String targetBaseUrl,
            List<ProbeResultItem> probes,
            Map<String, Object> recommendations
    ) {}

    public DiagnosticsReport runDiagnostics(String optionalBaseUrl) {
        String baseUrl = (optionalBaseUrl != null && !optionalBaseUrl.isBlank())
                ? optionalBaseUrl.replaceAll("/+$", "")
                : "http://127.0.0.1:" + httpPort;

        List<ProbeResultItem> items = new ArrayList<>();

        // 1. 公开首页
        items.add(probeEndpoint(baseUrl, "/", Collections.emptyMap(),
                "公开首页 (GET /)",
                "必须声明 private 或 no-cache，因为存在动态 CSP Nonce 替换，绝不可被 CDN 共享强缓存"));

        // 2. 公开文章（若存在已发布文章）
        String sampleSlug = findSamplePublicSlug();
        if (sampleSlug != null) {
            String postPath = "/post/" + sampleSlug;
            // 匿名访问公开文章
            items.add(probeEndpoint(baseUrl, postPath, Collections.emptyMap(),
                    "公开文章-匿名访问 (GET " + postPath + ")",
                    "允许适度缓存（max-age / s-maxage），但必须包含 Vary: Accept-Language, Cookie"));

            // 携带登录态访问公开文章
            items.add(probeEndpoint(baseUrl, postPath, Map.of("Cookie", "user_token=diagnostic_mock_token"),
                    "公开文章-模拟登录态 (GET " + postPath + ")",
                    "必须自动切换为 private / no-store，绝不可被 CDN 共享缓存"));
        }

        // 3. 静态资源（头像/图标）
        items.add(probeEndpoint(baseUrl, "/assets/img/avatar-default.svg", Collections.emptyMap(),
                "静态资源 (GET /assets/img/avatar-default.svg)",
                "应当开启长效公共缓存 (public, max-age)，并建议在 CDN 开启参数区分"));

        // 4. 管理后台 API
        items.add(probeEndpoint(baseUrl, "/api/admin/system/monitor", Collections.emptyMap(),
                "管理后台 API (GET /api/admin/...)",
                "必须严格设置 no-store, no-cache，禁止任何中间代理与 CDN 缓存"));

        // 5. 用户数据接口
        items.add(probeEndpoint(baseUrl, "/api/user/profile", Collections.emptyMap(),
                "用户中心 API (GET /api/user/...)",
                "必须严格设置 no-store, no-cache，保护用户敏感隐私"));

        // 6. 异常与 404 错误响应
        items.add(probeEndpoint(baseUrl, "/_system_probe_non_existent_path_404", Collections.emptyMap(),
                "全局 404 错误响应",
                "错误响应必须带 no-store，严禁被 CDN 负缓存 (Negative Caching) 锁定数分钟"));

        // 计算整体健康度
        String overallStatus = "HEALTHY";
        for (ProbeResultItem item : items) {
            if ("DANGER".equals(item.level())) {
                overallStatus = "DANGER";
                break;
            } else if ("WARNING".equals(item.level())) {
                overallStatus = "WARNING";
            }
        }

        return new DiagnosticsReport(
                OffsetDateTime.now().toString(),
                overallStatus,
                baseUrl,
                items,
                getRecommendations()
        );
    }

    public ProbeResultItem probeCustom(String targetBaseUrl, String path, Map<String, String> headers) {
        String baseUrl = (targetBaseUrl != null && !targetBaseUrl.isBlank())
                ? targetBaseUrl.replaceAll("/+$", "")
                : "http://127.0.0.1:" + httpPort;
        return probeEndpoint(baseUrl, path, headers != null ? headers : Collections.emptyMap(),
                "自定义路径探测 (" + path + ")", "根据响应头自动诊断缓存合规性");
    }

    public Map<String, Object> getRecommendations() {
        Map<String, Object> rec = new LinkedHashMap<>();

        // 1. 缓存键 Cookie
        rec.put("cacheKeyCookies", List.of("user_lang_v2"));
        rec.put("cacheKeyCookiesDescription",
                "WindBlog 服务端渲染多语言页面依赖 Cookie: user_lang_v2。如果 CDN 支持按 Cookie 自定义 Cache Key，必须把 user_lang_v2 加入缓存键，否则不同语言的访客会命中同一缓存。");

        // 2. 绕过缓存的 Cookie (Bypass Cache)
        rec.put("bypassCookies", List.of("user_token", "admin_jwt", "post_access_ticket_*"));
        rec.put("bypassCookiesDescription",
                "当请求头包含上述任一 Cookie 时，说明用户处于已登录状态或具有文章解锁权限，CDN 规则必须强制绕过缓存 (Bypass Cache) 直接回源。");

        // 3. 必须 Bypass 的路径
        rec.put("bypassPaths", List.of(
                "/api/admin/*",
                "/api/user/*",
                "/user/*",
                "/q/*",
                "/observability/*",
                "/api/internal/*"
        ));
        rec.put("bypassPathsDescription",
                "管理后台、用户个人中心、Quarkus 内部端点（指标/监控）以及 Grafana 嵌入地址，必须在 CDN 规则中设为始终回源、绝不缓存。");

        // 4. 推荐强缓存的静态路径
        rec.put("staticCachePaths", List.of("/assets/*", "/uploads/*"));
        rec.put("staticCachePathsDescription",
                "静态资源及媒体上传目录可安全在 CDN 开启 30 天边缘强缓存，并请开启 Cache by Query String。");

        // 5. 错误码负缓存防护
        rec.put("errorCacheRecommendation",
                "务必将 4xx 与 5xx 状态码（特别是 500, 502, 503, 504）的 CDN 缓存时间显式设为 0 秒，避免源站闪断或重启时错误页被边缘节点长时间锁定。");

        // 6. Cloudflare 配置指引
        rec.put("cloudflareRules", Map.of(
                "title", "Cloudflare Cache Rules 推荐配置",
                "bypassRule", "(http.request.uri.path wildcard \"/api/admin/*\") or (http.request.uri.path wildcard \"/api/user/*\") or (http.request.uri.path wildcard \"/q/*\") or (http.cookie contains \"user_token\") or (http.cookie contains \"post_access_ticket_\")",
                "action", "Bypass cache",
                "cacheKeyAdvice", "在 Cache Key 中开启 Query String 全部包含，并在 Cookie 选项中添加 user_lang_v2"
        ));

        // 7. 国内 CDN 配置指引
        rec.put("standardCdnRules", Map.of(
                "title", "阿里云 / 腾讯云 CDN 推荐配置",
                "directoryNoCache", "/api/admin/, /api/user/, /q/, /observability/",
                "directoryLongCache", "/assets/ (30天), /uploads/ (30天)",
                "paramCache", "开启保留 Query String 参数缓存",
                "followOrigin", "开启遵循源站 Cache-Control (以源站响应为准)"
        ));

        return rec;
    }

    private ProbeResultItem probeEndpoint(String baseUrl, String path, Map<String, String> headers,
                                          String name, String purpose) {
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        String fullUrl = baseUrl + normalizedPath;
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(fullUrl))
                    .timeout(Duration.ofSeconds(3))
                    .GET();

            if (headers != null) {
                headers.forEach(reqBuilder::header);
            }

            HttpResponse<Void> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            String cc = response.headers().firstValue("Cache-Control").orElse("");
            String vary = response.headers().firstValue("Vary").orElse("");
            String pragma = response.headers().firstValue("Pragma").orElse("");

            return evaluateSafety(name, normalizedPath, status, cc, vary, pragma, purpose);
        } catch (Exception ex) {
            LOGGER.debug("CDN 探测异常: {} -> {}", fullUrl, ex.getMessage());
            return new ProbeResultItem(
                    UUID.randomUUID().toString(),
                    name,
                    normalizedPath,
                    0,
                    "",
                    "",
                    "",
                    false,
                    "WARNING",
                    "探测请求未完成: " + ex.getMessage(),
                    "请确认目标地址可访问或本地监听端口就绪。"
            );
        }
    }

    private ProbeResultItem evaluateSafety(String name, String path, int status,
                                           String cc, String vary, String pragma, String purpose) {
        String lowerCc = cc.toLowerCase(Locale.ROOT);
        boolean isSafe = true;
        String level = "HEALTHY";
        String summary;
        String suggestion = purpose;

        if (status >= 400) {
            if (lowerCc.contains("no-store") || lowerCc.contains("no-cache")) {
                summary = "符合规范：错误响应已设置禁缓存标头 (" + cc + ")，不会被 CDN 负缓存锁定。";
            } else {
                isSafe = false;
                level = "DANGER";
                summary = "安全警告：状态码 " + status + " 未显式返回 no-store，可能被 CDN 默认负缓存数分钟！";
                suggestion = "需确保错误处理器输出 Cache-Control: no-cache, no-store。";
            }
        } else if (path.startsWith("/api/admin/") || path.startsWith("/api/user/") || path.startsWith("/user/")) {
            if (lowerCc.contains("no-store") || (lowerCc.contains("private") && lowerCc.contains("no-cache"))) {
                summary = "安全受控：私有敏感接口已严格禁止共享缓存 (" + cc + ")。";
            } else {
                isSafe = false;
                level = "DANGER";
                summary = "高危隐患：私有/管理接口未禁用共享缓存 (" + cc + ")，数据可能被 CDN 缓存并泄漏！";
                suggestion = "必须设置 Cache-Control: no-cache, no-store, must-revalidate。";
            }
        } else if (path.equals("/")) {
            if (lowerCc.contains("private") || lowerCc.contains("no-cache") || lowerCc.contains("no-store")) {
                summary = "安全受控：首页使用私有/协商缓存 (" + cc + ")，动态 CSP Nonce 受到保护。";
            } else if (lowerCc.contains("public")) {
                isSafe = false;
                level = "WARNING";
                summary = "风险提示：首页设置了 public 强缓存，可能导致多用户收到失效的 CSP Nonce。";
            } else {
                summary = "正常：首页缓存标头为 " + (cc.isBlank() ? "默认" : cc);
            }
        } else if (path.startsWith("/assets/")) {
            if (lowerCc.contains("public") && lowerCc.contains("max-age")) {
                summary = "良好：静态资源已配置长效强缓存 (" + cc + ")。";
            } else {
                level = "WARNING";
                summary = "提示：静态资源未配置长期缓存 (" + cc + ")，将增加源站回源带宽消耗。";
            }
        } else {
            summary = "响应状态 " + status + "，标头: " + (cc.isBlank() ? "无 Cache-Control" : cc);
        }

        return new ProbeResultItem(
                UUID.randomUUID().toString(),
                name,
                path,
                status,
                cc,
                vary,
                pragma,
                isSafe,
                level,
                summary,
                suggestion
        );
    }

    private String findSamplePublicSlug() {
        try {
            Post post = Post.find("published = true and visibility = 0").firstResult();
            return post != null ? post.slug : null;
        } catch (Exception ex) {
            return null;
        }
    }
}
