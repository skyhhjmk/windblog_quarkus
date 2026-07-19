package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.*;
import com.biliwind.blog.service.security.SafeExternalHttpService;
import com.biliwind.blog.service.edge.DataSyncEvent;
import com.biliwind.blog.service.repost.*;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台转载、token、点击证据和封禁管理 API。
 */
@Path("/api/admin/repost")
@jakarta.ws.rs.Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminRepost")
public class AdminRepostController {

    @Inject
    AffiliateTokenService affiliateTokenService;

    @Inject
    SafeExternalHttpService safeExternalHttpService;

    @Inject
    RepostLicenseService repostLicenseService;

    @Inject
    RepostDomainService repostDomainService;

    @Inject
    RepostDetectionService repostDetectionService;

    @Inject
    EdgeClickLogSyncService edgeClickLogSyncService;

    @Inject
    Event<DataSyncEvent> dataSyncEvent;

    @GET
    @Path("/licenses")
    @Operation(summary = "转载授权列表")
    public Map<String, Object> listLicenses(@QueryParam("page") @DefaultValue("1") int page,
                                            @QueryParam("pageSize") @DefaultValue("20") int pageSize) {
        List<RepostLicense> licenses = RepostLicense.find("order by createdAt desc").page(Page.of(page - 1, pageSize)).list();
        long total = RepostLicense.count();
        List<Map<String, Object>> items = new ArrayList<>();
        for (RepostLicense license : licenses) {
            items.add(toLicenseItem(license));
        }
        return pageResponse(items, total, page, pageSize);
    }

    @GET
    @Path("/tokens")
    @Operation(summary = "短链 token 列表")
    public Map<String, Object> listTokens(@QueryParam("page") @DefaultValue("1") int page,
                                          @QueryParam("pageSize") @DefaultValue("20") int pageSize) {
        List<AffiliateToken> tokens = AffiliateToken.find("order by createdAt desc").page(Page.of(page - 1, pageSize)).list();
        long total = AffiliateToken.count();
        List<Map<String, Object>> items = new ArrayList<>();
        for (AffiliateToken token : tokens) {
            items.add(toTokenItem(token));
        }
        return pageResponse(items, total, page, pageSize);
    }

    @GET
    @Path("/click-events")
    @Operation(summary = "点击证据链")
    public Map<String, Object> listClickEvents(@QueryParam("page") @DefaultValue("1") int page,
                                               @QueryParam("pageSize") @DefaultValue("20") int pageSize) {
        List<RedirectClickEvent> events = RedirectClickEvent.find("order by clickedAt desc").page(Page.of(page - 1, pageSize)).list();
        long total = RedirectClickEvent.count();
        List<Map<String, Object>> items = new ArrayList<>();
        for (RedirectClickEvent event : events) {
            items.add(toClickEventItem(event));
        }
        return pageResponse(items, total, page, pageSize);
    }

    @GET
    @Path("/suspicious")
    @Operation(summary = "可疑转载列表")
    public Map<String, Object> listSuspiciousReposts(@QueryParam("page") @DefaultValue("1") int page,
                                                     @QueryParam("pageSize") @DefaultValue("20") int pageSize) {
        List<SuspiciousRepost> reposts = SuspiciousRepost.find("order by lastSeenAt desc").page(Page.of(page - 1, pageSize)).list();
        long total = SuspiciousRepost.count();
        List<Map<String, Object>> items = new ArrayList<>();
        for (SuspiciousRepost suspiciousRepost : reposts) {
            items.add(toSuspiciousItem(suspiciousRepost));
        }
        return pageResponse(items, total, page, pageSize);
    }

    @POST
    @Path("/tokens/{id}/revoke")
    @Operation(summary = "撤销 token")
    public Response revokeToken(@PathParam("id") Long id) {
        affiliateTokenService.revokeToken(id);
        return Response.ok(simpleSuccess()).build();
    }

    @POST
    @Path("/licenses/{id}/revoke")
    @Operation(summary = "撤销转载授权")
    public Response revokeLicense(@PathParam("id") Long id) {
        repostLicenseService.revokeLicense(id);
        return Response.ok(simpleSuccess()).build();
    }

