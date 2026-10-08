package com.biliwind.blog.controller;

import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.model.Link;
import com.biliwind.blog.model.LinkType;
import com.biliwind.blog.service.link.LinkMonitorPolicy;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Link page for PJAX testing.
 */
@Path("/link")
public class LinkController {

        @Inject
        @Location("blog/link.html")
        Template link;

    @Inject
    @Location("blog/link.content.html")
    Template linkContent;

    @Inject
    @Location("blog/link-apply.html")
    Template linkApply;

    @Inject
    @Location("blog/link-apply.content.html")
    Template linkApplyContent;

    @Inject
    @Location("blog/link-detail.html")
    Template linkDetail;

    @Inject
    @Location("blog/link-detail.content.html")
    Template linkDetailContent;

        @Inject
        LanguageContext languageContext;

    @Inject
    @Location("system/go.html")
    Template goTemplate;

    @Inject
    com.biliwind.blog.service.link.LinkPublicTokenService linkPublicTokenService;

        @GET
        @Produces(MediaType.TEXT_HTML)
        public TemplateInstance index(
                        @Context HttpHeaders httpHeaders,
                        @QueryParam("type") String type) {
                return render(httpHeaders, type);
        }

    @GET
    @Path("/apply")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance application(@Context HttpHeaders httpHeaders) {
        Template template = linkApply;
        if (PjaxHelper.isPjaxRequest(httpHeaders)) {
            template = linkApplyContent;
        }
        return template
                .data("pageTitle", "申请友情链接")
                .data("language", languageContext.getLang());
    }

        private TemplateInstance render(HttpHeaders httpHeaders, String type) {
            Template template = link;
            if (PjaxHelper.isPjaxRequest(httpHeaders)) {
                template = linkContent;
            }
                
                List<Link> eligibleLinks = Link.<Link>list(
                                "status = ?1 and type != ?2 order by sortOrder asc, createdAt desc",
                                (short) 1,
                                LinkType.EXTERNAL_ARTICLE)
                        .stream()
                        .filter(LinkMonitorPolicy::isVisibleOnPublicPage)
                        .toList();
                List<Link> links;
            if (type == null || type.isBlank() || type.equalsIgnoreCase("All")) {
                links = eligibleLinks;
                } else {
                        LinkType linkType = parseLinkType(type);
                if (linkType != null && linkType != LinkType.EXTERNAL_ARTICLE) {
                                links = eligibleLinks.stream()
                                        .filter(item -> item.type == linkType)
                                        .toList();
                        } else {
                    links = eligibleLinks;
                        }
                }

            // 只获取当前数据库中已存在的链接分类，且排除文章外部链接
            List<LinkType> activeTypes = eligibleLinks.stream()
                    .map(item -> item.type)
                    .distinct()
                    .toList();
                
                return template
                        .data("pageTitle", "友情链接")
                                .data("language", languageContext.getLang())
                        .data("linkTypes", activeTypes)
                        .data("currentType", type)
                                .data("links", links);
        }
        
        private LinkType parseLinkType(String type) {
            if (type == null || type.isBlank()) {
                return null;
            }
            // Try by enum name
            try {
                return LinkType.valueOf(type);
            } catch (IllegalArgumentException e) {
                // Fallback: search by code or legacy names
                if ("friend_links".equals(type)) {
                    return LinkType.FRIENDLY_LINK;
                } else if ("other_links".equals(type)) {
                    return LinkType.OTHER;
                }

                // Try parsing as code
                try {
                    short code = Short.parseShort(type);
                    return LinkType.fromCode(code);
                } catch (Exception ex) {
                    return null;
                }
            }
        }

    @GET
    @Path("/{id}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance detail(
            @Context HttpHeaders httpHeaders,
            @PathParam("id") Long id) {
        Link linkEntity = Link.findById(id);
        if (linkEntity == null || linkEntity.status != 1
                || !LinkMonitorPolicy.isVisibleOnPublicPage(linkEntity)) {
            throw new WebApplicationException(404);
        }

        Template template = linkDetail;
        if (PjaxHelper.isPjaxRequest(httpHeaders)) {
            template = linkDetailContent;
        }

        String pageTitle = linkEntity.seoTitle;
        if (pageTitle == null || pageTitle.isBlank()) {
            pageTitle = linkEntity.name + " - 资源详情";
        }

        return template
                .data("pageTitle", pageTitle)
                .data("language", languageContext.getLang())
                .data("seoTitle", linkEntity.seoTitle)
                .data("pageKeywords", linkEntity.seoKeywords)
                .data("pageDescription", linkEntity.seoDescription)
                .data("link", linkEntity);
    }

    @GET
    @Path("/go/{publicToken}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance go(@PathParam("publicToken") String publicToken) {
        Link linkEntity = linkPublicTokenService.findByPublicToken(publicToken);
        if (linkEntity == null) {
            throw new WebApplicationException(404);
        }

        if (linkEntity.status != 1 || !LinkMonitorPolicy.isVisibleOnPublicPage(linkEntity)) {
            throw new WebApplicationException(404);
        }

        // 如果是直接跳转类型，则执行 302
        if (linkEntity.redirectType == 1) {
            throw new RedirectionException(jakarta.ws.rs.core.Response.Status.SEE_OTHER, java.net.URI.create(linkEntity.url));
        }

        String linkName = linkEntity.name;
        if (linkName == null || linkName.isBlank()) {
            linkName = "本站";
        }

        // 默认显示中间页
        return goTemplate
                .data("pageTitle", "正在离开 " + linkName)
                .data("language", languageContext.getLang())
                .data("targetUrl", linkEntity.url)
                .data("linkName", linkEntity.name);
    }

}
