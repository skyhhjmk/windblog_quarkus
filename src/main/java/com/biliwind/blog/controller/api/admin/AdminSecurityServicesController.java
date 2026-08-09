package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.service.MediaVirusScanService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/admin/system/security-services")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminSecurityServicesController {

    @Inject
    MediaVirusScanService mediaVirusScanService;

    @GET
    public Response getStatus() {
        MediaVirusScanService.Configuration configuration = mediaVirusScanService.getConfiguration();
        MediaVirusScanService.ProbeResult probe = mediaVirusScanService.probe();
        return Response.ok(Map.of(
                "success", true,
                "data", toData(configuration, probe))).build();
    }

    @POST
    @Path("/clamav/test")
    public Response testClamAv() {
        MediaVirusScanService.ProbeResult probe = mediaVirusScanService.probe();
        MediaVirusScanService.Configuration configuration = mediaVirusScanService.getConfiguration();
        // A failed probe is a business result for the admin workbench, not a
        // failed HTTP request. Keeping the response 200 lets the Flutter page
        // display the configured state and the actionable probe message.
        return Response.ok(Map.of("success", probe.available(), "data", toData(configuration, probe)))
                .build();
    }

    private Map<String, Object> toData(MediaVirusScanService.Configuration configuration,
                                       MediaVirusScanService.ProbeResult probe) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("checkedAt", OffsetDateTime.now().toString());
        data.put("enabled", configuration.enabled());
        data.put("required", configuration.required());
        data.put("host", configuration.host());
        data.put("port", configuration.port());
        data.put("timeout", configuration.timeout().toString());
        data.put("status", probe.status().name());
        data.put("available", probe.available());
        data.put("message", probe.message());
        return data;
    }
}
