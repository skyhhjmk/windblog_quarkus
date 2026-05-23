package com.biliwind.blog.filter;

import com.biliwind.blog.service.edge.EdgePersistentChannelClient;
import com.biliwind.blog.service.edge.EdgeReadOnlyState;
import com.biliwind.blog.service.edge.NodeRoleService;
import com.biliwind.blog.service.edge.RoutedHttpExchange;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Provider
@Priority(Priorities.USER)
@ApplicationScoped
public class EdgeWriteRoutingFilter implements ContainerRequestFilter {

    @Inject
    NodeRoleService nodeRoleService;

    @Inject
    EdgeReadOnlyState readOnlyState;

    @Inject
    EdgePersistentChannelClient channelClient;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!nodeRoleService.isEdgeNode()) {
            return;
        }

        if (!isWriteMethod(requestContext.getMethod())) {
            return;
        }

        String path = normalizePath(requestContext.getUriInfo().getPath());
        if (isLocalWriteAllowed(path)) {
            return;
        }

        if (readOnlyState.isReadOnly()) {
            requestContext.abortWith(buildReadOnlyResponse());
            return;
        }

        byte[] requestBody = readRequestBody(requestContext);
        RoutedHttpExchange.Request routedRequest = new RoutedHttpExchange.Request(
                requestContext.getMethod(),
                path,
                requestContext.getUriInfo().getRequestUri().getRawQuery(),
                collectHeaders(requestContext),
                requestBody
        );

        RoutedHttpExchange.Response routedResponse = channelClient.forwardWriteRequest(routedRequest);
        Response response = buildResponse(routedResponse);
        requestContext.abortWith(response);
    }

    private boolean isWriteMethod(String method) {
        if ("POST".equalsIgnoreCase(method)) {
            return true;
        }
        if ("PUT".equalsIgnoreCase(method)) {
            return true;
        }
        if ("PATCH".equalsIgnoreCase(method)) {
            return true;
        }
        return "DELETE".equalsIgnoreCase(method);
    }

    private boolean isLocalWriteAllowed(String path) {
        if (path.equals("/user/api/login")) {
            return true;
        }
        if (path.equals("/user/api/logout")) {
            return true;
        }
        if (path.equals("/api/admin/auth/login")) {
            return true;
        }
        if (path.startsWith("/q/")) {
            return true;
        }
        return path.startsWith("/grpc/");
    }

    private byte[] readRequestBody(ContainerRequestContext requestContext) {
        InputStream inputStream = requestContext.getEntityStream();
        if (inputStream == null) {
            return new byte[0];
        }

        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int readLength = inputStream.read(buffer);
            while (readLength >= 0) {
                outputStream.write(buffer, 0, readLength);
                readLength = inputStream.read(buffer);
            }
            byte[] body = outputStream.toByteArray();
            requestContext.setEntityStream(new ByteArrayInputStream(body));
            return body;
        } catch (Exception exception) {
            return new byte[0];
        }
    }

    private Map<String, String> collectHeaders(ContainerRequestContext requestContext) {
        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, List<String>> headerEntry : requestContext.getHeaders().entrySet()) {
            String headerName = headerEntry.getKey();
            List<String> values = headerEntry.getValue();
            if (values == null || values.isEmpty()) {
                continue;
            }
            if (!canForwardHeader(headerName)) {
                continue;
            }
            headers.put(headerName, values.get(0));
        }
        return headers;
    }

    private boolean canForwardHeader(String headerName) {
        if (headerName == null || headerName.isBlank()) {
            return false;
        }
        String lowerName = headerName.toLowerCase();
        if ("host".equals(lowerName)) {
            return false;
        }
        if ("content-length".equals(lowerName)) {
            return false;
        }
        return !"connection".equals(lowerName);
    }

    private Response buildResponse(RoutedHttpExchange.Response routedResponse) {
        Response.ResponseBuilder responseBuilder = Response.status(routedResponse.status());
        if (routedResponse.body() != null) {
            responseBuilder.entity(routedResponse.body());
        }

        Map<String, String> headers = routedResponse.headers();
        if (headers != null) {
            for (Map.Entry<String, String> headerEntry : headers.entrySet()) {
                responseBuilder.header(headerEntry.getKey(), headerEntry.getValue());
            }
        }

        return responseBuilder.build();
    }

    private Response buildReadOnlyResponse() {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(Map.of("success", false, "message", readOnlyState.getCurrentMessage()))
                .build();
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        if (path.startsWith("/")) {
            return path;
        }
        return "/" + path;
    }
}
