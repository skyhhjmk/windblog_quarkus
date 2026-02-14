package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.AdminTagDtos.AdminTagItem;
import com.biliwind.blog.controller.api.admin.dto.AdminTagDtos.TagCreateRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminTagDtos.TagUpdateRequest;
import com.biliwind.blog.model.Tag;
import io.quarkus.panache.common.Sort;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Path("/api/admin/tags")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@org.eclipse.microprofile.openapi.annotations.tags.Tag(name = "AdminTag")
public class AdminTagController {

    @GET
    @Operation(summary = "所有标签")
    public List<AdminTagItem> list() {
        return com.biliwind.blog.model.Tag.listAll(Sort.by("createdAt").descending()).stream()
                .map(t -> (com.biliwind.blog.model.Tag) t)
                .map(this::toItem)
                .collect(Collectors.toList());
    }

    @POST
    @Transactional
    @Operation(summary = "创建标签")
    public AdminTagItem create(TagCreateRequest req) {
        com.biliwind.blog.model.Tag t = new com.biliwind.blog.model.Tag();
        t.slug = req.slug();
        t.name = req.name();
        t.description = req.description();
        t.createdAt = OffsetDateTime.now();
        t.persist();
        return toItem(t);
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public AdminTagItem update(@PathParam("id") Long id, TagUpdateRequest req) {
        com.biliwind.blog.model.Tag t = com.biliwind.blog.model.Tag.findById(id);
        if (t == null)
            throw new NotFoundException();
        t.slug = req.slug();
        t.name = req.name();
        t.description = req.description();
        return toItem(t);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public void delete(@PathParam("id") Long id) {
        com.biliwind.blog.model.Tag.deleteById(id);
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
