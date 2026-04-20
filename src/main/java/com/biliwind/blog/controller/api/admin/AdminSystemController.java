package com.biliwind.blog.controller.api.admin;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.lang.management.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/admin/system")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "AdminSystem")
@SecurityRequirement(name = "adminBearerAuth")
public class AdminSystemController {

    // 注入所有 HealthCheck 实现（由 CDI 自动收集所有实现类）
    @Inject
    jakarta.enterprise.inject.Instance<HealthCheck> allHealthChecks;

    @GET
    @Path("/monitor")
    @Operation(summary = "获取系统监控数据", description = "返回JVM、OS、应用及各组件健康状态")
    public Map<String, Object> monitor() {
        Map<String, Object> result = new HashMap<>();

        result.put("jvm", buildJvmSection());
        result.put("system", buildSystemSection());
        result.put("application", buildApplicationSection());
        result.put("health", buildHealthSection());

        return result;
    }

    private Map<String, Object> buildJvmSection() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();

        long memoryUsed = memoryBean.getHeapMemoryUsage().getUsed();
        long memoryMax = memoryBean.getHeapMemoryUsage().getMax();
        long memoryCommitted = memoryBean.getHeapMemoryUsage().getCommitted();

        Map<String, Object> jvmSection = new HashMap<>();
        jvmSection.put("memoryUsed", memoryUsed);
        jvmSection.put("memoryMax", memoryMax);
        jvmSection.put("memoryCommitted", memoryCommitted);
        jvmSection.put("threadCount", threadBean.getThreadCount());
        jvmSection.put("peakThreadCount", threadBean.getPeakThreadCount());
        jvmSection.put("uptime", runtimeBean.getUptime());

        return jvmSection;
    }

    private Map<String, Object> buildSystemSection() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

        Map<String, Object> systemSection = new HashMap<>();
        systemSection.put("cpuCount", osBean.getAvailableProcessors());
        systemSection.put("systemLoadAverage", osBean.getSystemLoadAverage());
        systemSection.put("osName", osBean.getName());
        systemSection.put("osVersion", osBean.getVersion());
        systemSection.put("osArch", osBean.getArch());

        return systemSection;
    }

    private Map<String, Object> buildApplicationSection() {
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();

        long startTimeMillis = runtimeBean.getStartTime();
        String startTimeFormatted = Instant.ofEpochMilli(startTimeMillis)
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        Map<String, Object> applicationSection = new HashMap<>();
        applicationSection.put("name", "WindBlog");
        applicationSection.put("version", "1.0");
        applicationSection.put("startTime", startTimeFormatted);
        applicationSection.put("uptime", runtimeBean.getUptime());

        return applicationSection;
    }

    private Map<String, Object> buildHealthSection() {
        List<HealthCheckResponse> responses = collectAllHealthCheckResponses();

        Map<String, Object> components = new HashMap<>();
        boolean allUp = true;

        for (HealthCheckResponse response : responses) {
            boolean isUp = response.getStatus() == HealthCheckResponse.Status.UP;
            if (!isUp) {
                allUp = false;
            }

            Map<String, Object> componentDetail = new HashMap<>();
            componentDetail.put("status", isUp ? "UP" : "DOWN");

            // 把每个 health check 携带的 data 也一并带给前端
            Map<String, Object> detailData = new HashMap<>();
            response.getData().ifPresent(dataMap -> {
                for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
                    detailData.put(entry.getKey(), entry.getValue());
                }
            });
            if (!detailData.isEmpty()) {
                componentDetail.put("details", detailData);
            }

            components.put(response.getName(), componentDetail);
        }

        Map<String, Object> healthSection = new HashMap<>();
        healthSection.put("status", allUp ? "UP" : "DOWN");
        healthSection.put("components", components);

        return healthSection;
    }

    private List<HealthCheckResponse> collectAllHealthCheckResponses() {
        List<HealthCheckResponse> responses = new ArrayList<>();
        for (HealthCheck healthCheck : allHealthChecks) {
            try {
                HealthCheckResponse response = healthCheck.call();
                responses.add(response);
            } catch (Exception ignored) {
                // 单个检查失败不能拖垮整体监控接口
            }
        }
        return responses;
    }
}
