package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.exception.BadRequestException;
import com.biliwind.blog.model.RegionRule;
import jakarta.transaction.Transactional;
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
        return Response.noContent().build();
    }
}