    @POST
    @Path("/blocked-domains")
    @Transactional
    @Operation(summary = "封禁域名")
    public Response blockDomain(BlockDomainRequest request) {
        String domainName = repostDomainService.normalizeDomain(request.domain);
        if (domainName == null || domainName.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(message("域名不能为空")).build();
        }

        BlockedDomain blockedDomain = BlockedDomain.find("domainName = ?1", domainName).firstResult();
        if (blockedDomain == null) {
            blockedDomain = new BlockedDomain();
            blockedDomain.domainName = domainName;
            blockedDomain.persist();
        }
        blockedDomain.reason = request.reason;
        blockedDomain.status = 1;
        dataSyncEvent.fire(new DataSyncEvent("BLOCKED_DOMAIN", blockedDomain.id, "UPSERT"));
        return Response.ok(toBlockedDomainItem(blockedDomain)).build();
    }

    @POST
    @Path("/risk-devices")
    @Transactional
    @Operation(summary = "封禁风险设备")
    public Response blockDevice(BlockDeviceRequest request) {
        if (request.deviceRiskId == null || request.deviceRiskId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(message("deviceRiskId 不能为空")).build();
        }

        RiskDevice riskDevice = RiskDevice.find("deviceRiskId = ?1", request.deviceRiskId).firstResult();
        if (riskDevice == null) {
            riskDevice = new RiskDevice();
            riskDevice.deviceRiskId = request.deviceRiskId.trim();
            riskDevice.persist();
        }
        riskDevice.reason = request.reason;
        riskDevice.status = 1;
        dataSyncEvent.fire(new DataSyncEvent("RISK_DEVICE", riskDevice.id, "UPSERT"));
        return Response.ok(toRiskDeviceItem(riskDevice)).build();
    }

    @POST
    @Path("/affiliate-links")
    @Transactional
    @Operation(summary = "创建商业链接")
    public Response createAffiliateLink(AffiliateLinkRequest request) {
        String targetDomain = repostDomainService.normalizeDomainFromUrl(request.targetUrl);
        if (targetDomain == null || targetDomain.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(message("targetUrl 无法识别域名")).build();
        }

        AffiliateLink affiliateLink = new AffiliateLink();
        affiliateLink.name = request.name;
        affiliateLink.targetUrl = request.targetUrl;
        affiliateLink.targetDomain = targetDomain;
        affiliateLink.note = request.note;
        affiliateLink.status = 1;
        affiliateLink.persist();
        dataSyncEvent.fire(new DataSyncEvent("AFFILIATE_LINK", affiliateLink.id, "UPSERT"));
        return Response.ok(toAffiliateLinkItem(affiliateLink)).build();
    }

    @POST
    @Path("/licenses/{id}/check")
    @Transactional
    @Operation(summary = "检查授权转载页合规性")
    public Response checkLicenseCompliance(@PathParam("id") Long id) {
        RepostLicense license = RepostLicense.findById(id);
        if (license == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(message("转载授权不存在")).build();
        }

        try {
            String responseHtml = safeExternalHttpService.get(license.targetUrl, "WindBlog-Repost-Inspector/1.0").bodyAsText();
            Document document = Jsoup.parse(responseHtml, license.targetUrl);
            String html = document.html();
            String originalUrl = repostLicenseService.buildPostUrl(license.article);
            boolean hasOriginalLink = html.contains(originalUrl);
            boolean hasLicenseCode = html.contains(license.code);
            boolean hasGoLink = html.contains("/r/");
            boolean hasAffiliateToken = hasGoLink;
            String snippetSummary = document.text();
            if (snippetSummary.length() > 240) {
                snippetSummary = snippetSummary.substring(0, 240);
            }
            Map<String, Object> evidence = repostDetectionService.buildComplianceEvidence(
                    license.targetUrl,
                    hasOriginalLink,
                    hasLicenseCode,
                    hasGoLink,
                    hasAffiliateToken,
                    snippetSummary
            );
            return Response.ok(evidence).build();
        } catch (Exception exception) {
            return Response.status(Response.Status.BAD_GATEWAY).entity(message("检测失败：" + exception.getMessage())).build();
        }
    }

    @POST
    @Path("/click-events/merge")
    @Operation(summary = "边缘点击日志幂等合并")
    public Response mergeClickEvent(EdgeClickLogSyncService.ClickEventMergeRequest request) {
        boolean merged = edgeClickLogSyncService.mergeClickEvent(request);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("merged", merged);
        return Response.ok(body).build();
    }

