package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.common.security.PasswordHasher;
import com.biliwind.blog.controller.api.admin.dto.AdminInstallRequest;
import com.biliwind.blog.model.ApplicationInstallation;
import com.biliwind.blog.model.SystemSetting;
import com.biliwind.blog.model.UploadRole;
import com.biliwind.blog.model.User;
import com.biliwind.blog.model.dto.ConfigChangedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Locale;

@ApplicationScoped
public class ApplicationInstallationService {

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    ObjectMapper mapper;

    @Inject
    UploadRoleService uploadRoleService;

    @Inject
    Event<ConfigChangedEvent> configChangedEvent;

    @Transactional
    public InstallationStatus status() {
        ApplicationInstallation state = currentState();
        if (!state.installed && hasActiveSuperAdmin()) {
            User admin = User.find("roleName = ?1 and status = 1 and deletedAt is null order by id",
                    RoleConstant.SUPER_ADMIN).firstResult();
            markInstalled(state, admin == null ? null : admin.id);
        }
        return new InstallationStatus(state.installed, state.installedAt);
    }

    @Transactional
    public boolean isInstalled() {
        ApplicationInstallation state = ApplicationInstallation.current();
        return state != null && (state.installed || hasActiveSuperAdmin());
    }

    @Transactional
    public void install(AdminInstallRequest request) {
        validate(request);
        ApplicationInstallation state = ApplicationInstallation.find("id", 1L)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
        if (state == null) {
            throw new IllegalStateException("安装状态未初始化，请先执行数据库迁移");
        }
        if (state.installed || hasActiveSuperAdmin()) {
            if (!state.installed) {
                markInstalled(state, null);
            }
            throw new AlreadyInstalledException();
        }

        User existingUser = User.find("username = ?1 or email = ?2", request.username().trim(),
                request.email().trim().toLowerCase(Locale.ROOT)).firstResult();
        if (existingUser != null) {
            throw new InstallationValidationException("用户名或邮箱已被占用");
        }

        // users.role_name references upload_roles.name.  A fresh database does not
        // have the default upload roles yet, so create and flush them before the
        // first SUPER_ADMIN user is inserted.
        uploadRoleService.ensureDefaults();
        UploadRole.getEntityManager().flush();

        User admin = new User();
        OffsetDateTime now = OffsetDateTime.now();
        admin.username = request.username().trim();
        admin.email = request.email().trim().toLowerCase(Locale.ROOT);
        admin.password = passwordHasher.hash(request.password());
        admin.status = 1;
        admin.roleName = RoleConstant.SUPER_ADMIN;
        admin.createdAt = now;
        admin.updatedAt = now;
        admin.persist();

        updateSiteInfo(request);
        markInstalled(state, admin.id);
    }

    @Transactional
    public void markInstalledForTest(Long adminId) {
        ApplicationInstallation state = currentState();
        markInstalled(state, adminId);
    }

    private ApplicationInstallation currentState() {
        ApplicationInstallation state = ApplicationInstallation.current();
        if (state == null) {
            throw new IllegalStateException("安装状态未初始化，请先执行数据库迁移");
        }
        return state;
    }

    private boolean hasActiveSuperAdmin() {
        return User.count("roleName = ?1 and status = 1 and deletedAt is null", RoleConstant.SUPER_ADMIN) > 0;
    }

    private void markInstalled(ApplicationInstallation state, Long adminId) {
        if (state.installed) {
            return;
        }
        state.installed = true;
        state.installedAt = OffsetDateTime.now();
        state.installedBy = adminId;
        state.persist();
    }

    private void updateSiteInfo(AdminInstallRequest request) {
        SystemSetting setting = SystemSetting.findByKey("site_info");
        if (setting == null) {
            throw new IllegalStateException("站点基础配置未初始化，请先执行数据库迁移");
        }
        ObjectNode value = mapper.createObjectNode();
        value.put("title", request.siteTitle().trim());
        value.put("subtitle", trimToEmpty(request.siteSubtitle()));
        ArrayNode keywords = value.putArray("keywords");
        for (String keyword : request.siteKeywords()) {
            String normalized = keyword == null ? "" : keyword.trim();
            if (!normalized.isEmpty()) {
                keywords.add(normalized);
            }
        }
        value.put("description", trimToEmpty(request.siteDescription()));
        value.put("author", trimToEmpty(request.siteAuthor()));
        value.put("site_url", request.siteUrl().trim());
        setting.configValue = value;
        setting.version = setting.version + 1;
        setting.isFrozen = false;
        setting.persist();
        configChangedEvent.fire(new ConfigChangedEvent("site_info", value));
    }

    private void validate(AdminInstallRequest request) {
        if (request == null) {
            throw new InstallationValidationException("安装参数不能为空");
        }
        String username = trimToEmpty(request.username());
        String email = trimToEmpty(request.email());
        String password = request.password() == null ? "" : request.password();
        if (username.length() < 3 || username.length() > 100) {
            throw new InstallationValidationException("用户名长度必须为 3-100 个字符");
        }
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new InstallationValidationException("邮箱格式不正确");
        }
        if (password.length() < 12 || password.length() > 255) {
            throw new InstallationValidationException("管理员密码至少需要 12 个字符");
        }
        if (trimToEmpty(request.siteTitle()).length() < 1 || request.siteTitle().trim().length() > 200) {
            throw new InstallationValidationException("站点标题不能为空且不能超过 200 个字符");
        }
        if (request.siteKeywords() == null || request.siteKeywords().size() > 30) {
            throw new InstallationValidationException("SEO 关键词数量不能超过 30 个");
        }
        String siteUrl = trimToEmpty(request.siteUrl());
        try {
            URI uri = URI.create(siteUrl);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new InstallationValidationException("本站链接必须是有效的 http(s) 地址");
        }
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public record InstallationStatus(boolean installed, OffsetDateTime installedAt) {
    }

    public static class AlreadyInstalledException extends RuntimeException {
    }

    public static class InstallationValidationException extends RuntimeException {
        public InstallationValidationException(String message) {
            super(message);
        }
    }
}
