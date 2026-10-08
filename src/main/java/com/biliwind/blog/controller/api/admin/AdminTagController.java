package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.AdminTagDtos.AdminTagItem;
import com.biliwind.blog.controller.api.admin.dto.AdminTagDtos.TagCreateRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminTagDtos.TagUpdateRequest;
import io.quarkus.panache.common.Sort;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/admin/tags")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@org.eclipse.microprofile.openapi.annotations.tags.Tag(name = "AdminTag")
public class AdminTagController {

    @jakarta.inject.Inject
    com.biliwind.blog.service.AuditService auditService;

    @jakarta.inject.Inject
    com.biliwind.blog.common.CacheService cacheService;

    @jakarta.inject.Inject
    jakarta.enterprise.event.Event<com.biliwind.blog.service.edge.DataSyncEvent> dataSyncEvent;

    private void invalidateTagCaches() {
        // 清理侧边栏标签
        cacheService.deletePattern(com.biliwind.blog.common.CacheService.Keys.SIDEBAR_TAGS + "*");
        // 清理侧边栏统计
        cacheService.delete(com.biliwind.blog.common.CacheService.Keys.SIDEBAR_STATS);
    }

    @GET
    @Transactional
    @Operation(summary = "所有标签")
    public List<AdminTagItem> list() {
        List<com.biliwind.blog.model.Tag> tags = com.biliwind.blog.model.Tag.listAll(Sort.by("createdAt").descending());
        Map<Long, Long> postCounts = loadPostCounts(tags);
        List<AdminTagItem> items = new java.util.ArrayList<>();
        for (com.biliwind.blog.model.Tag t : tags) {
            items.add(toItem(t, postCounts.getOrDefault(t.id, 0L)));
        }
        return items;
    }

    @POST
    @Transactional
    @Operation(summary = "创建标签")
    public AdminTagItem create(TagCreateRequest req) {
        String slug = req.slug();
        if (slug == null || slug.isBlank()) {
            slug = com.biliwind.blog.common.helper.SlugHelper.slugify(req.name());
        } else {
            slug = slug.trim();
        }

        com.biliwind.blog.model.Tag t = new com.biliwind.blog.model.Tag();
        t.slug = slug;
        t.name = req.name();
        t.description = req.description();
        t.createdAt = OffsetDateTime.now();
        t.persist();
        auditService.log("tag", t.id, "create", null, java.util.Map.of("name", t.name, "slug", t.slug));
        invalidateTagCaches();
        dataSyncEvent.fire(new com.biliwind.blog.service.edge.DataSyncEvent("TAG", t.id, "UPSERT"));
        return toItem(t);
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public AdminTagItem update(@PathParam("id") Long id, TagUpdateRequest req) {
        com.biliwind.blog.model.Tag t = com.biliwind.blog.model.Tag.findById(id);
        if (t == null)
            throw new NotFoundException();

        String slug = req.slug();
        if (slug == null || slug.isBlank()) {
            slug = com.biliwind.blog.common.helper.SlugHelper.slugify(req.name());
        } else {
            slug = slug.trim();
        }

        t.slug = slug;
        t.name = req.name();
        t.description = req.description();
        auditService.log("tag", t.id, "update", null, java.util.Map.of("name", t.name, "slug", t.slug));
        invalidateTagCaches();
        dataSyncEvent.fire(new com.biliwind.blog.service.edge.DataSyncEvent("TAG", t.id, "UPSERT"));
        return toItem(t);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    @Operation(summary = "删除标签")
    public void delete(@PathParam("id") Long id) {
        com.biliwind.blog.model.Tag t = com.biliwind.blog.model.Tag.findById(id);
        if (t != null) {
            auditService.log("tag", t.id, "delete", java.util.Map.of("name", t.name), null);
            dataSyncEvent.fire(new com.biliwind.blog.service.edge.DataSyncEvent("TAG", id, "DELETE"));
            com.biliwind.blog.model.Tag.deleteById(id);
            invalidateTagCaches();
        }
    }

    private AdminTagItem toItem(com.biliwind.blog.model.Tag t) {
        return toItem(t, loadPostCounts(List.of(t)).getOrDefault(t.id, 0L));
    }

    private AdminTagItem toItem(com.biliwind.blog.model.Tag t, Long postCount) {
        return new AdminTagItem(
                t.id,
                t.slug,
                t.name,
                t.description,
                t.createdAt,
                postCount);
    }

    private Map<Long, Long> loadPostCounts(List<com.biliwind.blog.model.Tag> tags) {
        if (tags.isEmpty()) {
            return Map.of();
        }

        List<Long> tagIds = tags.stream().map(tag -> tag.id).toList();
        Map<Long, Long> postCounts = new HashMap<>();
        List<Object[]> rows = com.biliwind.blog.model.PostTag.getEntityManager().createQuery(
                        "select tag.id, count(post.id) from PostTag "
                                + "where tag.id in ?1 and post.status = ?2 and post.deletedAt is null "
                                + "and post.visibility = 0 and post.publishedRevision is not null group by tag.id",
                        Object[].class)
                .setParameter(1, tagIds)
                .setParameter(2, com.biliwind.blog.model.PostStatus.PUBLISHED)
                .getResultList();
        for (Object[] row : rows) {
            postCounts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return postCounts;
    }
}
