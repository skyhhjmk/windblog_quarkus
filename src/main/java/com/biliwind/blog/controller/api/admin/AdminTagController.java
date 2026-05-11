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
import java.util.List;

@Path("/api/admin/tags")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@org.eclipse.microprofile.openapi.annotations.tags.Tag(name = "AdminTag")
public class AdminTagController {

    @jakarta.inject.Inject
    com.biliwind.blog.service.AuditService auditService;

    @GET
    @Operation(summary = "所有标签")
    public List<AdminTagItem> list() {
        List<com.biliwind.blog.model.Tag> tags = com.biliwind.blog.model.Tag.listAll(Sort.by("createdAt").descending());
        List<AdminTagItem> items = new java.util.ArrayList<>();
        for (com.biliwind.blog.model.Tag t : tags) {
            items.add(toItem(t));
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
        return toItem(t);
    }

    @Transactional
    public void delete(@PathParam("id") Long id) {
        com.biliwind.blog.model.Tag t = com.biliwind.blog.model.Tag.findById(id);
        if (t != null) {
            auditService.log("tag", t.id, "delete", java.util.Map.of("name", t.name), null);
            com.biliwind.blog.model.Tag.deleteById(id);
        }
    }

    private AdminTagItem toItem(com.biliwind.blog.model.Tag t) {
        return new AdminTagItem(
                t.id,
                t.slug,
                t.name,
                t.description,
                t.createdAt);
    }
}