    private Map<String, Object> toLicenseItem(RepostLicense license) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", license.id);
        item.put("code", license.code);
        item.put("postId", resolveId(license.article));
        item.put("viewerUserId", resolveId(license.viewerUser));
        item.put("allowedDomain", license.allowedDomain);
        item.put("targetUrl", license.targetUrl);
        item.put("status", license.status);
        item.put("createdAt", license.createdAt);
        return item;
    }

    private Map<String, Object> toTokenItem(AffiliateToken token) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", token.id);
        item.put("shortDisplay", token.shortDisplay);
        item.put("tokenType", token.tokenType);
        item.put("status", token.status);
        item.put("postId", resolveId(token.article));
        item.put("affiliateLinkId", resolveId(token.affiliateLink));
        item.put("repostLicenseId", resolveId(token.repostLicense));
        item.put("allowedDomain", token.allowedDomain);
        item.put("createdAt", token.createdAt);
        return item;
    }

    private Map<String, Object> toClickEventItem(RedirectClickEvent event) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", event.id);
        item.put("eventKey", event.eventKey);
        item.put("tokenId", resolveId(event.affiliateToken));
        item.put("refererDomain", event.refererDomain);
        item.put("refererCategory", event.refererCategory);
        item.put("deviceRiskId", event.deviceRiskId);
        item.put("riskScore", event.riskScore);
        item.put("sourceNodeId", event.sourceNodeId);
        item.put("clickedAt", event.clickedAt);
        return item;
    }

    private Map<String, Object> toSuspiciousItem(SuspiciousRepost suspiciousRepost) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", suspiciousRepost.id);
        item.put("tokenId", resolveId(suspiciousRepost.affiliateToken));
        item.put("postId", resolveId(suspiciousRepost.article));
        item.put("domain", suspiciousRepost.suspiciousDomain);
        item.put("clickCount", suspiciousRepost.clickCount);
        item.put("riskScore", suspiciousRepost.riskScore);
        item.put("firstSeenAt", suspiciousRepost.firstSeenAt);
        item.put("lastSeenAt", suspiciousRepost.lastSeenAt);
        item.put("evidence", suspiciousRepost.evidenceJson);
        return item;
    }

    private Map<String, Object> toBlockedDomainItem(BlockedDomain blockedDomain) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", blockedDomain.id);
        item.put("domainName", blockedDomain.domainName);
        item.put("reason", blockedDomain.reason);
        item.put("status", blockedDomain.status);
        return item;
    }

    private Map<String, Object> toRiskDeviceItem(RiskDevice riskDevice) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", riskDevice.id);
        item.put("deviceRiskId", riskDevice.deviceRiskId);
        item.put("reason", riskDevice.reason);
        item.put("status", riskDevice.status);
        return item;
    }

    private Map<String, Object> toAffiliateLinkItem(AffiliateLink affiliateLink) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", affiliateLink.id);
        item.put("name", affiliateLink.name);
        item.put("targetUrl", affiliateLink.targetUrl);
        item.put("targetDomain", affiliateLink.targetDomain);
        item.put("status", affiliateLink.status);
        return item;
    }

    private Long resolveId(Object entity) {
        if (entity instanceof AffiliateLink affiliateLink) {
            return affiliateLink.id;
        }
        if (entity instanceof AffiliateToken affiliateToken) {
            return affiliateToken.id;
        }
        if (entity instanceof RepostLicense repostLicense) {
            return repostLicense.id;
        }
        if (entity instanceof com.biliwind.blog.model.Post post) {
            return post.id;
        }
        if (entity instanceof com.biliwind.blog.model.User user) {
            return user.id;
        }
        return null;
    }

    private Map<String, Object> pageResponse(List<Map<String, Object>> items, long total, int page, int pageSize) {
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        body.put("total", total);
        body.put("page", page);
        body.put("pageSize", pageSize);
        return body;
    }

    private Map<String, Object> simpleSuccess() {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        return body;
    }

    private Map<String, Object> message(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", message);
        return body;
    }

    public static class BlockDomainRequest {
        public String domain;
        public String reason;
    }

    public static class BlockDeviceRequest {
        public String deviceRiskId;
        public String reason;
    }

    public static class AffiliateLinkRequest {
        public String name;
        public String targetUrl;
        public String note;
    }
}
