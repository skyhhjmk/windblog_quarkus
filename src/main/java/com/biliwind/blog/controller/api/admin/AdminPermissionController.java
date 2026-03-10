package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.controller.api.admin.dto.AdminPermissionDtos.PermissionRoleItem;
import com.biliwind.blog.controller.api.admin.dto.AdminPermissionDtos.RoleCreateRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminPermissionDtos.RoleUpdateRequest;
import com.biliwind.blog.model.UploadRole;
import com.biliwind.blog.service.UploadRoleService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Path("/api/admin/permissions/roles")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminPermission")
public class AdminPermissionController {

    @Inject
    UploadRoleService uploadRoleService;

    @Inject
    AdminRequestContext adminRequestContext;

    private static UploadRoleService.UploadRoleUpdateRequest toServiceRequest(RoleCreateRequest request) {
        return new UploadRoleService.UploadRoleUpdateRequest(
                request.displayName(),
                request.description(),
                request.canUpload(),
                request.allowedMimeTypes(),
                request.maxSingleUploadBytes(),
                request.maxTotalUploadBytes());
    }

    private static UploadRoleService.UploadRoleUpdateRequest toServiceRequest(RoleUpdateRequest request) {
        return new UploadRoleService.UploadRoleUpdateRequest(
                request.displayName(),
                request.description(),
                request.canUpload(),
                request.allowedMimeTypes(),
                request.maxSingleUploadBytes(),
                request.maxTotalUploadBytes());
    }

    @GET
    @Operation(summary = "列出所有权限角色")
    public List<PermissionRoleItem> list() {
        return uploadRoleService.listRoles().stream()
                .map(this::toItem)
                .toList();
    }

    @POST
    @Transactional
    @Operation(summary = "创建角色")
    public PermissionRoleItem create(RoleCreateRequest request) {
        ensureSuperAdmin();
        String roleName = validateName(request.name());
        return toItem(uploadRoleService.upsert(roleName, toServiceRequest(request)));
    }

    @PUT
    @Path("/{name}")
    @Transactional
    @Operation(summary = "更新角色")
    public PermissionRoleItem update(@PathParam("name") String name,
                                     RoleUpdateRequest request) {
        ensureSuperAdmin();
        return toItem(uploadRoleService.upsert(validateName(name), toServiceRequest(request)));
    }

    @DELETE
    @Path("/{name}")
    @Transactional
    @Operation(summary = "删除角色")
    public void delete(@PathParam("name") String name) {
        ensureSuperAdmin();
        String roleName = validateName(name);
        if (RoleConstant.DEFAULT_ROLES.contains(roleName)) {
            throw new BadRequestException("默认角色不可删除");
        }
        uploadRoleService.delete(roleName);
    }

    private void ensureSuperAdmin() {
        if (!adminRequestContext.isSuperAdmin()) {
            throw new ForbiddenException("仅超级管理员可执行此操作");
        }
    }

    private PermissionRoleItem toItem(UploadRole role) {
        return new PermissionRoleItem(
                role.name,
                role.displayName,
                role.description,
                role.canUpload,
                role.allowedMimeTypes,
                role.maxSingleUploadBytes,
                role.maxTotalUploadBytes,
                role.createdAt,
                role.updatedAt);
    }

    private String validateName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("角色名称不能为空");
        }

        String normalized = name.trim();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("角色名称不能为空");
        }

        if (normalized.length() > 64) {
            throw new IllegalArgumentException("角色名称长度不能超过64字符");
        }

        if (!normalized.matches("^[a-zA-Z0-9_\\-]+$")) {
            throw new IllegalArgumentException("角色名称只能包含字母、数字、下划线和短横线");
        }

        return normalized;
    }
}
