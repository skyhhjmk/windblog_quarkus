package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.AdminLinkDtos.*;
import com.biliwind.blog.controller.api.admin.dto.AdminUserDtos.PageResult;
import com.biliwind.blog.model.Link;
import com.biliwind.blog.model.LinkAudit;
import com.biliwind.blog.model.LinkMonitorLog;
import com.biliwind.blog.model.LinkType;
import com.biliwind.blog.service.link.LinkMonitorService;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.time.OffsetDateTime;
import java.util.List;
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
    public List<AdminLinkItem> list() {
        return Link.listAll(Sort.by("sortOrder")).stream()
                .map(l -> (Link) l)
                .map(this::toItem)
                .collect(Collectors.toList());
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

    @POST
    @Path("/{id}/check")
    @Operation(summary = "手动触发链接检查并更新链接状态")
    public void check(@PathParam("id") Long id) {
        Link link = Link.findById(id);
        if (link == null)
            throw new NotFoundException();
        linkMonitorService.checkLink(link, false);
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

        String queryStr = (linkId != null) ? "link.id = ?1" : "";
        Object[] params = (linkId != null) ? new Object[] { linkId } : new Object[] {};

        String where = queryStr.isEmpty() ? "" : "where " + queryStr;
        PanacheQuery<LinkMonitorLog> query = LinkMonitorLog.find(where + " order by checkTime desc", params);
        List<LinkMonitorLog> list = query.page(Page.of(page - 1, pageSize)).list();

        return new PageResult<>(
                list.stream().map(this::toLogItem).toList(),
                query.count(),
                page,
                pageSize);
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
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(5000)
                    .get();

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
            throw new WebApplicationException("Failed to parse URL metadata: " + e.getMessage(), 400);
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
        l.target = req.target() == null ? "_blank" : req.target();
        l.redirectType = req.redirectType() == null ? (short) 1 : req.redirectType();
        l.showUrl = req.showUrl() == null || req.showUrl();
        l.email = req.email();
        l.note = req.note();
        l.type = req.type() == null ? LinkType.FRIENDLY_LINK : LinkType.fromCode(req.type());
        l.seoTitle = req.seoTitle();
        l.seoKeywords = req.seoKeywords();
        l.seoDescription = req.seoDescription();
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
                l.createdAt);
    }

    private AdminLinkMonitorLogItem toLogItem(LinkMonitorLog log) {
        return new AdminLinkMonitorLogItem(
                log.id,
                log.link.id,
                log.link.name,
                log.checkTime,
                log.ok,
                log.loadTimeMs,
                log.backlinkFound,
                log.statusCode);
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
