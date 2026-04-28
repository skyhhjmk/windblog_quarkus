package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.model.SystemSetting;
import com.biliwind.blog.model.SystemSettingHistory;
import com.biliwind.blog.model.dto.ConfigChangedEvent;
import com.biliwind.blog.service.SafeModeWatchdog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

@Path("/api/admin/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminSettings", description = "系统设置中心")
public class AdminSystemSettingsController {

    @Inject
    AdminRequestContext adminRequestContext;

    @Inject
    Event<ConfigChangedEvent> configChangedEvent;

    @Inject
    SafeModeWatchdog watchdog;

    @Inject
    ObjectMapper mapper;

    @GET
    @SecurityRequirement(name = "adminBearerAuth")
    @Operation(summary = "获取所有设置项")
    public Response getAllSettings(@QueryParam("group") String group) {
        List<SystemSetting> settings;
        if (group != null && !group.isEmpty()) {
            settings = SystemSetting.list("groupName", group);
        } else {
            settings = SystemSetting.listAll();
        }
        return Response.ok(Map.of("success", true, "data", settings)).build();
    }

    @GET
    @Path("/{key}")
    @SecurityRequirement(name = "adminBearerAuth")
    @Operation(summary = "根据Key获取设置项")
    public Response getSettingByKey(@PathParam("key") String key) {
        SystemSetting setting = SystemSetting.findByKey(key);
        if (setting == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("success", false, "message", "配置项不存在")).build();
        }
        return Response.ok(Map.of("success", true, "data", setting)).build();
    }

    @PUT
    @Path("/{key}")
    @Transactional
    @SecurityRequirement(name = "adminBearerAuth")
    @Operation(summary = "更新配置项（触发热更新与Watchdog）")
    public Response updateSetting(@PathParam("key") String key, Map<String, Object> body) {
        SystemSetting setting = SystemSetting.findByKey(key);
        if (setting == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("success", false, "message", "配置项不存在")).build();
        }

        if (setting.isFrozen) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("success", false, "message", "配置项处于验证锁定期，请先确认或回滚")).build();
        }

        JsonNode newValue = mapper.valueToTree(body.get("configValue"));
        String reason = (String) body.get("reason");

        // 保存历史
        SystemSettingHistory history = new SystemSettingHistory();
        history.settingId = setting.id;
        history.configKey = setting.configKey;
        history.configValue = setting.configValue;
        history.version = setting.version;
        history.operatorId = adminRequestContext.getUserId();
        history.changeReason = reason;
        history.persist();

        // 更新当前
        setting.configValue = newValue;
        setting.version += 1;
        setting.isFrozen = true; // 进入锁定验证期
        setting.persist();

        // 触发热更新
        configChangedEvent.fire(new ConfigChangedEvent(key, newValue));

        // 启动 Watchdog (3分钟后验证)
        watchdog.watch(key, 3);

        return Response.ok(Map.of("success", true, "message", "配置已更新，进入3分钟验证期", "data", setting)).build();
    }

    @POST
    @Path("/{key}/confirm")
    @Transactional
    @SecurityRequirement(name = "adminBearerAuth")
    @Operation(summary = "人工确认配置有效，解除锁定")
    public Response confirmSetting(@PathParam("key") String key) {
        SystemSetting setting = SystemSetting.findByKey(key);
        if (setting == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        setting.isFrozen = false;
        setting.persist();
        return Response.ok(Map.of("success", true, "message", "配置已确认")).build();
    }

    @POST
    @Path("/{key}/rollback")
    @Transactional
    @SecurityRequirement(name = "adminBearerAuth")
    @Operation(summary = "手动回滚到上一个版本")
    public Response rollbackSetting(@PathParam("key") String key) {
        SystemSetting setting = SystemSetting.findByKey(key);
        if (setting == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        watchdog.rollback(setting);
        return Response.ok(Map.of("success", true, "message", "已执行回滚")).build();
    }

    @GET
    @Path("/{key}/history")
    @SecurityRequirement(name = "adminBearerAuth")
    @Operation(summary = "获取配置变更历史")
    public Response getHistory(@PathParam("key") String key) {
        List<SystemSettingHistory> history = SystemSettingHistory.list("configKey", key);
        return Response.ok(Map.of("success", true, "data", history)).build();
    }
}
