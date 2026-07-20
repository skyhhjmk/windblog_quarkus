package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.EmailChannelGroup;
import com.biliwind.blog.model.EmailChannelGroupMember;
import com.biliwind.blog.model.EmailScenarioRoute;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/admin/email-routing")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminEmailRoutingController {
    @GET
    @Path("/groups")
    public List<EmailChannelGroup> listGroups() {
        return EmailChannelGroup.list("order by id");
    }

    @GET
    @Path("/routes")
    public List<EmailScenarioRoute> listRoutes() {
        return EmailScenarioRoute.list("order by scenario");
    }

    @POST
    @Path("/groups")
    @Transactional
    public EmailChannelGroup createGroup(EmailChannelGroup request) {
        validateGroup(request);
        request.id = null;
        request.nextChannelIndex = 0;
        request.persist();
        return request;
    }

    @PUT
    @Path("/groups/{id}")
    @Transactional
    public EmailChannelGroup updateGroup(@PathParam("id") Long id, EmailChannelGroup request) {
        EmailChannelGroup group = EmailChannelGroup.findById(id);
        if (group == null) throw new WebApplicationException("邮件通道组不存在", Response.Status.NOT_FOUND);
        validateGroup(request);
        group.name = request.name;
        group.dispatchMode = request.dispatchMode;
        group.enabled = request.enabled;
        return group;
    }

    @PUT
    @Path("/groups/{id}/members")
    @Transactional
    public void replaceMembers(@PathParam("id") Long groupId, java.util.Map<String, List<EmailChannelGroupMember>> request) {
        if (EmailChannelGroup.findById(groupId) == null)
            throw new WebApplicationException("邮件通道组不存在", Response.Status.NOT_FOUND);
        EmailChannelGroupMember.delete("groupId", groupId);
        List<EmailChannelGroupMember> members = request.get("members");
        if (members == null) {
            return;
        }
        for (EmailChannelGroupMember member : members) {
            member.groupId = groupId;
            member.persist();
        }
    }

    @PUT
    @Path("/routes/{scenario}")
    @Transactional
    public EmailScenarioRoute updateRoute(@PathParam("scenario") String scenario, EmailScenarioRoute request) {
        if (request.channelId != null && request.channelGroupId != null)
            throw new WebApplicationException("场景只能选择通道或通道组", Response.Status.BAD_REQUEST);
        EmailScenarioRoute route = EmailScenarioRoute.findById(scenario);
        if (route == null) {
            route = new EmailScenarioRoute();
            route.scenario = scenario;
        }
        route.channelId = request.channelId;
        route.channelGroupId = request.channelGroupId;
        route.templateKey = request.templateKey == null || request.templateKey.isBlank() ? scenario : request.templateKey;
        route.persist();
        return route;
    }

    private void validateGroup(EmailChannelGroup group) {
        if (group == null || group.name == null || group.name.isBlank())
            throw new WebApplicationException("通道组名称不能为空", Response.Status.BAD_REQUEST);
        if (!"PRIMARY_BACKUP".equals(group.dispatchMode) && !"ROUND_ROBIN".equals(group.dispatchMode))
            throw new WebApplicationException("不支持的通道组策略", Response.Status.BAD_REQUEST);
    }
}
