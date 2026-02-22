package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.model.UploadRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 上传角色及配额管理
 */
@ApplicationScoped
public class UploadRoleService {

    private static List<String> normalizeMimeList(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>();
        for (String item : raw) {
            if (item == null) continue;
            String normalized = item.trim().toLowerCase();
            if (normalized.isEmpty()) continue;
            list.add(normalized);
        }
        return list.stream().distinct().collect(Collectors.toUnmodifiableList());
    }

    public List<UploadRole> listRoles() {
        return UploadRole.listAll();
    }

    public UploadRole findByName(String name) {
        return UploadRole.findById(name);
    }

    @Transactional
    public UploadRole upsert(String name, UploadRoleUpdateRequest request) {
        UploadRole role = UploadRole.findById(name);
        if (role == null) {
            role = new UploadRole();
            role.name = name;
            role.createdAt = OffsetDateTime.now();
        }
        role.displayName = request.displayName();
        role.description = request.description();
        role.canUpload = request.canUpload();
        role.allowedMimeTypes = normalizeMimeList(request.allowedMimeTypes());
        role.maxSingleUploadBytes = request.maxSingleUploadBytes();
        role.maxTotalUploadBytes = request.maxTotalUploadBytes();
        role.updatedAt = OffsetDateTime.now();
        if (!role.isPersistent()) {
            role.persist();
        }
        return role;
    }

    @Transactional
    public void delete(String name) {
        UploadRole role = UploadRole.findById(name);
        if (role != null) {
            UploadRole.deleteById(name);
        }
    }

    @Transactional
    public void ensureDefaults() {
        for (String roleName : RoleConstant.DEFAULT_ROLES) {
            if (UploadRole.findById(roleName) == null) {
                UploadRole role = createDefault(roleName);
                role.persist();
            }
        }
    }

    private UploadRole createDefault(String name) {
        UploadRole role = new UploadRole();
        role.name = name;
        role.createdAt = OffsetDateTime.now();
        role.updatedAt = OffsetDateTime.now();
        switch (name) {
            case RoleConstant.SUPER_ADMIN -> {
                role.displayName = "超级管理员";
                role.description = "拥有全部权限";
                role.canUpload = true;
                role.allowedMimeTypes = Collections.emptyList();
            }
            case RoleConstant.ADMIN -> {
                role.displayName = "管理员";
                role.description = "日常管理用户";
                role.canUpload = true;
                role.allowedMimeTypes = Collections.emptyList();
            }
            case RoleConstant.USER -> {
                role.displayName = "登录用户";
                role.description = "常规投稿用户";
                role.canUpload = true;
                role.allowedMimeTypes = List.of("image/png", "image/jpeg", "image/gif", "image/webp");
                role.maxSingleUploadBytes = 4 * 1024 * 1024L;
                role.maxTotalUploadBytes = 100 * 1024 * 1024L;
            }
            case RoleConstant.GUEST -> {
                role.displayName = "访客";
                role.description = "默认游客";
                role.canUpload = false;
                role.allowedMimeTypes = Collections.emptyList();
            }
            default -> {
                role.displayName = name;
                role.description = "自定义角色";
                role.canUpload = false;
                role.allowedMimeTypes = Collections.emptyList();
            }
        }
        return role;
    }

    public record UploadRoleUpdateRequest(
            String displayName,
            String description,
            boolean canUpload,
            List<String> allowedMimeTypes,
            Long maxSingleUploadBytes,
            Long maxTotalUploadBytes) {
    }
}
