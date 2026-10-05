package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.exception.BadRequestException;
import com.biliwind.blog.model.RegionRule;
import jakarta.transaction.Transactional;
import jakarta.inject.Inject;
import jakarta.enterprise.event.Event;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * 区域规则管理接口
 */
@Path("/api/admin/regions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminRegionController extends AdminBaseApiController {

    @Inject
    com.biliwind.blog.service.RegionRuleService regionRuleService;

    @Inject
    com.biliwind.blog.service.edge.NodeRoleService nodeRoleService;

    @Inject
    Event<com.biliwind.blog.service.edge.DataSyncEvent> dataSyncEvent;

    @GET
    public List<RegionRule> listRules() {
        return RegionRule.list("order by priority desc");
    }

    @POST
    @Transactional
    public Response createRule(RegionRule rule) {
        if (rule == null || rule.name == null || rule.pattern == null || rule.region == null) {
            throw new BadRequestException("参数缺失");
        }
        rule.id = null; // 确保是新增
        rule.persist();
        regionRuleService.invalidate();
        if (!nodeRoleService.isEdgeNode()) {
            dataSyncEvent.fire(new com.biliwind.blog.service.edge.DataSyncEvent(
                    "REGION_RULE", rule.id, "UPSERT"));
        }
        return Response.status(Response.Status.CREATED).entity(rule).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public RegionRule updateRule(@PathParam("id") Long id, RegionRule update) {
        RegionRule entity = RegionRule.findById(id);
        if (entity == null) {
            throw new NotFoundException("规则不存在");
        }

        entity.name = update.name;
        entity.ruleType = update.ruleType;
        entity.pattern = update.pattern;
        entity.region = update.region;
        entity.priority = update.priority;
        entity.isEnabled = update.isEnabled;
        regionRuleService.invalidate();
        if (!nodeRoleService.isEdgeNode()) {
            dataSyncEvent.fire(new com.biliwind.blog.service.edge.DataSyncEvent(
                    "REGION_RULE", entity.id, "UPSERT"));
        }

        return entity;
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response deleteRule(@PathParam("id") Long id) {
        RegionRule entity = RegionRule.findById(id);
        if (entity == null) {
            throw new NotFoundException("规则不存在");
        }
        entity.delete();
        regionRuleService.invalidate();
        if (!nodeRoleService.isEdgeNode()) {
            dataSyncEvent.fire(new com.biliwind.blog.service.edge.DataSyncEvent(
                    "REGION_RULE", id, "DELETE"));
        }
        return Response.noContent().build();
    }
}
