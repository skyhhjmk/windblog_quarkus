package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.controller.api.admin.dto.AdminLinkDtos.*;
import com.biliwind.blog.controller.api.admin.dto.AdminUserDtos.PageResult;
import com.biliwind.blog.model.*;
import com.biliwind.blog.service.link.LinkMonitorService;
import com.biliwind.blog.service.link.LinkMonitorPolicy;
import com.biliwind.blog.service.security.SafeExternalHttpService;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

@Path("/api/admin/links")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminLink")
public class AdminLinkController {

    @Inject
    LinkMonitorService linkMonitorService;

    @Inject
    SafeExternalHttpService safeExternalHttpService;

    @Inject
    com.biliwind.blog.service.ai.AiManager aiManager;

    @Inject
    com.biliwind.blog.service.AuditService auditService;

    @Inject
    com.biliwind.blog.context.AdminRequestContext adminRequestContext;

    @Inject
    jakarta.enterprise.event.Event<com.biliwind.blog.service.edge.DataSyncEvent> dataSyncEvent;

    @Inject
    com.biliwind.blog.service.link.ArticleExternalLinkService articleExternalLinkService;

    @Inject
    com.biliwind.blog.service.link.LinkPublicTokenService linkPublicTokenService;

    @GET
    @Operation(summary = "所有友链")
    public PageResult<AdminLinkItem> list(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("pageSize") @DefaultValue("20") int pageSize,
            @QueryParam("type") Short type,
            @QueryParam("excludeType") Short excludeType) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        PanacheQuery<Link> query;
        if (type != null) {
            query = Link.find("type = ?1 order by id asc", LinkType.fromCode(type));
        } else if (excludeType != null) {
            query = Link.find("type <> ?1 order by id asc", LinkType.fromCode(excludeType));
        } else {
            query = Link.find("order by id asc");
        }
        List<Link> list = query.page(Page.of(safePage - 1, safePageSize)).list();
        return new PageResult<>(list.stream().map(this::toItem).toList(), query.count(), safePage, safePageSize);
    }

    @GET
    @Path("/article-link")
    @Operation(summary = "按 URL 查询文章外链")
    public AdminLinkItem findArticleLink(@QueryParam("url") String url) {
        if (url == null || url.isBlank()) {
            throw new BadRequestException("url 不能为空");
        }

        Link link = articleExternalLinkService.findArticleLinkByUrl(url);
        if (link == null) {
            throw new NotFoundException("文章外链不存在");
        }
        return toItem(link);
    }

    @GET
    @Path("/{id}/references")
    @Operation(summary = "查询文章外链引用文章")
    public List<AdminLinkReferenceItem> listReferences(@PathParam("id") Long id) {
        Link link = Link.findById(id);
        if (link == null) {
            throw new NotFoundException("链接不存在");
        }

        List<LinkArticleReference> references = articleExternalLinkService.listReferences(id);
        java.util.ArrayList<AdminLinkReferenceItem> items = new java.util.ArrayList<>();
        for (LinkArticleReference reference : references) {
            items.add(toReferenceItem(reference));
        }
        return items;
    }

    @POST
    @Path("/{id}/check")
    @Operation(summary = "异步触发链接检查并更新链接状态")
    public Response check(@PathParam("id") Long id) {
        Link link = Link.findById(id);
        if (link == null) {
            throw new NotFoundException("链接不存在");
        }
        AdminLinkCheckJobItem job = linkMonitorService.enqueueCheck(id, LinkMonitorSource.MANUAL);
        return Response.accepted(job).build();
    }

    @GET
    @Path("/{id}/check-jobs/{jobId}")
    @Operation(summary = "查询异步链接检查状态")
    public AdminLinkCheckJobItem checkJob(@PathParam("id") Long id, @PathParam("jobId") String jobId) {
        if (Link.findById(id) == null) {
            throw new NotFoundException("链接不存在");
        }
        AdminLinkCheckJobItem job = linkMonitorService.getCheckJob(id, jobId);
        if (job == null) {
            throw new NotFoundException("检测任务不存在");
        }
        return job;
    }

    @POST
    @Path("/{id}/review")
    @Transactional
    @Operation(summary = "审核公开友链申请")
    public AdminLinkItem review(
            @PathParam("id") Long id,
            LinkApplicationReviewRequest request
    ) {
        Link link = Link.findById(id);
        if (link == null) {
            throw new NotFoundException("友链申请不存在");
        }
        if (request == null) {
            throw new BadRequestException("审核内容不能为空");
        }

        if (request.approved()) {
            link.applicationStatus = 1;
            link.status = 1;
        } else {
            link.applicationStatus = 3;
            link.status = 2;
        }
        if (request.note() != null && !request.note().isBlank()) {
            link.note = request.note().trim();
        }
        link.updatedAt = OffsetDateTime.now();
        String auditAction = "application_rejected";
        if (request.approved()) {
            auditAction = "application_approved";
        }
        auditService.log(
                "link",
                String.valueOf(link.id),
                auditAction,
                null,
                java.util.Map.of("applicationStatus", link.applicationStatus)
        );
        if (request.approved() && LinkMonitorPolicy.isMonitoringEnabled(link)) {
            linkMonitorService.enqueueCheck(link.id, LinkMonitorSource.AUTOMATIC);
        }
        dataSyncEvent.fire(new com.biliwind.blog.service.edge.DataSyncEvent("LINK", link.id, "UPSERT"));
        return toItem(link);
    }

    @POST
    @Path("/{id}/audit")
    @Transactional
    @Operation(summary = "手动触发 AI 审核")
    public CompletionStage<AdminLinkAuditItem> audit(@PathParam("id") Long id) {
        Link link = Link.findById(id);
        if (link == null)
            throw new NotFoundException();

        long startTime = System.currentTimeMillis();
        Long linkId = link.id;
        // 在异步调用前捕获用户ID
        Long performingUserId = adminRequestContext.getUserId();

        return aiManager.moderate(link.name + " " + link.description + " " + link.url).thenApply(new java.util.function.Function<com.biliwind.blog.service.ai.AiResult, AdminLinkAuditItem>() {
            @Override
            public AdminLinkAuditItem apply(com.biliwind.blog.service.ai.AiResult aiResult) {
                long endTime = System.currentTimeMillis();
                long durationMs = endTime - startTime;

                io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(new Runnable() {
                    @Override
                    public void run() {
                        Link l = Link.findById(linkId);
                        if (l == null) {
                            return;
                        }

                        boolean safe = aiResult.isSafe;
                        LinkAudit audit = new LinkAudit();
                        audit.link = l;
                        audit.status = safe ? (short) 1 : (short) 2; // 1=approved, 2=rejected
                        audit.reason = safe ? "AI verified safe" : "AI flagged as unsafe/spam";
                        audit.score = safe ? java.math.BigDecimal.valueOf(100) : java.math.BigDecimal.ZERO;
                        audit.createdAt = java.time.OffsetDateTime.now();
                        audit.persist();

                        // Auto update link status
                        if (!safe && l.status == 1) {
                            l.status = 2; // Auto hide if flagged
                        }

                        // 写入审计日志
                        java.util.Map<String, Object> extInfo = new java.util.HashMap<>();
                        extInfo.put("durationMs", durationMs);
                        extInfo.put("inputTokens", aiResult.inputTokens);
                        extInfo.put("outputTokens", aiResult.outputTokens);
                        extInfo.put("totalTokens", aiResult.totalTokens);
                        extInfo.put("isSafe", safe);

                        auditService.log("link", String.valueOf(l.id), "ai_moderation",
                                java.util.Map.of("status", l.status == 2 ? 1 : l.status),
                                java.util.Map.of("status", (int) l.status),
                                extInfo);
                    }
                });

                LinkAudit latestAudit = LinkAudit.find("link.id = ?1 order by createdAt desc", linkId).firstResult();
                return toAuditItem(latestAudit);
            }
        });
    }

    @GET
    @Path("/monitor-logs")
    @Operation(summary = "监控日志列表")
    public PageResult<AdminLinkMonitorLogItem> monitorLogs(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("pageSize") @DefaultValue("20") int pageSize,
            @QueryParam("linkId") Long linkId) {

        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        OffsetDateTime retentionCutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(90);
        PanacheQuery<LinkMonitorLog> query;
        if (linkId != null) {
            query = LinkMonitorLog.find(
                    "link.id = ?1 and checkTime >= ?2 order by checkTime desc, id desc",
                    linkId,
                    retentionCutoff);
        } else {
            query = LinkMonitorLog.find(
                    "checkTime >= ?1 order by checkTime desc, id desc",
                    retentionCutoff);
        }
        List<LinkMonitorLog> list = query.page(Page.of(safePage - 1, safePageSize)).list();

        return new PageResult<>(
                list.stream().map(this::toLogItem).toList(),
                query.count(),
                safePage,
                safePageSize);
    }

    @GET
    @Path("/audit-logs")
    @Operation(summary = "审核日志列表")
    public PageResult<AdminLinkAuditItem> auditLogs(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("pageSize") @DefaultValue("20") int pageSize,
            @QueryParam("linkId") Long linkId) {

        String queryStr = (linkId != null) ? "link.id = ?1" : "";
        Object[] params = (linkId != null) ? new Object[] { linkId } : new Object[] {};

        String where = queryStr.isEmpty() ? "" : "where " + queryStr;
        PanacheQuery<LinkAudit> query = LinkAudit.find(where + " order by createdAt desc", params);
        List<LinkAudit> list = query.page(Page.of(page - 1, pageSize)).list();

        return new PageResult<>(
                list.stream().map(this::toAuditItem).toList(),
                query.count(),
                page,
                pageSize);
    }

    @POST
    @Path("/parse-meta")
    @Operation(summary = "Parse URL metadata")
    public LinkMetaResponse parseMeta(java.util.Map<String, String> body) {
        String url = body.get("url");
        if (url == null || url.isBlank()) {
            throw new BadRequestException("url cannot be empty");
        }

        try {
            String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
            String html = safeExternalHttpService.get(url, userAgent).bodyAsText();
            Document doc = Jsoup.parse(html, url);

            String title = doc.title();
            if (title != null && title.length() > 255) {
                title = title.substring(0, 255);
            }

            String description = "";
            Element descMeta = doc.selectFirst("meta[name=description]");
            if (descMeta != null) {
                description = descMeta.attr("content");
            } else {
                Element ogDescMeta = doc.selectFirst("meta[property=og:description]");
                if (ogDescMeta != null) {
                    description = ogDescMeta.attr("content");
                }
            }
            if (description != null && description.length() > 500) {
                description = description.substring(0, 500);
            }

            String icon = "";
            Element iconLink = doc.selectFirst("link[rel~=(?i)^(shortcut )?icon]");
            if (iconLink != null) {
                icon = iconLink.attr("href");
                if (!icon.startsWith("http")) {
                    if (icon.startsWith("//")) {
                        icon = "https:" + icon;
                    } else if (icon.startsWith("/")) {
                        java.net.URL parsedUrl = new java.net.URL(url);
                        icon = parsedUrl.getProtocol() + "://" + parsedUrl.getHost() + icon;
                    } else {
                        java.net.URL parsedUrl = new java.net.URL(url);
                        String path = parsedUrl.getPath();
                        if (path.isEmpty() || path.equals("/")) {
                            icon = parsedUrl.getProtocol() + "://" + parsedUrl.getHost() + "/" + icon;
                        } else {
                            icon = parsedUrl.getProtocol() + "://" + parsedUrl.getHost() + path.substring(0, path.lastIndexOf("/") + 1) + icon;
                        }
                    }
                }
            } else {
                // Fallback to default favicon.ico
                java.net.URI uri = java.net.URI.create(url);
                icon = uri.getScheme() + "://" + uri.getHost() + "/favicon.ico";
            }

            return new LinkMetaResponse(title, description, icon);
        } catch (Exception e) {
            throw new WebApplicationException("Failed to parse URL metadata: "
                    + SensitiveMessageSanitizer.sanitize(e.getMessage()), 400);
        }
    }

    @POST
    @Transactional
    @Operation(summary = "创建友链")
    public AdminLinkItem create(LinkCreateRequest req) {
        Link l = new Link();
        l.name = req.name();
        l.url = req.url();
        l.description = req.description();
        l.image = req.image();
        l.icon = req.icon();
        l.sortOrder = req.sortOrder() == null ? 0 : req.sortOrder();
        l.status = req.status() == null ? (short) 1 : req.status();
        l.applicationStatus = 1;
        l.availabilityStatus = "UNKNOWN";
        l.backlinkStatus = "UNKNOWN";
        l.target = req.target() == null ? "_blank" : req.target();
        l.redirectType = req.redirectType() == null ? (short) 1 : req.redirectType();
        l.showUrl = req.showUrl() == null || req.showUrl();
        l.email = req.email();
        l.note = req.note();
        l.type = req.type() == null ? LinkType.FRIENDLY_LINK : LinkType.fromCode(req.type());
        l.seoTitle = req.seoTitle();
        l.seoKeywords = req.seoKeywords();
        l.seoDescription = req.seoDescription();
        applyMonitoringSettings(
                l,
                req.monitoringEnabled() == null ? true : req.monitoringEnabled(),
                req.monitoringIntervalMinutes() == null
                        ? LinkMonitorPolicy.DEFAULT_INTERVAL_MINUTES
                        : req.monitoringIntervalMinutes(),
                req.hideWhenBacklinkMissing() == null ? false : req.hideWhenBacklinkMissing(),
                req.hideWhenOffline() == null ? false : req.hideWhenOffline(),
                req.monitoringKeywords(),
                req.hideWhenKeywordFraudDetected() == null ? false : req.hideWhenKeywordFraudDetected());
        applyRegionalLinkSettings(l, req.displayRegion() == null ? "global" : req.displayRegion(),
                req.backlinkCheckUrls(), req.notifyOnBacklinkMissing(), req.backlinkMissingGraceDays(),
                req.notifyOnOffline(), req.offlineGraceDays(), req.notifyOnKeywordFraud(),
                req.keywordFraudGraceDays());
        l.publicToken = linkPublicTokenService.ensurePublicToken(l);

        l.createdAt = OffsetDateTime.now();
        l.updatedAt = OffsetDateTime.now();

        l.persist();
        auditService.log("link", String.valueOf(l.id), "create", null, java.util.Map.of("name", l.name, "url", l.url));
        dataSyncEvent.fire(new com.biliwind.blog.service.edge.DataSyncEvent("LINK", l.id, "UPSERT"));
        return toItem(l);
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public AdminLinkItem update(@PathParam("id") Long id, LinkUpdateRequest req) {
        Link l = Link.findById(id);
        if (l == null)
            throw new NotFoundException();

        if (req.name() != null)
            l.name = req.name();
        if (req.url() != null)
            l.url = req.url();
        if (req.description() != null)
            l.description = req.description();
        if (req.image() != null)
            l.image = req.image();
        if (req.icon() != null)
            l.icon = req.icon();
        if (req.sortOrder() != null)
            l.sortOrder = req.sortOrder();
        if (req.status() != null)
            l.status = req.status();
        if (req.target() != null)
            l.target = req.target();
        if (req.redirectType() != null)
            l.redirectType = req.redirectType();
        if (req.showUrl() != null)
            l.showUrl = req.showUrl();
        if (req.email() != null)
            l.email = req.email();
        if (req.note() != null)
            l.note = req.note();
        if (req.type() != null)
            l.type = LinkType.fromCode(req.type());
        if (req.seoTitle() != null)
            l.seoTitle = req.seoTitle();
        if (req.seoKeywords() != null)
            l.seoKeywords = req.seoKeywords();
        if (req.seoDescription() != null)
            l.seoDescription = req.seoDescription();

        if (req.monitoringEnabled() != null
                || req.monitoringIntervalMinutes() != null
                || req.hideWhenBacklinkMissing() != null
                || req.hideWhenOffline() != null
                || req.monitoringKeywords() != null
                || req.hideWhenKeywordFraudDetected() != null) {
            applyMonitoringSettings(
                    l,
                    req.monitoringEnabled(),
                    req.monitoringIntervalMinutes(),
                    req.hideWhenBacklinkMissing(),
                    req.hideWhenOffline(),
                    req.monitoringKeywords(),
                    req.hideWhenKeywordFraudDetected());
        }

        if (req.displayRegion() != null
                || req.backlinkCheckUrls() != null
                || req.notifyOnBacklinkMissing() != null
                || req.backlinkMissingGraceDays() != null
                || req.notifyOnOffline() != null
                || req.offlineGraceDays() != null
                || req.notifyOnKeywordFraud() != null
                || req.keywordFraudGraceDays() != null) {
            applyRegionalLinkSettings(l, req.displayRegion(), req.backlinkCheckUrls(),
                    req.notifyOnBacklinkMissing(), req.backlinkMissingGraceDays(), req.notifyOnOffline(),
                    req.offlineGraceDays(), req.notifyOnKeywordFraud(), req.keywordFraudGraceDays());
        }

        linkPublicTokenService.ensurePublicToken(l);
        l.updatedAt = OffsetDateTime.now();
        auditService.log("link", String.valueOf(l.id), "update", null, java.util.Map.of("name", l.name, "url", l.url)); // For simplicity, just log key info
        dataSyncEvent.fire(new com.biliwind.blog.service.edge.DataSyncEvent("LINK", l.id, "UPSERT"));
        return toItem(l);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public void delete(@PathParam("id") Long id) {
        Link l = Link.findById(id);
        if (l != null) {
            auditService.log("link", String.valueOf(l.id), "delete", java.util.Map.of("name", l.name), null);
            dataSyncEvent.fire(new com.biliwind.blog.service.edge.DataSyncEvent("LINK", id, "DELETE"));
            Link.deleteById(id);
        }
    }

    private AdminLinkItem toItem(Link l) {
        return new AdminLinkItem(
                l.id,
                l.name,
                l.url,
                l.description,
                l.image,
                l.icon,
                l.sortOrder,
                l.status,
                l.target,
                l.redirectType,
                l.showUrl,
                l.email,
                l.note,
                l.type != null ? l.type.code() : 0,
                l.seoTitle,
                l.seoKeywords,
                l.seoDescription,
                l.applicationStatus,
                l.availabilityStatus,
                l.backlinkStatus,
                l.lastCheckedAt,
                readSetting(l, "placementType"),
                readSetting(l, "placementUrl"),
                readSetting(l, "placementPageName"),
                readSetting(l, "placementDescription"),
                articleExternalLinkService.countReferencedPosts(l.id),
                articleExternalLinkService.countReferences(l.id),
                LinkMonitorPolicy.isMonitoringEnabled(l),
                LinkMonitorPolicy.intervalMinutes(l),
                LinkMonitorPolicy.shouldHideWhenBacklinkMissing(l),
                LinkMonitorPolicy.shouldHideWhenOffline(l),
                LinkMonitorPolicy.monitoringKeywords(l),
                LinkMonitorPolicy.shouldHideWhenKeywordFraudDetected(l),
                l.keywordFraudStatus == null ? "UNKNOWN" : l.keywordFraudStatus,
                LinkMonitorPolicy.displayRegion(l).getCode(),
                LinkMonitorPolicy.backlinkCheckUrls(l),
                LinkMonitorPolicy.shouldNotify(l, LinkMonitorPolicy.BACKLINK_MISSING),
                LinkMonitorPolicy.graceDays(l, LinkMonitorPolicy.BACKLINK_MISSING),
                LinkMonitorPolicy.shouldNotify(l, LinkMonitorPolicy.OFFLINE),
                LinkMonitorPolicy.graceDays(l, LinkMonitorPolicy.OFFLINE),
                LinkMonitorPolicy.shouldNotify(l, LinkMonitorPolicy.KEYWORD_FRAUD),
                LinkMonitorPolicy.graceDays(l, LinkMonitorPolicy.KEYWORD_FRAUD),
                LinkMonitorPolicy.autoHideMessage(l),
                l.createdAt);
    }

    private void applyRegionalLinkSettings(
            Link link,
            String displayRegion,
            List<String> backlinkCheckUrls,
            Boolean notifyOnBacklinkMissing,
            Integer backlinkMissingGraceDays,
            Boolean notifyOnOffline,
            Integer offlineGraceDays,
            Boolean notifyOnKeywordFraud,
            Integer keywordFraudGraceDays) {
        Map<String, Object> settings = link.settings == null
                ? new HashMap<>()
                : new HashMap<>(link.settings);
        if (displayRegion != null) {
            String normalizedRegion = displayRegion.trim().toLowerCase(java.util.Locale.ROOT);
            boolean knownRegion = java.util.Arrays.stream(BlogRegion.values())
                    .anyMatch(region -> region.getCode().equals(normalizedRegion)
                            || ("china".equals(normalizedRegion) && region == BlogRegion.CN));
            if (!knownRegion) {
                throw new BadRequestException("友链展示区域无效");
            }
            settings.put("displayRegion", BlogRegion.fromCode(normalizedRegion).getCode());
        }
        if (backlinkCheckUrls != null) {
            if (backlinkCheckUrls.size() > 20) {
                throw new BadRequestException("检测链接最多设置 20 个");
            }
            List<String> normalizedUrls = new ArrayList<>();
            for (String value : backlinkCheckUrls) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                String normalized = value.trim();
                if (normalized.length() > 2000) {
                    throw new BadRequestException("单个检测链接不能超过 2000 个字符");
                }
                try {
                    URI uri = URI.create(normalized);
                    String scheme = uri.getScheme();
                    if (uri.getHost() == null || uri.getUserInfo() != null
                            || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                        throw new IllegalArgumentException("invalid URL");
                    }
                } catch (IllegalArgumentException exception) {
                    throw new BadRequestException("检测链接必须是有效的 HTTP 或 HTTPS 地址");
                }
                if (!normalizedUrls.contains(normalized)) {
                    normalizedUrls.add(normalized);
                }
            }
            if (normalizedUrls.isEmpty()) {
                settings.remove("backlinkCheckUrls");
            } else {
                settings.put("backlinkCheckUrls", List.copyOf(normalizedUrls));
            }
        }

        putBooleanSetting(settings, "notifyOnBacklinkMissing", notifyOnBacklinkMissing);
        putBooleanSetting(settings, "notifyOnOffline", notifyOnOffline);
        putBooleanSetting(settings, "notifyOnKeywordFraud", notifyOnKeywordFraud);
        putGraceSetting(settings, "backlinkMissingGraceDays", backlinkMissingGraceDays);
        putGraceSetting(settings, "offlineGraceDays", offlineGraceDays);
        putGraceSetting(settings, "keywordFraudGraceDays", keywordFraudGraceDays);

        boolean requestsNotification = Boolean.TRUE.equals(settings.get("notifyOnBacklinkMissing"))
                || Boolean.TRUE.equals(settings.get("notifyOnOffline"))
                || Boolean.TRUE.equals(settings.get("notifyOnKeywordFraud"));
        if (requestsNotification && (link.email == null || link.email.isBlank())) {
            throw new BadRequestException("启用站长通知前，请先填写友链邮箱");
        }
        link.settings = settings;
    }

    private void putBooleanSetting(Map<String, Object> settings, String key, Boolean value) {
        if (value != null) {
            settings.put(key, value);
        }
    }

    private void putGraceSetting(Map<String, Object> settings, String key, Integer value) {
        if (value == null) {
            return;
        }
        if (value < 0 || value > LinkMonitorPolicy.MAX_GRACE_DAYS) {
            throw new BadRequestException("缓冲期必须在 0 到 365 天之间");
        }
        settings.put(key, value);
    }

    private void applyMonitoringSettings(
            Link link,
            Boolean monitoringEnabled,
            Integer monitoringIntervalMinutes,
            Boolean hideWhenBacklinkMissing,
            Boolean hideWhenOffline,
            String monitoringKeywords,
            Boolean hideWhenKeywordFraudDetected) {
        if (monitoringIntervalMinutes != null
                && (monitoringIntervalMinutes < 1
                || monitoringIntervalMinutes > LinkMonitorPolicy.MAX_INTERVAL_MINUTES)) {
            throw new BadRequestException("检测间隔必须在 1 到 10080 分钟之间");
        }

        Map<String, Object> settings = link.settings == null
                ? new HashMap<>()
                : new HashMap<>(link.settings);
        if (monitoringEnabled != null) {
            settings.put("monitoringEnabled", monitoringEnabled);
        }
        if (monitoringIntervalMinutes != null) {
            settings.put("monitoringIntervalMinutes", monitoringIntervalMinutes);
        }
        if (hideWhenBacklinkMissing != null) {
            settings.put("hideWhenBacklinkMissing", hideWhenBacklinkMissing);
        }
        if (hideWhenOffline != null) {
            settings.put("hideWhenOffline", hideWhenOffline);
        }
        if (monitoringKeywords != null) {
            List<String> keywords = java.util.Arrays.stream(monitoringKeywords.split("[,，;；\\r\\n]+"))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
            if (keywords.size() > 20 || keywords.stream().anyMatch(value -> value.length() > 100)) {
                throw new BadRequestException("检测关键词最多 20 个，每个关键词不超过 100 个字符");
            }
            if (keywords.isEmpty()) {
                settings.remove("monitoringKeywords");
            } else {
                settings.put("monitoringKeywords", String.join(", ", keywords));
            }
        }
        if (hideWhenKeywordFraudDetected != null) {
            settings.put("hideWhenKeywordFraudDetected", hideWhenKeywordFraudDetected);
        }
        if (hideWhenBacklinkMissing != null
                && LinkMonitorPolicy.shouldHideWhenBacklinkMissing(link) != hideWhenBacklinkMissing) {
            resetLifecycleState(settings, LinkMonitorPolicy.BACKLINK_MISSING);
        }
        if (hideWhenOffline != null
                && LinkMonitorPolicy.shouldHideWhenOffline(link) != hideWhenOffline) {
            resetLifecycleState(settings, LinkMonitorPolicy.OFFLINE);
        }
        if (hideWhenKeywordFraudDetected != null
                && LinkMonitorPolicy.shouldHideWhenKeywordFraudDetected(link) != hideWhenKeywordFraudDetected) {
            resetLifecycleState(settings, LinkMonitorPolicy.KEYWORD_FRAUD);
        }
        link.settings = settings;
    }

    private void resetLifecycleState(Map<String, Object> settings, String condition) {
        Object rawLifecycle = settings.get("autoHideLifecycle");
        if (!(rawLifecycle instanceof Map<?, ?> values)) {
            return;
        }
        Map<String, Object> lifecycle = new HashMap<>();
        values.forEach((key, value) -> {
            if (key instanceof String stringKey && !condition.equals(stringKey)) {
                lifecycle.put(stringKey, value);
            }
        });
        if (lifecycle.isEmpty()) {
            settings.remove("autoHideLifecycle");
        } else {
            settings.put("autoHideLifecycle", lifecycle);
        }
    }

    private String readSetting(Link link, String key) {
        if (link.settings == null) {
            return null;
        }
        Object value = link.settings.get(key);
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    private AdminLinkReferenceItem toReferenceItem(LinkArticleReference reference) {
        String postTitle = "";
        String postSlug = "";
        Long postId = null;
        if (reference.post != null) {
            postId = reference.post.id;
            postSlug = reference.post.slug;
            postTitle = resolvePostTitle(reference.post);
        }

        return new AdminLinkReferenceItem(
                reference.id,
                postId,
                postSlug,
                postTitle,
                reference.anchorText,
                reference.normalizedUrl,
                reference.referenceCount,
                reference.updatedAt);
    }

    private String resolvePostTitle(com.biliwind.blog.model.Post post) {
        if (post == null) {
            return "";
        }
        String title = com.biliwind.blog.common.helper.LanguageHelper.resolveLocalizedValue(post.title, "zh-cn");
        if (title == null || title.isBlank()) {
            return post.slug;
        }
        return title;
    }

    private AdminLinkMonitorLogItem toLogItem(LinkMonitorLog log) {
        Map<String, Object> detectionDetails = new HashMap<>();
        if (log.rawData != null && log.rawData.get("detectionDetails") instanceof Map<?, ?> values) {
            values.forEach((key, value) -> {
                if (key instanceof String stringKey) {
                    detectionDetails.put(stringKey, value);
                }
            });
        }
        boolean fraudDetected = Boolean.TRUE.equals(detectionDetails.get("keywordFraudDetected"));
        return new AdminLinkMonitorLogItem(
                log.id,
                log.link.id,
                log.link.name,
                log.checkTime,
                log.checkSource == null ? LinkMonitorSource.UNKNOWN.name() : log.checkSource.name(),
                log.ok,
                log.loadTimeMs,
                log.backlinkFound,
                log.statusCode,
                log.checkBatchId,
                log.nodeId,
                log.nodeName,
                log.errorMessage,
                fraudDetected,
                detectionDetails);
    }

    private AdminLinkAuditItem toAuditItem(LinkAudit audit) {
        return new AdminLinkAuditItem(
                audit.id,
                audit.link.id,
                audit.link.name,
                audit.status,
                audit.score,
                audit.reason,
                audit.autoApproved,
                audit.createdAt);
    }
}
