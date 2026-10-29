package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.AdminArticleMapDtos.ArticleMap;
import com.biliwind.blog.model.*;
import com.biliwind.blog.service.*;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.*;

@Path("/api/admin/seo")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminSeoController {
    @Inject SeoManagementService seo;
    @Inject InternalLinkGraphService linkGraph;
    @Inject ArticleMapService articleMap;
    @Inject SeoAuditService audit;
    @Inject GoogleSearchConsoleService gsc;
    @Inject EntityManager em;
    @Inject SeoAiService ai;
    @Inject AuditService auditLog;

    @GET @Path("/redirects")
    public Map<String, Object> redirects(@QueryParam("page") @DefaultValue("1") int page) {
        page = page(page);
        return Map.of("items", SeoRedirect.find("order by id desc").page(io.quarkus.panache.common.Page.of(page - 1, 50)).list(),
                "total", SeoRedirect.count(), "page", page);
    }

    @POST @Path("/redirects")
    public SeoRedirect saveRedirect(RedirectRequest request) {
        if (request == null) throw new BadRequestException();
        SeoRedirect result = seo.saveRedirect(request.id(), request.source(), request.target(), request.status(), request.enabled());
        auditLog.log("seo_redirect", result.id, "save", null, Map.of("source", result.source, "target", result.target, "status", result.status));
        return result;
    }

    @DELETE @Path("/redirects/{id}") @Transactional
    public void deleteRedirect(@PathParam("id") Long id) {
        if (!SeoRedirect.deleteById(id)) throw new NotFoundException();
    }

    @GET @Path("/not-found")
    public Map<String, Object> notFound(@QueryParam("page") @DefaultValue("1") int page) {
        page = page(page);
        List<Object[]> rows = em.createNativeQuery("select path, referer, hits, last_seen, resolved from seo_not_found order by last_seen desc")
                .setFirstResult((page - 1) * 50).setMaxResults(50).getResultList();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object[] row : rows) items.add(Map.of("path", row[0], "referer", Objects.toString(row[1], ""),
                "hits", row[2], "lastSeen", row[3].toString(), "resolved", row[4]));
        return Map.of("items", items, "page", page,
                "total", em.createNativeQuery("select count(*) from seo_not_found").getSingleResult());
    }

    @POST @Path("/not-found/resolve") @Transactional
    public void resolveNotFound(Map<String, String> request) {
        if (request == null || request.get("path") == null) throw new BadRequestException();
        em.createNativeQuery("update seo_not_found set resolved = true where path = ?1")
                .setParameter(1, request.get("path")).executeUpdate();
    }

    @GET @Path("/links")
    public Map<String, Object> links(@QueryParam("page") @DefaultValue("1") int page,
                                    @QueryParam("language") @DefaultValue("zh-cn") String language) {
        return audit.links(page(page), language(language));
    }

    @GET @Path("/link-graph/status")
    public Map<String, Boolean> graphStatus() { return Map.of("enabled", linkGraph.enabled()); }

    @GET @Path("/posts/{id}/link-graph")
    public jakarta.ws.rs.core.Response graph(@PathParam("id") Long id,
            @QueryParam("language") @DefaultValue("zh-cn") String language,
            @QueryParam("depth") @DefaultValue("3") int depth,
            @QueryParam("maxNodes") @DefaultValue("150") int maxNodes) {
        return jakarta.ws.rs.core.Response.ok(linkGraph.adminGraph(id, language(language), depth, maxNodes))
                .header("Cache-Control", "no-store").build();
    }

    @GET @Path("/posts/{id}/article-map")
    public ArticleMap articleMap(@PathParam("id") Long id,
                                 @QueryParam("language") @DefaultValue("zh-cn") String language,
                                 @QueryParam("depth") @DefaultValue("2") int depth,
                                 @QueryParam("maxNodes") @DefaultValue("80") int maxNodes,
                                 @QueryParam("relatedPerNode") @DefaultValue("4") int relatedPerNode) {
        return articleMap.build(id, language(language), depth, maxNodes, relatedPerNode);
    }

    @GET @Path("/posts/{id}/audit")
    public Map<String, Object> inspect(@PathParam("id") Long id, @QueryParam("language") @DefaultValue("zh-cn") String language,
                                      @QueryParam("keyword") @DefaultValue("") String keyword) {
        Post post = post(id);
        if (post.publishedRevision == null) throw new BadRequestException("文章尚无已发布版本");
        return audit.inspect(post, language(language), keyword);
    }

    @GET @Path("/posts/{id}/policy")
    public Map<String, Object> policy(@PathParam("id") Long id) {
        post(id);
        SeoPostPolicy policy = SeoPostPolicy.find("postId", id).firstResult();
        return Map.of("noindex", policy != null && policy.noindex, "nofollow", policy != null && policy.nofollow);
    }

    @POST @Path("/posts/{id}/policy") @Transactional
    public Map<String, Object> savePolicy(@PathParam("id") Long id, Map<String, Boolean> request) {
        post(id);
        if (request == null || !request.containsKey("noindex") || !request.containsKey("nofollow")) throw new BadRequestException();
        SeoPostPolicy policy = SeoPostPolicy.find("postId", id).firstResult();
        if (policy == null) { policy = new SeoPostPolicy(); policy.postId = id; }
        policy.noindex = Boolean.TRUE.equals(request.get("noindex")); policy.nofollow = Boolean.TRUE.equals(request.get("nofollow"));
        policy.persist();
        auditLog.log("post", id, "seo_policy_update", null, request);
        return Map.of("noindex", policy.noindex, "nofollow", policy.nofollow);
    }

    @POST @Path("/posts/{id}/suggest")
    @io.smallrye.common.annotation.Blocking
    public Map<String, String> suggest(@PathParam("id") Long id, @QueryParam("language") @DefaultValue("zh-cn") String language) {
        return ai.suggest(post(id), language(language));
    }

    @GET @Path("/gsc")
    public Map<String, Object> gscStatus() { return gsc.status(); }

    @GET @Path("/gsc/analytics")
    public com.fasterxml.jackson.databind.JsonNode analytics(@QueryParam("start") String start, @QueryParam("end") String end,
            @QueryParam("dimension") @DefaultValue("query") String dimension, @QueryParam("offset") @DefaultValue("0") int offset) {
        return gsc.query(start, end, dimension, offset);
    }

    private int page(int page) {
        if (page < 1 || page > 1000000) throw new BadRequestException("页码无效");
        return page;
    }
    private String language(String language) {
        String normalized = com.biliwind.blog.common.helper.LanguageHelper.normalizeToSupportedLang(language);
        if (normalized == null) throw new BadRequestException("不支持的语言");
        return normalized;
    }
    private Post post(Long id) {
        Post post = Post.findById(id);
        if (post == null || post.deletedAt != null) throw new NotFoundException();
        return post;
    }
    public record RedirectRequest(Long id, String source, String target, int status, boolean enabled) {}
}
