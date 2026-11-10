package com.biliwind.blog.service.ai;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.controller.api.admin.dto.AdminAiProviderDtos.AiDailyUsageStatDto;
import com.biliwind.blog.controller.api.admin.dto.AdminAiProviderDtos.AiFeatureUsageStatDto;
import com.biliwind.blog.controller.api.admin.dto.AdminAiProviderDtos.AiProviderUsageStatDto;
import com.biliwind.blog.controller.api.admin.dto.AdminAiProviderDtos.AiUsageSummaryDto;
import com.biliwind.blog.model.AiProviderConfig;
import com.biliwind.blog.model.AiUsageLog;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class AiUsageService {

    private static final Logger log = LoggerFactory.getLogger(AiUsageService.class);

    @Inject
    EntityManager entityManager;

    /**
     * 异步记录一次 AI 调用的 Token 用量及耗时情况。
     */
    public void record(AiProviderConfig config, String feature, AiResult result, long durationMs, Throwable error) {
        String providerName = config != null && config.name != null ? config.name : "UNKNOWN";
        String providerType = config != null && config.provider != null ? config.provider : "UNKNOWN";
        String model = config != null ? config.model : null;
        if (result != null && result.modelId != null && !result.modelId.isBlank()) {
            model = result.modelId;
        }
        Long configId = config != null ? config.id : null;

        int prompt = result != null ? result.inputTokens : 0;
        int completion = result != null ? result.outputTokens : 0;
        int total = result != null ? result.totalTokens : (prompt + completion);
        boolean success = error == null;
        String errMsg = error != null ? safeMessage(error.getMessage()) : null;

        record(configId, providerName, providerType, model, feature, prompt, completion, total, durationMs, success, errMsg);
    }

    public void record(Long providerConfigId, String providerName, String providerType, String model,
                       String feature, int promptTokens, int completionTokens, int totalTokens,
                       long durationMs, boolean success, String errorMessage) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                AiUsageLog logEntry = new AiUsageLog();
                logEntry.providerConfigId = providerConfigId;
                logEntry.providerName = providerName != null ? providerName : "UNKNOWN";
                logEntry.providerType = providerType != null ? providerType : "UNKNOWN";
                logEntry.model = model;
                logEntry.feature = (feature != null && !feature.isBlank()) ? feature.trim().toLowerCase() : "general";
                logEntry.promptTokens = Math.max(0, promptTokens);
                logEntry.completionTokens = Math.max(0, completionTokens);
                logEntry.totalTokens = Math.max(0, totalTokens > 0 ? totalTokens : (promptTokens + completionTokens));
                logEntry.durationMs = Math.max(0, durationMs);
                logEntry.success = success;
                logEntry.errorMessage = errorMessage;
                logEntry.createdAt = OffsetDateTime.now();
                logEntry.persist();
            });
        } catch (Exception e) {
            log.warn("记录 AI 用量日志失败: {}", e.getMessage());
        }
    }

    /**
     * 获取指定天数内的 AI 用量统计摘要。
     */
    public AiUsageSummaryDto getSummary(int days) {
        int filterDays = days > 0 ? days : 30;
        OffsetDateTime since = OffsetDateTime.now().minusDays(filterDays);

        // 1. Overall totals
        String totalSql = "SELECT count(*), "
                + "coalesce(sum(case when success = true then 1 else 0 end), 0), "
                + "coalesce(sum(case when success = false then 1 else 0 end), 0), "
                + "coalesce(sum(prompt_tokens), 0), "
                + "coalesce(sum(completion_tokens), 0), "
                + "coalesce(sum(total_tokens), 0) "
                + "FROM ai_usage_logs WHERE created_at >= :since";

        Object[] totalRow = (Object[]) entityManager.createNativeQuery(totalSql)
                .setParameter("since", since)
                .getSingleResult();

        long totalReq = toLong(totalRow[0]);
        long successReq = toLong(totalRow[1]);
        long failedReq = toLong(totalRow[2]);
        long promptTokens = toLong(totalRow[3]);
        long completionTokens = toLong(totalRow[4]);
        long totalTokens = toLong(totalRow[5]);

        // 2. By provider
        String providerSql = "SELECT provider_config_id, provider_name, provider_type, model, "
                + "count(*), "
                + "coalesce(sum(case when success = true then 1 else 0 end), 0), "
                + "coalesce(sum(case when success = false then 1 else 0 end), 0), "
                + "coalesce(sum(prompt_tokens), 0), "
                + "coalesce(sum(completion_tokens), 0), "
                + "coalesce(sum(total_tokens), 0), "
                + "max(created_at) "
                + "FROM ai_usage_logs WHERE created_at >= :since "
                + "GROUP BY provider_config_id, provider_name, provider_type, model "
                + "ORDER BY sum(total_tokens) DESC, count(*) DESC";

        @SuppressWarnings("unchecked")
        List<Object[]> providerRows = entityManager.createNativeQuery(providerSql)
                .setParameter("since", since)
                .getResultList();

        List<AiProviderUsageStatDto> providers = new ArrayList<>();
        for (Object[] row : providerRows) {
            Long pId = row[0] != null ? ((Number) row[0]).longValue() : null;
            String pName = (String) row[1];
            String pType = (String) row[2];
            String pModel = (String) row[3];
            long req = toLong(row[4]);
            long sReq = toLong(row[5]);
            long fReq = toLong(row[6]);
            long pTok = toLong(row[7]);
            long cTok = toLong(row[8]);
            long tTok = toLong(row[9]);
            OffsetDateTime lastUsed = toOffsetDateTime(row[10]);

            providers.add(new AiProviderUsageStatDto(pId, pName, pType, pModel, req, sReq, fReq, pTok, cTok, tTok, lastUsed));
        }

        // 3. By feature
        String featureSql = "SELECT feature, count(*), "
                + "coalesce(sum(prompt_tokens), 0), "
                + "coalesce(sum(completion_tokens), 0), "
                + "coalesce(sum(total_tokens), 0) "
                + "FROM ai_usage_logs WHERE created_at >= :since "
                + "GROUP BY feature "
                + "ORDER BY sum(total_tokens) DESC, count(*) DESC";

        @SuppressWarnings("unchecked")
        List<Object[]> featureRows = entityManager.createNativeQuery(featureSql)
                .setParameter("since", since)
                .getResultList();

        List<AiFeatureUsageStatDto> features = new ArrayList<>();
        for (Object[] row : featureRows) {
            String feat = (String) row[0];
            long req = toLong(row[1]);
            long pTok = toLong(row[2]);
            long cTok = toLong(row[3]);
            long tTok = toLong(row[4]);
            features.add(new AiFeatureUsageStatDto(feat, req, pTok, cTok, tTok));
        }

        // 4. Daily stats
        String dailySql = "SELECT to_char(created_at, 'YYYY-MM-DD') as day, "
                + "count(*), coalesce(sum(total_tokens), 0) "
                + "FROM ai_usage_logs WHERE created_at >= :since "
                + "GROUP BY to_char(created_at, 'YYYY-MM-DD') "
                + "ORDER BY day ASC";

        @SuppressWarnings("unchecked")
        List<Object[]> dailyRows = entityManager.createNativeQuery(dailySql)
                .setParameter("since", since)
                .getResultList();

        List<AiDailyUsageStatDto> dailyStats = new ArrayList<>();
        for (Object[] row : dailyRows) {
            String day = (String) row[0];
            long req = toLong(row[1]);
            long tTok = toLong(row[2]);
            dailyStats.add(new AiDailyUsageStatDto(day, req, tTok));
        }

        return new AiUsageSummaryDto(totalReq, successReq, failedReq, promptTokens, completionTokens, totalTokens,
                providers, features, dailyStats);
    }

    public int clearUsageLogs(int days) {
        if (days <= 0) {
            return entityManager.createNativeQuery("TRUNCATE TABLE ai_usage_logs").executeUpdate();
        }
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(days);
        return entityManager.createNativeQuery("DELETE FROM ai_usage_logs WHERE created_at < :cutoff")
                .setParameter("cutoff", cutoff)
                .executeUpdate();
    }

    private long toLong(Object val) {
        if (val instanceof Number n) return n.longValue();
        return 0L;
    }

    private OffsetDateTime toOffsetDateTime(Object val) {
        if (val instanceof OffsetDateTime odt) return odt;
        if (val instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        return null;
    }

    private String safeMessage(String message) {
        String sanitized = SensitiveMessageSanitizer.sanitize(message);
        if (sanitized == null || sanitized.isBlank()) return "Unknown error";
        return sanitized.length() > 1000 ? sanitized.substring(0, 1000) : sanitized;
    }
}
