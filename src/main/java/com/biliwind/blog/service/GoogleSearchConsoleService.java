package com.biliwind.blog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@ApplicationScoped
public class GoogleSearchConsoleService {
    @ConfigProperty(name = "windblog.seo.gsc.client-id") Optional<String> clientId;
    @ConfigProperty(name = "windblog.seo.gsc.client-secret") Optional<String> clientSecret;
    @ConfigProperty(name = "windblog.seo.gsc.refresh-token") Optional<String> refreshToken;
    @ConfigProperty(name = "windblog.seo.gsc.site-url") Optional<String> siteUrl;
    @Inject ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private String accessToken;
    private Instant expiresAt = Instant.EPOCH;

    public Map<String, Object> status() {
        return Map.of("configured", configured(), "siteUrl", siteUrl.orElse(""),
                "scope", "https://www.googleapis.com/auth/webmasters.readonly");
    }

    private boolean configured() {
        return clientId.filter(s -> !s.isBlank()).isPresent() && clientSecret.filter(s -> !s.isBlank()).isPresent()
                && refreshToken.filter(s -> !s.isBlank()).isPresent() && siteUrl.filter(s -> !s.isBlank()).isPresent();
    }

    private synchronized String token() {
        if (!configured()) throw new BadRequestException("请先在服务器配置 Google OAuth 客户端、refresh token 和 Search Console 站点属性");
        if (Instant.now().isBefore(expiresAt)) return accessToken;
        String form = "grant_type=refresh_token&client_id=" + encode(clientId.get())
                + "&client_secret=" + encode(clientSecret.get()) + "&refresh_token=" + encode(refreshToken.get());
        JsonNode result = send(HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build());
        accessToken = result.path("access_token").asText();
        if (accessToken.isBlank()) throw new WebApplicationException("Google 授权响应缺少 access token", 502);
        expiresAt = Instant.now().plusSeconds(Math.max(0, result.path("expires_in").asLong(3600) - 60));
        return accessToken;
    }

    public JsonNode query(String start, String end, String dimension, int offset) {
        LocalDate startDate, endDate;
        try { startDate = LocalDate.parse(start); endDate = LocalDate.parse(end); }
        catch (RuntimeException e) { throw new BadRequestException("日期格式应为 YYYY-MM-DD"); }
        if (startDate.isAfter(endDate) || startDate.isBefore(endDate.minusMonths(16)) || offset < 0
                || !Set.of("query", "page", "date", "country", "device").contains(dimension)) throw new BadRequestException("查询范围或维度无效");
        String token = token();
        try {
            String body = mapper.writeValueAsString(Map.of("startDate", start, "endDate", end,
                    "dimensions", List.of(dimension), "rowLimit", 100, "startRow", offset, "dataState", "final"));
            return send(HttpRequest.newBuilder(URI.create("https://www.googleapis.com/webmasters/v3/sites/"
                            + encode(siteUrl.get()) + "/searchAnalytics/query"))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new WebApplicationException("无法编码查询", 500); }
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpRequest bounded = HttpRequest.newBuilder(request, (name, value) -> true).timeout(Duration.ofSeconds(25)).build();
            HttpResponse<String> response = client.send(bounded, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (response.statusCode() == 401) { expiresAt = Instant.EPOCH; accessToken = null; }
                throw new WebApplicationException("Google Search Console 请求失败，HTTP " + response.statusCode() + "；请检查授权、站点权限及 API 配额", 502);
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new WebApplicationException("Google 请求中断", 502); }
        catch (java.io.IOException e) { throw new WebApplicationException("无法连接 Google Search Console", 502); }
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
}
