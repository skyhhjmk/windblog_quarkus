package com.biliwind.blog.service;

import com.biliwind.blog.model.SystemSetting;
import com.biliwind.blog.model.SystemSettingHistory;
import com.biliwind.blog.model.dto.ConfigChangedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.jboss.logging.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class SafeModeWatchdog {

    private static final Logger LOG = Logger.getLogger(SafeModeWatchdog.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    @Inject
    Instance<HealthCheck> healthChecks;
    @Inject
    Event<ConfigChangedEvent> configChangedEvent;

    public void watch(String key, int delayMinutes) {
        LOG.infof("Watchdog started for key: %s, will check health in %d minutes", key, delayMinutes);
        scheduler.schedule(() -> checkAndRollbackIfNeeded(key), delayMinutes, TimeUnit.MINUTES);
    }

    @Transactional
    public void checkAndRollbackIfNeeded(String key) {
        SystemSetting setting = SystemSetting.findByKey(key);
        if (setting == null || !setting.isFrozen) {
            LOG.infof("Watchdog for %s cancelled: setting not found or not frozen.", key);
            return;
        }

        boolean healthy = isSystemHealthy();
        if (healthy) {
            LOG.infof("System is healthy after config change for %s. Keeping configuration.", key);
            // We could auto-unfreeze here, but the plan says "if health is abnormal ... rollback".
            // It doesn't explicitly say to unfreeze if healthy. I'll leave it frozen for manual confirmation 
            // or I could auto-unfreeze. Let's stick to the plan: rollback if abnormal.
        } else {
            LOG.errorf("System HEALTH ABNORMAL after config change for %s! Initiating automatic rollback.", key);
            rollback(setting);
        }
    }

    private boolean isSystemHealthy() {
        for (HealthCheck check : healthChecks) {
            HealthCheckResponse response = check.call();
            if (response.getStatus() != HealthCheckResponse.Status.UP) {
                LOG.warnf("Health check failed: %s", response.getName());
                return false;
            }
        }
        return true;
    }

    @Transactional
    public void rollback(SystemSetting setting) {
        SystemSettingHistory lastHistory = SystemSettingHistory.find("configKey = ?1 and version < ?2 order by version desc",
                setting.configKey, setting.version).firstResult();

        if (lastHistory != null) {
            setting.configValue = lastHistory.configValue;
            setting.version = lastHistory.version;
            setting.isFrozen = false;
            setting.persist();

            configChangedEvent.fire(new ConfigChangedEvent(setting.configKey, setting.configValue));
            LOG.infof("Rolled back %s to version %d", setting.configKey, setting.version);
        } else {
            LOG.errorf("Rollback failed for %s: no history found!", setting.configKey);
        }
    }
}
