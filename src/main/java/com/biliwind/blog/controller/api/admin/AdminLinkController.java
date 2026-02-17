package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.AdminLinkDtos.AdminLinkAuditItem;
import com.biliwind.blog.controller.api.admin.dto.AdminLinkDtos.AdminLinkItem;
import com.biliwind.blog.controller.api.admin.dto.AdminLinkDtos.AdminLinkMonitorLogItem;
import com.biliwind.blog.controller.api.admin.dto.AdminLinkDtos.LinkCreateRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminLinkDtos.LinkUpdateRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminUserDtos.PageResult;
import com.biliwind.blog.model.Link;
import com.biliwind.blog.model.LinkAudit;
import com.biliwind.blog.model.LinkMonitorLog;
import com.biliwind.blog.service.ai.AiManager;
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

import java.math.BigDecimal;
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
    AiManager aiManager;

    @GET
    @Operation(summary = "所有友链")
    public List<AdminLinkItem> list() {
        return Link.listAll(Sort.by("sortOrder")).stream()
                .map(l -> (Link) l)
                .map(this::toItem)
                .collect(Collectors.toList());
    }

    @POST
    @Path("/{id}/check")
    @Operation(summary = "手动触发链接检查")
    public void check(@PathParam("id") Long id) {
        Link link = Link.findById(id);
        if (link == null)
            throw new NotFoundException();
        linkMonitorService.checkLink(link);
    }

    @POST
    @Path("/{id}/audit")
    @Transactional
    @Operation(summary = "手动触发 AI 审核")
    public CompletionStage<AdminLinkAuditItem> audit(@PathParam("id") Long id) {
        Link link = Link.findById(id);
        if (link == null)
            throw new NotFoundException();

        return aiManager.moderate(link.name + " " + link.description + " " + link.url).thenApply(safe -> {
            LinkAudit audit = new LinkAudit();
            audit.link = link;
            audit.status = safe ? (short) 1 : (short) 2; // 1=approved, 2=rejected
            audit.reason = safe ? "AI verified safe" : "AI flagged as unsafe/spam";
            audit.score = safe ? BigDecimal.valueOf(100) : BigDecimal.ZERO;
            audit.createdAt = OffsetDateTime.now();
            audit.persist();

            // Auto update link status? Let's say we just log it for now or update if safe
            if (!safe && link.status == 1) {
                link.status = 2; // Auto hide if flagged
            }

            return toAuditItem(audit);
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
        l.showUrl = req.showUrl() == null ? true : req.showUrl();
        l.email = req.email();
        l.note = req.note();
        l.category = req.category();
        l.seoTitle = req.seoTitle();
        l.seoKeywords = req.seoKeywords();
        l.seoDescription = req.seoDescription();

        l.createdAt = OffsetDateTime.now();
        l.updatedAt = OffsetDateTime.now();

        l.persist();
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
        if (req.category() != null)
            l.category = req.category();
        if (req.seoTitle() != null)
            l.seoTitle = req.seoTitle();
        if (req.seoKeywords() != null)
            l.seoKeywords = req.seoKeywords();
        if (req.seoDescription() != null)
            l.seoDescription = req.seoDescription();

        l.updatedAt = OffsetDateTime.now();
        return toItem(l);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public void delete(@PathParam("id") Long id) {
        Link.deleteById(id);
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
                l.category,
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
