package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.AdminCategoryDtos.AdminCategoryItem;
import com.biliwind.blog.controller.api.admin.dto.AdminCategoryDtos.CategoryCreateRequest;
import com.biliwind.blog.model.Category;
import io.quarkus.panache.common.Sort;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/admin/categories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminCategory")
public class AdminCategoryController {

    @jakarta.inject.Inject
    com.biliwind.blog.service.AuditService auditService;

    @jakarta.inject.Inject
    com.biliwind.blog.common.CacheService cacheService;

    @jakarta.inject.Inject
    jakarta.enterprise.event.Event<com.biliwind.blog.service.edge.DataSyncEvent> dataSyncEvent;

    private void invalidateCategoryCaches() {
        // 清理侧边栏分类
        cacheService.deletePattern(com.biliwind.blog.common.CacheService.Keys.SIDEBAR_CATEGORIES + "*");
        // 清理侧边栏统计
        cacheService.delete(com.biliwind.blog.common.CacheService.Keys.SIDEBAR_STATS);
    }

    @GET
    @Transactional
    @Operation(summary = "所有分类")
    public List<AdminCategoryItem> list() {
        List<Category> categories = Category.listAll(Sort.by("path"));
        Map<Long, Long> postCounts = loadPostCounts(categories);
        List<AdminCategoryItem> items = new java.util.ArrayList<>();
        for (Category c : categories) {
            items.add(toItem(c, postCounts.getOrDefault(c.id, 0L)));
        }
        return items;
    }

    @POST
    @Transactional
    @Operation(summary = "创建分类")
    public AdminCategoryItem create(CategoryCreateRequest req) {
        String slug = req.slug();
        if (slug == null || slug.isBlank()) {
            slug = com.biliwind.blog.common.helper.SlugHelper.slugify(req.name());
        } else {
            slug = slug.trim();
        }

        Category c = new Category();
        c.slug = slug;
        c.name = req.name();
        c.description = req.description();
        if (req.parentId() != null) {
            c.parent = Category.findById(req.parentId());
        }
        c.createdAt = OffsetDateTime.now();
        c.persist();
        auditService.log("category", c.id, "create", null, java.util.Map.of("name", c.name, "slug", c.slug));
        dataSyncEvent.fire(new com.biliwind.blog.service.edge.DataSyncEvent("CATEGORY", c.id, "UPSERT"));
        invalidateCategoryCaches();
        return toItem(c);
    }

    @PUT
    @Path("/{id}")
    @Transactional
    @Operation(summary = "更新分类")
    public AdminCategoryItem update(@PathParam("id") Long id, CategoryCreateRequest req) {
        Category c = Category.findById(id);
        if (c == null) throw new NotFoundException();

        String slug = req.slug();
        if (slug == null || slug.isBlank()) {
            slug = com.biliwind.blog.common.helper.SlugHelper.slugify(req.name());
        } else {
            slug = slug.trim();
        }

        c.slug = slug;
        c.name = req.name();
        c.description = req.description();
        if (req.parentId() != null) {
            c.parent = Category.findById(req.parentId());
        } else {
            c.parent = null;
        }
        c.persist();
        auditService.log("category", c.id, "update", null, java.util.Map.of("name", c.name, "slug", c.slug));
        dataSyncEvent.fire(new com.biliwind.blog.service.edge.DataSyncEvent("CATEGORY", c.id, "UPSERT"));
        invalidateCategoryCaches();
        return toItem(c);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    @Operation(summary = "删除分类")
    public void delete(@PathParam("id") Long id) {
        Category c = Category.findById(id);
        if (c == null) throw new NotFoundException();
        // 处理子分类或关联文章的逻辑通常由业务决定，这里简单处理
        auditService.log("category", c.id, "delete", java.util.Map.of("name", c.name), null);
        dataSyncEvent.fire(new com.biliwind.blog.service.edge.DataSyncEvent("CATEGORY", id, "DELETE"));
        Category.deleteById(id);
        invalidateCategoryCaches();
    }

    @POST
    @Path("/re-scan")
    @Transactional
    @Operation(summary = "重新扫描全表计算分类文章数量")
    public Response reScan() {
        List<Category> allCategories = Category.listAll();
        Map<Long, Long> postCounts = loadPostCounts(allCategories);
        for (Category c : allCategories) {
            c.postCount = postCounts.getOrDefault(c.id, 0L);
            c.persist();
        }
        invalidateCategoryCaches();
        return Response.ok(java.util.Map.of("success", true, "message", "扫描完成")).build();
    }

    private Map<Long, Long> loadPostCounts(List<Category> categories) {
        if (categories.isEmpty()) {
            return Map.of();
        }

        List<Long> categoryIds = categories.stream().map(category -> category.id).toList();
        Map<Long, Long> postCounts = new HashMap<>();
        List<Object[]> rows = Category.getEntityManager().createNativeQuery("""
                        select related_posts.category_id, count(distinct related_posts.post_id)
                        from (
                            select p.category_id, p.id as post_id
                            from posts p
                            where p.status = 1 and p.deleted_at is null and p.visibility = 0
                              and p.published_revision_id is not null
                              and p.category_id in (:legacyCategoryIds)
                            union all
                            select pc.category_id, p.id as post_id
                            from post_categories pc
                            join posts p on p.id = pc.post_id
                            where p.status = 1 and p.deleted_at is null and p.visibility = 0
                              and p.published_revision_id is not null
                              and pc.category_id in (:relationCategoryIds)
                        ) related_posts
                        group by related_posts.category_id
                        """)
                .setParameter("legacyCategoryIds", categoryIds)
                .setParameter("relationCategoryIds", categoryIds)
                .getResultList();
        for (Object[] row : rows) {
            postCounts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return postCounts;
    }

    private AdminCategoryItem toItem(Category c) {
        return toItem(c, loadPostCounts(List.of(c)).getOrDefault(c.id, 0L));
    }

    private AdminCategoryItem toItem(Category c, Long postCount) {
        return new AdminCategoryItem(
                c.id,
                c.parent != null ? c.parent.id : null,
                c.slug,
                c.name,
                c.description,
                c.path,
                c.createdAt,
                postCount);
    }
}
