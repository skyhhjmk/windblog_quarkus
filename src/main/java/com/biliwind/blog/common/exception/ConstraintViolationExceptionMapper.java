package com.biliwind.blog.common.exception;

import com.biliwind.blog.common.dto.ErrorResponse;
import com.biliwind.blog.common.error.ErrorCodeCatalog;
import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.Priority;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Overrides Quarkus's default Hibernate Validator mapper so validation failures
 * return WindBlog's uniform {@link ErrorResponse} with only public leaf field names
 * (never internal method/bean paths such as {@code login.account}).
 */
@Provider
@Priority(Priorities.USER)
public class ConstraintViolationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConstraintViolationExceptionMapper.class);

    @Context
    HttpServerRequest request;

    @Override
    public Response toResponse(ConstraintViolationException exception) {
        String path = request == null ? null : request.path();
        String summary = formatViolations(exception == null ? null : exception.getConstraintViolations());
        if (exception != null) {
            LOGGER.debug("参数校验失败: path={}, raw={}", path, exception.getMessage());
        }

        String errorCode = ErrorCodeCatalog.resolve(path, Response.Status.BAD_REQUEST.getStatusCode(), summary);
        String message = ErrorCodeCatalog.appendToMessage(summary, errorCode);
        ErrorResponse body = new ErrorResponse(message, "ValidationError", null, null, errorCode);

        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .entity(body)
                .build();
    }

    static String formatViolations(Set<ConstraintViolation<?>> violations) {
        if (violations == null || violations.isEmpty()) {
            return "请求参数无效";
        }
        List<String> details = new ArrayList<>();
        for (ConstraintViolation<?> violation : violations) {
            if (violation == null) {
                continue;
            }
            String field = extractLeafFieldName(violation.getPropertyPath());
            String reason = violation.getMessage();
            String safeReason = (reason == null || reason.isBlank())
                    ? "格式不正确"
                    : SensitiveMessageSanitizer.sanitize(reason);
            if (field != null && !field.isBlank()) {
                details.add(field + " " + safeReason);
            } else {
                details.add(safeReason);
            }
        }
        if (details.isEmpty()) {
            return "请求参数无效";
        }
        return "参数校验失败：" + String.join("；", details);
    }

    static String extractLeafFieldName(Path propertyPath) {
        if (propertyPath == null) {
            return null;
        }
        String leaf = null;
        for (Path.Node node : propertyPath) {
            if (node != null && node.getName() != null && !node.getName().isBlank()) {
                leaf = node.getName();
            }
        }
        return leaf;
    }
}
