package com.biliwind.blog.service;

import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.AutoTranslationRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminTranslationDtos.AutoTranslationStatus;
import com.biliwind.blog.model.SystemSetting;
import com.biliwind.blog.service.edge.NodeRoleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@ApplicationScoped
public class AutoTranslationService {

    private static final Logger log = LoggerFactory.getLogger(AutoTranslationService.class);
    private static final int BATCH_SIZE = 10;

    @Inject
    EntityManager entityManager;

    @Inject
    TranslationTaskService taskService;

    @Inject
    AdminTranslationService translationService;

    @Inject
    NodeRoleService nodeRoleService;

    @Inject
    ObjectMapper objectMapper;

    private volatile boolean enabled = false;
    private volatile String targetLanguage = "en-US";
    private volatile OffsetDateTime lastPollAt = null;

    @PostConstruct
    void init() {
        try {
            SystemSetting setting = SystemSetting.findByKey("ai_translation");
            if (setting != null && setting.configValue != null) {
                JsonNode val = setting.configValue;
                if (val.has("autoTranslateEnabled")) {
                    this.enabled = val.get("autoTranslateEnabled").asBoolean(false);
                }
                if (val.has("autoTranslateTargetLanguage") && !val.get("autoTranslateTargetLanguage").asText().isBlank()) {
                    this.targetLanguage = val.get("autoTranslateTargetLanguage").asText().trim();
                } else if (val.has("defaultTargetLanguage") && !val.get("defaultTargetLanguage").asText().isBlank()) {
                    this.targetLanguage = val.get("defaultTargetLanguage").asText().trim();
                }
            }
        } catch (Exception e) {
            log.warn("初始化自动翻译设置失败: {}", e.getMessage());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getTargetLanguage() {
        return targetLanguage;
    }

    @Scheduled(every = "5s", identity = "auto-translation-poller",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Blocking
    public void scheduledPoll() {
        if (nodeRoleService.isEdgeNode() || !enabled) {
            return;
        }
        pollAndSubmit();
    }

    @Transactional
    public void pollAndSubmit() {
        lastPollAt = OffsetDateTime.now();
        String target = targetLanguage;
        if (target == null || target.isBlank()) {
            target = translationService.defaultTargetLanguage();
        }
        String targetLower = target.toLowerCase(Locale.ROOT);

        String sql = "select p.id from posts p "
                + "left join post_revisions r on r.id = p.current_revision_id "
                + "where p.deleted_at is null and p.status = 1 "
                + "and (not (coalesce(nullif(btrim(p.title ->> :targetLanguage), ''), nullif(btrim(p.title ->> :targetLanguageLower), ''), '') <> '' "
                + "     and coalesce(nullif(btrim(r.content_markdown ->> :targetLanguage), ''), nullif(btrim(r.content_markdown ->> :targetLanguageLower), ''), '') <> '') "
                + "  or coalesce(r.paragraph_translations -> :targetLanguage ->> 'outdated', "
                + "              r.paragraph_translations -> :targetLanguageLower ->> 'outdated') = 'true') "
                + "and not exists ( "
                + "    select 1 from translation_tasks t "
                + "    where t.post_id = p.id and t.target_language = :targetLanguage "
                + "      and t.status in ('QUEUED', 'RUNNING') "
                + ") "
                + "and not exists ( "
                + "    select 1 from translation_tasks t "
                + "    where t.post_id = p.id and t.target_language = :targetLanguage "
                + "      and t.status = 'FAILED' and t.updated_at > now() - interval '10 minutes' "
                + ") "
                + "order by p.updated_at desc, p.id desc limit :batchSize";

        @SuppressWarnings("unchecked")
        List<Number> ids = entityManager.createNativeQuery(sql)
                .setParameter("targetLanguage", target)
                .setParameter("targetLanguageLower", targetLower)
                .setParameter("batchSize", BATCH_SIZE)
                .getResultList();

        if (ids.isEmpty()) {
            return;
        }

        List<Long> postIds = new ArrayList<>();
        for (Number n : ids) {
            postIds.add(n.longValue());
        }

        log.info("[自动翻译] 发现 {} 篇待翻译文章，正在加入翻译队列: {}", postIds.size(), postIds);
        taskService.submit(postIds, target, null, false);
    }

    @Transactional
    public AutoTranslationStatus getStatus() {
        String target = targetLanguage != null && !targetLanguage.isBlank()
                ? targetLanguage
                : (translationService != null ? translationService.defaultTargetLanguage() : "en-US");
        int pending = 0;
        int queued = 0;
        int running = 0;

        if (entityManager != null) {
            String targetLower = target.toLowerCase(Locale.ROOT);
            String countSql = "select count(*) from posts p "
                    + "left join post_revisions r on r.id = p.current_revision_id "
                    + "where p.deleted_at is null and p.status = 1 "
                    + "and (not (coalesce(nullif(btrim(p.title ->> :targetLanguage), ''), nullif(btrim(p.title ->> :targetLanguageLower), ''), '') <> '' "
                    + "     and coalesce(nullif(btrim(r.content_markdown ->> :targetLanguage), ''), nullif(btrim(r.content_markdown ->> :targetLanguageLower), ''), '') <> '') "
                    + "  or coalesce(r.paragraph_translations -> :targetLanguage ->> 'outdated', "
                    + "              r.paragraph_translations -> :targetLanguageLower ->> 'outdated') = 'true') "
                    + "and not exists ( "
                    + "    select 1 from translation_tasks t "
                    + "    where t.post_id = p.id and t.target_language = :targetLanguage "
                    + "      and t.status in ('QUEUED', 'RUNNING') "
                    + ")";

            Number pendingNum = (Number) entityManager.createNativeQuery(countSql)
                    .setParameter("targetLanguage", target)
                    .setParameter("targetLanguageLower", targetLower)
                    .getSingleResult();
            pending = pendingNum == null ? 0 : pendingNum.intValue();

            Number queuedNum = (Number) entityManager.createNativeQuery(
                            "select count(*) from translation_tasks where status = 'QUEUED' and target_language = :target")
                    .setParameter("target", target)
                    .getSingleResult();
            queued = queuedNum == null ? 0 : queuedNum.intValue();

            Number runningNum = (Number) entityManager.createNativeQuery(
                            "select count(*) from translation_tasks where status = 'RUNNING' and target_language = :target")
                    .setParameter("target", target)
                    .getSingleResult();
            running = runningNum == null ? 0 : runningNum.intValue();
        }

        return new AutoTranslationStatus(
                enabled,
                target,
                pending,
                queued,
                running,
                lastPollAt
        );
    }

    @Transactional
    public AutoTranslationStatus updateStatus(AutoTranslationRequest request) {
        if (request != null) {
            if (request.enabled() != null) {
                this.enabled = request.enabled();
            }
            if (request.targetLanguage() != null && !request.targetLanguage().isBlank()) {
                this.targetLanguage = request.targetLanguage().trim();
            }
            if (entityManager != null) {
                persistSetting();
                if (this.enabled) {
                    pollAndSubmit();
                }
            }
        }
        return getStatus();
    }

    private void persistSetting() {
        try {
            SystemSetting setting = SystemSetting.findByKey("ai_translation");
            if (setting == null) {
                setting = new SystemSetting();
                setting.configKey = "ai_translation";
                setting.groupName = "功能设置";
                setting.configType = "object";
                setting.uiSchema = objectMapper.createObjectNode();
                setting.description = "文章翻译提示词与默认目标语言。";
                setting.configValue = objectMapper.createObjectNode();
            }
            ObjectNode obj = setting.configValue instanceof ObjectNode o ? o : objectMapper.createObjectNode();
            obj.put("autoTranslateEnabled", this.enabled);
            obj.put("autoTranslateTargetLanguage", this.targetLanguage);
            setting.configValue = obj;
            setting.persist();
        } catch (Exception e) {
            log.warn("保存自动翻译配置到 SystemSetting 失败: {}", e.getMessage());
        }
    }
}
