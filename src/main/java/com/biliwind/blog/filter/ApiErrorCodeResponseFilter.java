package com.biliwind.blog.filter;

import com.biliwind.blog.common.dto.ErrorResponse;
import com.biliwind.blog.common.error.ErrorCodeCatalog;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import java.util.LinkedHashMap;
import java.util.Map;

/** Adds a stable error code to API failures returned directly by resource methods. */
@Provider
@Priority(Priorities.HEADER_DECORATOR + 50)
public class ApiErrorCodeResponseFilter implements ContainerResponseFilter {
    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        String path = request.getUriInfo().getPath();
        if (!isApiPath(path)) {
            return;
        }

        Object entity = response.getEntity();
        if (entity instanceof ErrorResponse errorResponse) {
            if (errorResponse.errorCode() != null) {
                response.getHeaders().putSingle("X-WindBlog-Error-Code", errorResponse.errorCode());
            }
            return;
        }
        Map<?, ?> body = entity instanceof Map<?, ?> map ? map : null;
        boolean failedBody = body != null && Boolean.FALSE.equals(body.get("success"));
        if (response.getStatus() < 400 && !failedBody) {
            return;
        }

        String messageKey = body != null && body.containsKey("message") ? "message"
                : body != null && body.containsKey("error") ? "error" : null;
        String message = messageKey == null ? null : stringValue(body.get(messageKey));
        String errorCode = body == null ? null : stringValue(body.get("errorCode"));
        if (errorCode == null || errorCode.isBlank()) {
            errorCode = ErrorCodeCatalog.resolve(path, response.getStatus(), message);
        }
        response.getHeaders().putSingle("X-WindBlog-Error-Code", errorCode);

        if (body == null) {
            return;
        }

        Map<String, Object> decorated = new LinkedHashMap<>();
        body.forEach((key, value) -> decorated.put(String.valueOf(key), value));
        decorated.put("errorCode", errorCode);
        if (message != null && messageKey != null) {
            decorated.put(messageKey, ErrorCodeCatalog.appendToMessage(message, errorCode));
        }
        response.setEntity(decorated);
    }

    private boolean isApiPath(String path) {
        if (path == null) {
            return false;
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        return normalized.startsWith("/api/") || normalized.startsWith("/user/api/");
    }

    private String stringValue(Object value) {
        return value instanceof String string ? string : null;
    }
}
