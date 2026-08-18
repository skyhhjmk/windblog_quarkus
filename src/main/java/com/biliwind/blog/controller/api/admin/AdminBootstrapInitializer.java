package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.common.security.PasswordHasher;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.ApplicationInstallationService;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;

@ApplicationScoped
@IfBuildProfile("test")
public class AdminBootstrapInitializer {

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    ApplicationInstallationService installationService;

    @Transactional
    void onStart(@Observes StartupEvent ignored) {
        if (hasActiveSuperAdmin()) {
            installationService.markInstalledForTest(null);
            return;
        }
        createTestSuperAdmin();
    }

    private boolean hasActiveSuperAdmin() {
        long count = User.count("roleName = ?1 and status = 1 and deletedAt is null", RoleConstant.SUPER_ADMIN);
        return count > 0;
    }

    private void createTestSuperAdmin() {
        String initUsername = "admin";
        String initEmail = "admin@windblog.local";
        User existingUser = User.find("username = ?1 or email = ?2", initUsername, initEmail).firstResult();
        ensureNoExistingUser(existingUser);
        OffsetDateTime now = OffsetDateTime.now();
        User admin = new User();
        admin.username = initUsername;
        admin.email = initEmail;
        admin.password = passwordHasher.hash("admin");
        admin.status = 1;
        admin.roleName = RoleConstant.SUPER_ADMIN;
        admin.createdAt = now;
        admin.updatedAt = now;
        admin.persist();
        installationService.markInstalledForTest(admin.id);
    }

    static void ensureNoExistingUser(User existingUser) {
        if (existingUser != null) {
            throw new IllegalStateException("无法创建测试 SUPER_ADMIN：初始化用户名或邮箱已被现有用户占用");
        }
    }
}
