package com.biliwind.blog.controller;

import com.biliwind.blog.service.PublicUrlService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/.well-known/api-catalog")
public class ApiCatalogResource {

    private static final String PROFILE = "https://www.rfc-editor.org/info/rfc9727";
    private static final String LINKSET_MEDIA_TYPE = "application/linkset+json; profile=\"" + PROFILE + "\"";

    @Inject
    PublicUrlService publicUrlService;

    @GET
    public Response getCatalog() {
        String baseUrl = normalizedPublicUrl();
        Map<String, Object> api = new LinkedHashMap<>();
        api.put("anchor", baseUrl + "/search/suggest");
        api.put("service-desc", List.of(Map.of(
                "href", baseUrl + "/api/public/openapi",
                "type", "application/vnd.oai.openapi+json;version=3.1"
        )));
        api.put("service-doc", List.of(Map.of(
                "href", baseUrl + "/api/public/docs",
                "type", "text/html"
        )));
        api.put("status", List.of(Map.of(
                "href", baseUrl + "/q/health/ready",
                "type", "application/json"
        )));

        return Response.ok(Map.of("linkset", List.of(api)))
                .type(LINKSET_MEDIA_TYPE)
                .build();
    }

    @HEAD
    public Response headCatalog() {
        String catalogUrl = normalizedPublicUrl() + "/.well-known/api-catalog";
        return Response.ok()
                .type(LINKSET_MEDIA_TYPE)
                .header("Link", "<" + catalogUrl + ">; rel=\"api-catalog\"")
                .build();
    }

    private String normalizedPublicUrl() {
        return publicUrlService.getBaseUrl();
    }
}
