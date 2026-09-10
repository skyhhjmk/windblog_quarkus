package com.biliwind.blog.filter;

import com.biliwind.blog.service.edge.EdgePersistentChannelClient;
import com.biliwind.blog.service.edge.EdgeReadOnlyState;
import com.biliwind.blog.service.edge.NodeRoleService;
import com.biliwind.blog.service.edge.RoutedHttpExchange;
import com.biliwind.blog.service.edge.WespSyncService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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

    @ConfigProperty(name = "windblog.edge.max-routed-body-bytes", defaultValue = "10485760")
    long maxRoutedBodyBytes;

    @Inject
    NodeRoleService nodeRoleService;

    @Inject
    EdgeReadOnlyState readOnlyState;

    @Inject
    EdgePersistentChannelClient channelClient;

    @Inject
    WespSyncService wespSyncService;

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

        // WESP is outbound-only and can establish/re-establish its session on
        // the first write. Legacy gRPC still requires an already-online stream.
        if (readOnlyState.isReadOnly() && !wespSyncService.isEnabled()) {
            requestContext.abortWith(buildReadOnlyResponse());
            return;
        }

        byte[] requestBody;
        try {
            requestBody = readRequestBody(requestContext);
        } catch (RequestBodyTooLargeException exception) {
            requestContext.abortWith(Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(Map.of("success", false, "message", "边缘回源请求体超过限制"))
                    .build());
            return;
        }
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
        if (path.equals("/api/admin/auth/step-up")) {
            return true;
        }
        // The primary writes the WESP runtime bootstrap only after logging in
        // to this target node. Keep that authenticated local operation on the
        // edge; forwarding it would send the request back to the primary.
        if (path.equals("/api/admin/edge-nodes/connection/bootstrap")) {
            return true;
        }
        if (path.equals("/api/admin/install")) {
            return true;
        }
        if (path.startsWith("/q/")) {
            return true;
        }
        // WESP receiver endpoints are the authenticated transport boundary.
        // They must be handled locally so an edge node never forwards a peer's
        // sync request back through its own write router.
        if (path.startsWith("/sync/v1/")) {
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
            byte[] body = readLimitedBody(inputStream, maxRoutedBodyBytes);
            requestContext.setEntityStream(new ByteArrayInputStream(body));
            return body;
        } catch (RequestBodyTooLargeException exception) {
            throw exception;
        } catch (Exception exception) {
            return new byte[0];
        }
    }

    static byte[] readLimitedBody(InputStream inputStream, long maxBytes) throws java.io.IOException {
        if (inputStream == null || maxBytes <= 0) {
            throw new IllegalArgumentException("边缘回源请求体限制无效");
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long totalBytes = 0L;
        int readLength = inputStream.read(buffer);
        while (readLength >= 0) {
            if (readLength > 0) {
                totalBytes = totalBytes + readLength;
                if (totalBytes > maxBytes) {
                    throw new RequestBodyTooLargeException();
                }
                outputStream.write(buffer, 0, readLength);
            }
            readLength = inputStream.read(buffer);
        }
        return outputStream.toByteArray();
    }

    private static class RequestBodyTooLargeException extends RuntimeException {
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
