package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.AdminInstallRequest;
import com.biliwind.blog.service.ApplicationInstallationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/api/admin/install")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminInstallationController {

    @Inject
    ApplicationInstallationService installationService;

    @GET
    @Path("/status")
    public Response status() {
        ApplicationInstallationService.InstallationStatus status = installationService.status();
        return Response.ok(Map.of("success", true, "installed", status.installed(),
                "installedAt", status.installedAt() == null ? "" : status.installedAt().toString())).build();
    }

    @POST
    public Response install(AdminInstallRequest request) {
        try {
            installationService.install(request);
            return Response.status(Response.Status.CREATED)
                    .entity(Map.of("success", true, "installed", true, "message", "安装初始化完成，请使用新管理员账号登录"))
                    .build();
        } catch (ApplicationInstallationService.AlreadyInstalledException exception) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("success", false, "code", "ALREADY_INSTALLED", "message", "系统已经完成安装初始化"))
                    .build();
        } catch (ApplicationInstallationService.InstallationValidationException exception) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "code", "INVALID_INSTALLATION", "message", exception.getMessage()))
                    .build();
        }
    }
}
