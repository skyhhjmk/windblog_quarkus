package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.model.SystemSetting;
import com.biliwind.blog.model.SystemSettingHistory;
import com.biliwind.blog.controller.api.admin.dto.SystemSettingView;
import com.biliwind.blog.model.dto.ConfigChangedEvent;
import com.biliwind.blog.service.SafeModeWatchdog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import io.quarkus.panache.common.Page;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

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

    @Inject
    com.biliwind.blog.service.AuditService auditService;

    @Inject
    Event<com.biliwind.blog.service.edge.DataSyncEvent> dataSyncEvent;

    @Inject
    com.biliwind.blog.service.security.ClientIpResolver clientIpResolver;

    @Inject
    RoutingContext routingContext;

    @GET
    @SecurityRequirement(name = "adminBearerAuth")
    @Operation(summary = "获取所有设置项")
    @Transactional
    public Response getAllSettings(@QueryParam("group") String group,
                                   @QueryParam("page") @DefaultValue("1") int page,
                                   @QueryParam("pageSize") @DefaultValue("50") int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        PanacheQuery<SystemSetting> query;
        if (group != null && !group.isEmpty()) {
            query = SystemSetting.find("groupName", group);
        } else {
            query = SystemSetting.findAll();
        }
        long total = query.count();
        List<SystemSetting> settings = query.page(Page.of(safePage - 1, safePageSize)).list();
        List<SystemSettingView> views = new ArrayList<>();
        for (SystemSetting setting : settings) {
            views.add(toView(setting));
        }
        return Response.ok(Map.of("success", true, "data", views,
                "page", safePage, "pageSize", safePageSize, "total", total)).build();
    }

    @GET
    @Path("/{key}")
    @SecurityRequirement(name = "adminBearerAuth")
    @Operation(summary = "根据Key获取设置项")
    @Transactional
    public Response getSettingByKey(@PathParam("key") String key) {
        SystemSetting setting = SystemSetting.findByKey(key);
        if (setting == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("success", false, "message", "配置项不存在")).build();
        }
        return Response.ok(Map.of("success", true, "data", toView(setting))).build();
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

        Object configValueObj = body.get("configValue");
        if (configValueObj == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "configValue 不能为空")).build();
        }

        JsonNode newValue = mapper.valueToTree(configValueObj);
        newValue = mergeSecretValues(setting.configKey, setting.configValue, newValue);
        Object reasonObj = body.get("reason");
        String reason = null;
        if (reasonObj != null) {
            if (reasonObj instanceof String) {
                reason = (String) reasonObj;
            } else {
                reason = reasonObj.toString();
            }
        }

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
        dataSyncEvent.fire(new com.biliwind.blog.service.edge.DataSyncEvent("SYSTEM_SETTING", setting.id, "UPSERT"));

        // 触发热更新
        configChangedEvent.fire(new ConfigChangedEvent(key, newValue));

        // 启动 Watchdog (3分钟后验证)
        watchdog.watch(key, 3);

        auditService.log("system_setting", String.valueOf(setting.id), "update",
                sanitizeForAudit(key, setting.configValue),
                sanitizeForAudit(key, newValue));

        return Response.ok(Map.of("success", true, "message", "配置已更新，进入3分钟验证期", "data", toView(setting))).build();
    }

    @POST
    @Path("/{key}/confirm")
    @Transactional
    @SecurityRequirement(name = "adminBearerAuth")
    @Operation(summary = "人工确认配置有效，解除锁定")
    public Response confirmSetting(@PathParam("key") String key) {
        SystemSetting setting = SystemSetting.findByKey(key);
        if (setting == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("success", false, "message", "配置项不存在")).build();
        }
        setting.isFrozen = false;
        setting.persist();
        dataSyncEvent.fire(new com.biliwind.blog.service.edge.DataSyncEvent("SYSTEM_SETTING", setting.id, "UPSERT"));
        auditService.log("system_setting", String.valueOf(setting.id), "confirm", null, Map.of("key", key));
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
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("success", false, "message", "配置项不存在")).build();
        }
        watchdog.rollback(setting);
        auditService.log("system_setting", String.valueOf(setting.id), "rollback", null, Map.of("key", key));
        return Response.ok(Map.of("success", true, "message", "已执行回滚")).build();
    }

    @GET
    @Path("/{key}/history")
    @SecurityRequirement(name = "adminBearerAuth")
    @Operation(summary = "获取配置变更历史")
    @Transactional
    public Response getHistory(@PathParam("key") String key,
                               @QueryParam("page") @DefaultValue("1") int page,
                               @QueryParam("pageSize") @DefaultValue("50") int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        PanacheQuery<SystemSettingHistory> query = SystemSettingHistory.find(
                "configKey = ?1 order by createdAt desc", key);
        long total = query.count();
        List<SystemSettingHistory> history = query.page(Page.of(safePage - 1, safePageSize)).list();
        List<Map<String, Object>> safeHistory = new ArrayList<>();
        for (SystemSettingHistory item : history) {
            Map<String, Object> safeItem = new HashMap<>();
            safeItem.put("id", item.id);
            safeItem.put("configKey", item.configKey);
            safeItem.put("configValue", maskSecrets(item.configKey, item.configValue));
            safeItem.put("version", item.version);
            safeItem.put("operatorId", item.operatorId);
            safeItem.put("changeReason", item.changeReason);
            safeItem.put("createdAt", item.createdAt);
            safeHistory.add(safeItem);
        }
        return Response.ok(Map.of("success", true, "data", safeHistory,
                "page", safePage, "pageSize", safePageSize, "total", total)).build();
    }

    @POST
    @Path("/apply-audit-value")
    @Transactional
    @SecurityRequirement(name = "adminBearerAuth")
    @Operation(summary = "从审计日志应用值（回滚/重用）")
    public Response applyAuditValue(Map<String, Object> body) {
        String key = (String) body.get("key");
        Object valueObj = body.get("value");
        if (key == null || valueObj == null) {
            throw new BadRequestException("Key and value are required");
        }

        SystemSetting setting = SystemSetting.findByKey(key);
        if (setting == null) {
            throw new NotFoundException("Setting not found");
        }

        if (setting.isFrozen) {
            throw new WebApplicationException("Setting is currently frozen", Response.Status.CONFLICT);
        }

        JsonNode newValue = mergeSecretValues(setting.configKey, setting.configValue, mapper.valueToTree(valueObj));

        // 记录历史
        SystemSettingHistory history = new SystemSettingHistory();
        history.settingId = setting.id;
        history.configKey = setting.configKey;
        history.configValue = setting.configValue;
        history.version = setting.version;
        history.operatorId = adminRequestContext.getUserId();
        history.changeReason = "Rollback/Apply from audit log";
        history.persist();

        // 更新
        setting.configValue = newValue;
        setting.version += 1;
        setting.isFrozen = true;
        setting.persist();
        dataSyncEvent.fire(new com.biliwind.blog.service.edge.DataSyncEvent("SYSTEM_SETTING", setting.id, "UPSERT"));

        // 触发热更新和 Watchdog
        configChangedEvent.fire(new ConfigChangedEvent(key, newValue));
        watchdog.watch(key, 3);

        auditService.log("system_setting", String.valueOf(setting.id), "audit_apply",
                Map.of("key", key, "value", maskSecrets(key, history.configValue), "version", history.version),
                Map.of("key", key, "value", maskSecrets(key, newValue), "version", setting.version));

        return Response.ok(Map.of("success", true, "message", "已应用配置并进入验证期")).build();
    }

    @GET
    @Path("/client-ip/inspect")
    @SecurityRequirement(name = "adminBearerAuth")
    @Operation(summary = "查看当前客户端 IP 解析结果")
    @Transactional
    public Response inspectClientIp() {
        com.biliwind.blog.service.security.ClientIpResolver.ClientIpResolution resolution = clientIpResolver.resolve(routingContext);
        return Response.ok(Map.of("success", true, "data", resolution)).build();
    }

    @POST
    @Path("/client-ip/simulate")
    @SecurityRequirement(name = "adminBearerAuth")
    @Operation(summary = "模拟客户端 IP 请求头解析")
    public Response simulateClientIp(Map<String, String> request) {
        String remoteIp = request.get("remoteIp");
        String headerValue = request.get("headerValue");
        com.biliwind.blog.service.security.ClientIpResolver.ClientIpResolution resolution = clientIpResolver.resolve(remoteIp, headerValue);
        return Response.ok(Map.of("success", true, "data", resolution)).build();
    }
    private Map<String, Object> sanitizeForAudit(String key, JsonNode value) {
        Map<String, Object> result = new HashMap<>();
        result.put("key", key);
        result.put("version", value.has("version") ? value.get("version").asInt() : null);

        if (isSensitiveKey(key)) {
            ObjectNode sanitized = mapper.createObjectNode();
            if (value.isObject()) {
                value.fieldNames().forEachRemaining(fieldName -> {
                    if (fieldName.contains("key") || fieldName.contains("secret") || fieldName.contains("password")) {
                        sanitized.put(fieldName, "***REDACTED***");
                    } else {
                        sanitized.set(fieldName, value.get(fieldName));
                    }
                });
            }
            result.put("value", sanitized);
        } else {
            result.put("value", value);
        }

        return result;
    }

    private boolean isSensitiveKey(String key) {
        return key.contains("key") || key.contains("secret") || key.contains("password") || key.contains("token");
    }

    private SystemSettingView toView(SystemSetting setting) {
        boolean secret = containsSecret(setting.configKey, setting.configValue);
        return new SystemSettingView(setting.id, setting.configKey,
                maskSecrets(setting.configKey, setting.configValue), setting.configType,
                setting.groupName, setting.uiSchema, setting.description, setting.version,
                setting.isFrozen, secret, setting.createdAt, setting.updatedAt);
    }

    private boolean containsSecret(String key, JsonNode value) {
        if (isSensitiveKey(key.toLowerCase())) {
            return true;
        }
        if (value == null) {
            return false;
        }
        if (value.isObject()) {
            java.util.Iterator<String> fields = value.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next().toLowerCase();
                if (isSensitiveKey(field) || containsSecret(field, value.get(field))) {
                    return true;
                }
            }
        } else if (value.isArray()) {
            for (JsonNode item : value) {
                if (containsSecret(key, item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private JsonNode maskSecrets(String key, JsonNode value) {
        if (value == null) {
            return null;
        }
        if (isSensitiveKey(key.toLowerCase())) {
            return mapper.getNodeFactory().textNode("***REDACTED***");
        }
        if (!value.isObject()) {
            if (value.isArray()) {
                com.fasterxml.jackson.databind.node.ArrayNode result = mapper.createArrayNode();
                for (JsonNode item : value) {
                    result.add(maskSecrets(key, item));
                }
                return result;
            }
            return value.deepCopy();
        }
        ObjectNode result = mapper.createObjectNode();
        value.fields().forEachRemaining(field -> result.set(field.getKey(), maskSecrets(field.getKey(), field.getValue())));
        return result;
    }

    private JsonNode mergeSecretValues(String key, JsonNode current, JsonNode submitted) {
        if (submitted == null || submitted.isNull()) {
            return current == null ? mapper.createObjectNode() : current.deepCopy();
        }
        if (isSensitiveKey(key.toLowerCase())) {
            return submitted.isTextual() && (submitted.asText().isBlank() || submitted.asText().equals("***REDACTED***"))
                    ? current.deepCopy() : submitted.deepCopy();
        }
        if (!submitted.isObject() || current == null || !current.isObject()) {
            return submitted.deepCopy();
        }
        ObjectNode merged = (ObjectNode) current.deepCopy();
        submitted.fields().forEachRemaining(field -> merged.set(field.getKey(),
                mergeSecretValues(field.getKey(), current.get(field.getKey()), field.getValue())));
        return merged;
    }
}
