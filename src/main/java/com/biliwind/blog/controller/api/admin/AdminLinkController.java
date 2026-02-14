package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.AdminLinkDtos.AdminLinkItem;
import com.biliwind.blog.controller.api.admin.dto.AdminLinkDtos.LinkCreateRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminLinkDtos.LinkUpdateRequest;
import com.biliwind.blog.model.Link;
import io.quarkus.panache.common.Sort;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Path("/api/admin/links")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminLink")
public class AdminLinkController {

    @GET
    @Operation(summary = "所有友链")
    public List<AdminLinkItem> list() {
        return Link.listAll(Sort.by("sortOrder")).stream()
                .map(l -> (Link) l)
                .map(this::toItem)
                .collect(Collectors.toList());
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

        // Defaults
        l.target = "_blank";
        l.redirectType = 1;
        l.showUrl = true;
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
                l.createdAt);
    }
}
