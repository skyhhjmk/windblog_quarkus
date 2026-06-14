package com.biliwind.blog.controller.api;

import com.biliwind.blog.model.Link;
import com.biliwind.blog.model.LinkType;
import com.biliwind.blog.service.ConfigManager;
import com.biliwind.blog.service.link.LinkApplicationRateLimitService;
import com.biliwind.blog.service.link.LinkProbeResult;
import com.biliwind.blog.service.link.LinkProbeService;
import com.biliwind.blog.service.link.LinkPublicTokenService;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.InetAddress;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Path("/api/link-applications")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class LinkApplicationApiController {

    private static final Set<String> PLACEMENT_TYPES = Set.of(
            "HOME_PAGE",
            "LINK_PAGE",
            "OTHER_PAGE"
    );

    @Inject
    RoutingContext routingContext;

    @Inject
    LinkApplicationRateLimitService rateLimitService;

    @Inject
    LinkProbeService linkProbeService;

    @Inject
    LinkPublicTokenService linkPublicTokenService;

    @Inject
    ConfigManager configManager;

    @POST
    @Transactional
    public Response apply(LinkApplicationRequest request) {
        validateApplicationRequest(request);
        String remoteAddress = routingContext.request().remoteAddress().host();
        rateLimitService.checkAndRecord(
                remoteAddress,
                resolveForwardedAddress(remoteAddress)
        );

        String normalizedUrl = request.url().trim();
        Link existingLink = Link.find("lower(url) = ?1", normalizedUrl.toLowerCase()).firstResult();
        if (existingLink != null) {
            throw new ClientErrorException("该站点已经申请或已存在", Response.Status.CONFLICT);
        }

        String siteUrl = configManager.getString("site_info", "site_url", "");
        LinkProbeResult probeResult = linkProbeService.probe(normalizedUrl, siteUrl);
        if (!probeResult.reachable()) {
            throw new ClientErrorException(
                    "服务端无法访问该站点，请确认站点已公开上线后再提交",
                    422
            );
        }

        Link application = createApplication(request, normalizedUrl, probeResult);
        application.persist();

        return Response.status(Response.Status.CREATED)
                .entity(Map.of(
                        "success", true,
                        "message", "友链申请已提交，请等待管理员审核"
                ))
                .build();
    }

    private Link createApplication(
            LinkApplicationRequest request,
            String normalizedUrl,
            LinkProbeResult probeResult
    ) {
        Link application = new Link();
        application.name = request.name().trim();
        application.url = normalizedUrl;
        application.description = blankToNull(request.description());
        application.icon = blankToNull(request.icon());
        application.email = request.email().trim();
        application.note = buildApplicationNote(request);
        application.sortOrder = 0;
        application.type = LinkType.FRIENDLY_LINK;
        application.status = 2;
        application.applicationStatus = 2;
        application.availabilityStatus = "ONLINE";
        application.backlinkStatus = "MISSING";
        if (probeResult.backlinkFound()) {
            application.backlinkStatus = "FOUND";
        }
        application.lastCheckedAt = OffsetDateTime.now(ZoneOffset.UTC);
        application.target = "_blank";
        application.redirectType = 1;
        application.showUrl = true;
        application.settings = buildApplicationSettings(request);
        application.publicToken = linkPublicTokenService.ensurePublicToken(application);
        application.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        application.updatedAt = application.createdAt;
        return application;
    }

    private Map<String, Object> buildApplicationSettings(LinkApplicationRequest request) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("applicationSource", "public");
        settings.put("placementType", request.placementType());
        settings.put("placementUrl", request.placementUrl().trim());
        putIfPresent(settings, "placementPageName", request.placementPageName());
        putIfPresent(settings, "placementDescription", request.placementDescription());
        return settings;
    }

    private String resolveForwardedAddress(String remoteAddress) {
        if (!isTrustedProxyAddress(remoteAddress)) {
            return "";
        }
        String forwardedAddress = routingContext.request().getHeader("X-Forwarded-For");
        if (forwardedAddress == null) {
            return "";
        }
        return forwardedAddress;
    }

    private boolean isTrustedProxyAddress(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(remoteAddress);
            if (address.isLoopbackAddress()) {
                return true;
            }
            if (address.isSiteLocalAddress()) {
                return true;
            }
            return address.isLinkLocalAddress();
        } catch (Exception exception) {
            return false;
        }
    }

    private void putIfPresent(Map<String, Object> settings, String key, String value) {
        String normalizedValue = blankToNull(value);
        if (normalizedValue != null) {
            settings.put(key, normalizedValue);
        }
    }

    private void validateApplicationRequest(LinkApplicationRequest request) {
        if (request == null) {
            throw new BadRequestException("申请内容不能为空");
        }
        requireText(request.name(), "站点名称不能为空");
        requireText(request.url(), "站点链接不能为空");
        requireText(request.email(), "联系邮箱不能为空");
        requireText(request.placementType(), "请选择友链放置位置");
        requireText(request.placementUrl(), "请填写友链实际放置页面");

        if (!PLACEMENT_TYPES.contains(request.placementType())) {
            throw new BadRequestException("友链放置位置无效");
        }
        if ("OTHER_PAGE".equals(request.placementType())) {
            requireText(request.placementPageName(), "请填写其他页面名称");
            requireText(request.placementDescription(), "请说明友链在其他页面中的具体位置");
        }

        validateMaximumLength(request.name(), 120, "站点名称过长");
        validateMaximumLength(request.url(), 2000, "站点链接过长");
        validateMaximumLength(request.email(), 320, "联系邮箱过长");
        validateMaximumLength(request.description(), 500, "站点描述过长");
        validateMaximumLength(request.icon(), 2000, "图标链接过长");
        validateMaximumLength(request.contact(), 200, "其他联系方式过长");
        validateMaximumLength(request.placementUrl(), 2000, "友链放置页面链接过长");
        validateMaximumLength(request.placementPageName(), 120, "页面名称过长");
        validateMaximumLength(request.placementDescription(), 500, "放置位置说明过长");

        validatePublicUrl(request.url(), "站点链接");
        validatePublicUrl(request.placementUrl(), "友链放置页面");
    }

    private void validatePublicUrl(String value, String fieldName) {
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (Exception exception) {
            throw new BadRequestException(fieldName + "格式不正确");
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new BadRequestException(fieldName + "必须使用 http 或 https");
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new BadRequestException(fieldName + "必须使用 http 或 https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new BadRequestException(fieldName + "缺少有效域名");
        }
        if (!linkProbeService.isPublicHttpUrl(value)) {
            throw new BadRequestException(fieldName + "必须指向可公开访问的网络地址");
        }
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(message);
        }
    }

    private void validateMaximumLength(String value, int maximumLength, String message) {
        if (value == null) {
            return;
        }
        if (value.length() > maximumLength) {
            throw new BadRequestException(message);
        }
    }

    private String buildApplicationNote(LinkApplicationRequest request) {
        String contact = blankToNull(request.contact());
        if (contact == null) {
            return "公开友链申请";
        }
        return "公开友链申请，补充联系方式：" + contact;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public record LinkApplicationRequest(
            String name,
            String url,
            String description,
            String icon,
            String email,
            String contact,
            String placementType,
            String placementUrl,
            String placementPageName,
            String placementDescription
    ) {
    }
}
