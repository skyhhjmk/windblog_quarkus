package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.controller.api.admin.dto.AdminUserDtos.AdminUserItem;
import com.biliwind.blog.controller.api.admin.dto.AdminUserDtos.PageResult;
import com.biliwind.blog.controller.api.admin.dto.AdminUserDtos.UserUpdateRequest;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.UploadRoleService;
import io.quarkus.panache.common.Page;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/admin/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminUser")
public class AdminUserController {

    @Inject
    UploadRoleService uploadRoleService;

    @Inject
    AdminRequestContext adminRequestContext;

    @GET
    @Operation(summary = "用户列表")
    public PageResult<AdminUserItem> list(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("pageSize") @DefaultValue("10") int pageSize,
            @QueryParam("keyword") String keyword) {

        StringBuilder where = new StringBuilder("deletedAt is null");
        Map<String, Object> params = new HashMap<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (username like :keyword or email like :keyword)");
            params.put("keyword", "%" + keyword.trim() + "%");
        }

        var query = User.find(where + " order by createdAt desc", params);
        List<User> list = query.page(Page.of(Math.max(page, 1) - 1, Math.max(pageSize, 1))).list();

        return new PageResult<>(
                list.stream().map(this::toItem).toList(),
                query.count(),
                Math.max(page, 1),
                Math.max(pageSize, 1));
    }

    @PUT
    @Path("/{id}")
    @Transactional
    @Operation(summary = "更新用户")
    public AdminUserItem update(@PathParam("id") Long id, UserUpdateRequest req) {
        User user = User.findById(id);
        if (user == null || user.deletedAt != null) {
            throw new NotFoundException();
        }

        if (req.email() != null && !req.email().isBlank()) {
            user.email = req.email().trim();
        }
        if (req.status() != null) {
            user.status = req.status();
        }

        if (req.roleName() != null && !req.roleName().isBlank()) {
            if (!adminRequestContext.isSuperAdmin()) {
                throw new ForbiddenException("只有超级管理员可修改角色");
            }
            String targetRole = req.roleName().trim();
            boolean knownRole = RoleConstant.DEFAULT_ROLES.contains(targetRole)
                    || uploadRoleService.findByName(targetRole) != null;
            if (!knownRole) {
                throw new BadRequestException("角色不存在：" + targetRole);
            }
            user.roleName = targetRole;
        }

        user.updatedAt = OffsetDateTime.now();
        user.persist();
        return toItem(user);
    }

    private AdminUserItem toItem(User user) {
        String avatar = "https://ui-avatars.com/api/?name=" + user.username;
        return new AdminUserItem(
                user.id,
                user.username,
                user.email,
                avatar,
                user.roleName,
                user.status,
                user.createdAt,
                user.updatedAt);
    }
}
