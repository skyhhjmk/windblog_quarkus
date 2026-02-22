package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.common.security.PasswordHasher;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.UploadRoleService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.OffsetDateTime;

@ApplicationScoped
public class AdminBootstrapInitializer {

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    UploadRoleService uploadRoleService;

    @ConfigProperty(name = "admin.init.enabled", defaultValue = "true")
    boolean initEnabled;

    @ConfigProperty(name = "admin.init.username", defaultValue = "admin")
    String initUsername;

    @ConfigProperty(name = "admin.init.password", defaultValue = "admin")
    String initPassword;

    @ConfigProperty(name = "admin.init.email", defaultValue = "admin@windblog.local")
    String initEmail;

    @Transactional
    void onStart(@Observes StartupEvent ignored) {
        if (!initEnabled) {
            return;
        }
        uploadRoleService.ensureDefaults();
        upsertAdminUser();
    }

    private void upsertAdminUser() {
        User admin = User.find("(username = ?1 or email = ?2)", initUsername, initEmail).firstResult();
        OffsetDateTime now = OffsetDateTime.now();
        String hashedPassword = passwordHasher.hash(initPassword);

        if (admin == null) {
            admin = new User();
            admin.username = initUsername;
            admin.email = initEmail;
            admin.password = hashedPassword;
            admin.status = 1;
            admin.roleName = RoleConstant.SUPER_ADMIN;
            admin.createdAt = now;
            admin.updatedAt = now;
            admin.deletedAt = null;
            admin.persist();
            return;
        }

        admin.username = initUsername;
        admin.email = initEmail;
        admin.password = hashedPassword;
        admin.status = 1;
        admin.roleName = RoleConstant.SUPER_ADMIN;
        admin.deletedAt = null;
        admin.updatedAt = now;
    }
}
