package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.SeoPostPolicy;
import com.biliwind.blog.service.edge.PostSyncedEvent;
import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Submits newly published or updated public post URLs to IndexNow
 * ({@code https://api.indexnow.org/indexnow}) after transaction commit.
 */
@ApplicationScoped
public class IndexNowService {

    private static final Logger LOG = Logger.getLogger(IndexNowService.class);
    private static final URI INDEXNOW_ENDPOINT = URI.create("https://api.indexnow.org/indexnow");
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9-]{8,128}$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Inject
    SettingsReader settings;

    @Inject
    CanonicalUrlService canonicalUrlService;

    @Inject
    FeedService feedService;

    @Inject
    SeoPageService seoPageService;

    public boolean isEnabled() {
        return settings.flag(SettingKey.SEO, "index_enabled")
                && settings.flag(SettingKey.SEO, "indexnow_enabled")
                && isValidKey(getConfiguredKey());
    }

    public String getConfiguredKey() {
        String key = settings.text(SettingKey.SEO, "indexnow_key");
        return key == null ? "" : key.trim();
    }

    public static boolean isValidKey(String key) {
        return key != null && KEY_PATTERN.matcher(key).matches();
    }

    public void onPostSynced(@Observes(during = TransactionPhase.AFTER_SUCCESS) PostSyncedEvent event) {
        if (event == null || event.getPostId() == null || event.isRemoteSync()) {
            return;
        }
        if (!isEnabled()) {
            return;
        }
        Long postId = event.getPostId();
        Infrastructure.getDefaultWorkerPool().execute(() -> {
            try {
                List<String> urls = QuarkusTransaction.requiringNew().call(() -> resolvePostUrls(postId));
                if (!urls.isEmpty()) {
                    submitUrls(urls);
                }
            } catch (Exception e) {
                LOG.warnf("IndexNow async submission failed for post %d: %s", postId, e.getMessage());
            }
        });
    }

    List<String> resolvePostUrls(Long postId) {
        Post post = Post.findById(postId);
        if (post == null || post.deletedAt != null || post.status != PostStatus.PUBLISHED
                || post.visibility != 0 || post.publishedRevision == null) {
            return List.of();
        }
        SeoPostPolicy policy = seoPageService.policy(post.id);
        if (policy != null && policy.noindex) {
            return List.of();
        }
        String plainPath = "/post/" + URLEncoder.encode(post.slug, StandardCharsets.UTF_8).replace("+", "%20");
        List<String> urls = new ArrayList<>();
        for (String lang : LanguageConstant.SUPPORTED_LANG_ORDER) {
            if (LanguageHelper.hasLocalizedValue(post.publishedRevision.title, lang)
                    && LanguageHelper.hasLocalizedValue(post.publishedRevision.contentMarkdown, lang)) {
                urls.add(canonicalUrlService.buildLocalizedPath(plainPath, lang));
            }
        }
        if (urls.isEmpty()) {
            urls.add(canonicalUrlService.buildLocalizedPath(plainPath, "zh-CN"));
        }
        return urls;
    }

    public boolean submitUrls(List<String> urls) {
        if (!isEnabled() || urls == null || urls.isEmpty()) {
            return false;
        }
        String key = getConfiguredKey();
        String baseUrl = feedService.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        URI baseUri = URI.create(baseUrl);
        String host = baseUri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        if (baseUri.getPort() > 0) {
            host = host + ":" + baseUri.getPort();
        }
        String keyLocation = (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                + "/" + key + ".txt";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("host", host);
        payload.put("key", key);
        payload.put("keyLocation", keyLocation);
        payload.put("urlList", urls);

        try {
            String json = MAPPER.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(INDEXNOW_ENDPOINT)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                LOG.infof("IndexNow submitted %d URL(s), status=%d", urls.size(), status);
                return true;
            }
            LOG.warnf("IndexNow submission returned status=%d, body=%s", status, response.body());
            return false;
        } catch (Exception e) {
            LOG.warnf("IndexNow HTTP submission failed: %s", e.getMessage());
            return false;
        }
    }
}
