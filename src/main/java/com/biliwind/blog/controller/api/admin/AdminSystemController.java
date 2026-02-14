package com.biliwind.blog.controller.api.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.HashMap;
import java.util.Map;

@Path("/api/admin/system")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("admin")
public class AdminSystemController {

    @GET
    @Path("/monitor")
    public Map<String, Object> monitor() {
        Map<String, Object> metrics = new HashMap<>();

        // Memory
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        metrics.put("heapMemoryUsage", memoryBean.getHeapMemoryUsage().getUsed());
        metrics.put("heapMemoryMax", memoryBean.getHeapMemoryUsage().getMax());
        metrics.put("nonHeapMemoryUsage", memoryBean.getNonHeapMemoryUsage().getUsed());

        // CPU (Note: This might be limited depending on the JVM/OS)
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        metrics.put("systemLoadAverage", osBean.getSystemLoadAverage());
        metrics.put("availableProcessors", osBean.getAvailableProcessors());
        metrics.put("arch", osBean.getArch());
        metrics.put("osName", osBean.getName());
        metrics.put("osVersion", osBean.getVersion());

        // Threads
        metrics.put("threadCount", ManagementFactory.getThreadMXBean().getThreadCount());

        // JVM
        metrics.put("vmName", ManagementFactory.getRuntimeMXBean().getVmName());
        metrics.put("vmVendor", ManagementFactory.getRuntimeMXBean().getVmVendor());
        metrics.put("vmVersion", ManagementFactory.getRuntimeMXBean().getVmVersion());
        metrics.put("uptime", ManagementFactory.getRuntimeMXBean().getUptime());

        return metrics;
    }
}
