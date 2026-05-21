package com.biliwind.blog.service;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SecurityConfigurationWarning {

    private static final Logger LOG = Logger.getLogger(SecurityConfigurationWarning.class);
    private static final String DEFAULT_ADMIN_JWT_SECRET = "windblog-admin-dev-secret-change-me";
    private static final String DEFAULT_USER_JWT_SECRET = "windblog-user-dev-secret-change-me";
    private static final String DEFAULT_ADMIN_INIT_PASSWORD = "admin";

    @ConfigProperty(name = "admin.jwt.secret", defaultValue = DEFAULT_ADMIN_JWT_SECRET)
    String adminJwtSecret;

    @ConfigProperty(name = "user.jwt.secret", defaultValue = DEFAULT_USER_JWT_SECRET)
    String userJwtSecret;

    @ConfigProperty(name = "admin.init.password", defaultValue = DEFAULT_ADMIN_INIT_PASSWORD)
    String adminInitPassword;

    @ConfigProperty(name = "security.warn-default-secrets", defaultValue = "true")
    boolean warnDefaultSecrets;

    void onStart(@Observes StartupEvent ignored) {
        if (!warnDefaultSecrets) {
            return;
        }
        warnIfDefault("ADMIN_JWT_SECRET", adminJwtSecret, DEFAULT_ADMIN_JWT_SECRET);
        warnIfDefault("USER_JWT_SECRET", userJwtSecret, DEFAULT_USER_JWT_SECRET);
        warnIfDefault("ADMIN_INIT_PASSWORD", adminInitPassword, DEFAULT_ADMIN_INIT_PASSWORD);
    }

    private void warnIfDefault(String environmentName, String actualValue, String defaultValue) {
        if (defaultValue.equals(actualValue)) {
            LOG.warnf("高风险配置：%s 正在使用默认值，请在生产环境中通过环境变量修改", environmentName);
        }
    }
}
