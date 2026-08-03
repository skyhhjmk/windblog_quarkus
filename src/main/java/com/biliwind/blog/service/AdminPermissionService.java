package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.model.AdminRolePermission;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AdminPermissionService {

    public boolean isAllowed(String roleName, String action) {
        if (RoleConstant.SUPER_ADMIN.equals(roleName)) {
            return true;
        }
        if (roleName == null || roleName.isBlank() || action == null || action.isBlank()) {
            return false;
        }
        if (has(roleName, action)) {
            return true;
        }
        int separator = action.indexOf('.');
        String resource = separator > 0 ? action.substring(0, separator) : action;
        if (has(roleName, resource + ".*")) {
            return true;
        }
        return false;
    }

    private boolean has(String roleName, String permission) {
        return AdminRolePermission.count(
                "roleName = ?1 and permission = ?2 and enabled = true", roleName, permission) > 0;
    }
}
