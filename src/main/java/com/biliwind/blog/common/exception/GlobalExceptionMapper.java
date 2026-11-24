package com.biliwind.blog.common.exception;

import com.biliwind.blog.common.dto.ErrorResponse;
import com.biliwind.blog.common.error.ErrorCodeCatalog;
import com.biliwind.blog.common.helper.RsaHelper;
import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotAcceptableException;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 全局异常映射器，将所有未捕获的异常转化为统一的响应格式（JSON 或 HTML）。
 */
@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionMapper.class);

    @Context
    HttpHeaders headers;

    @Context
    HttpServerRequest request;

    @Inject
    @io.quarkus.qute.Location("system/error.html")
    Template error;

    @Inject
    RsaHelper rsaHelper;

    @Override
    public Response toResponse(Throwable exception) {
        Response.Status status = Response.Status.INTERNAL_SERVER_ERROR;
        String message = "服务器内部错误，请联系管理员";
        String errorType = "InternalServerError";

        // 识别异常类型并设置状态码和消息
        if (exception instanceof ConflictException) {
            status = Response.Status.CONFLICT;
            message = SensitiveMessageSanitizer.sanitize(exception.getMessage());
            errorType = "ConflictError";
            LOGGER.debug("业务冲突: path={}, message={}", request == null ? null : request.path(), message);
        } else if (exception instanceof ConcurrentModificationException) {
            status = Response.Status.CONFLICT;
            message = SensitiveMessageSanitizer.sanitize(exception.getMessage());
            errorType = "ConcurrentUpdateError";
        } else if (exception instanceof BadRequestException) {
            status = Response.Status.BAD_REQUEST;
            message = SensitiveMessageSanitizer.sanitize(exception.getMessage());
            errorType = "BadRequestError";
        } else if (exception instanceof jakarta.validation.ConstraintViolationException) {
            status = Response.Status.BAD_REQUEST;
            message = SensitiveMessageSanitizer.sanitize(exception.getMessage());
            errorType = "ValidationError";
        } else if (exception instanceof WebApplicationException) {
            WebApplicationException webEx = (WebApplicationException) exception;
            Response webExResponse = webEx.getResponse();
            Response.Status resolvedStatus = webExResponse == null
                    ? Response.Status.INTERNAL_SERVER_ERROR
                    : Response.Status.fromStatusCode(webExResponse.getStatus());
            status = resolvedStatus != null ? resolvedStatus : Response.Status.BAD_REQUEST;
            message = resolveSafeWebExceptionMessage(webEx, status);
            errorType = "WebException";
            LOGGER.debug("WebApplicationException: path={}, status={}, rawMessage={}",
                    request == null ? null : request.path(), status.getStatusCode(), exception.getMessage());
        } else {
            LOGGER.error("未捕获的全局异常: ", exception);
        }

        // 处理追踪信息 (仅针对 5xx 错误)
        String trackingId = null;
        String trackingText = null;
        Response.Status.Family family = status.getFamily();
        String path = request == null ? null : request.path();
        String errorCode = ErrorCodeCatalog.resolve(path, status.getStatusCode(), message);
        message = ErrorCodeCatalog.appendToMessage(message, errorCode);
        if (family == Response.Status.Family.SERVER_ERROR) {
            UUID uuid = UUID.randomUUID();
            trackingId = uuid.toString();
            try {
                trackingText = generateTrackingText(exception);
            } catch (RuntimeException trackingException) {
                // 错误追踪信息是辅助能力，不能因 RSA/请求上下文异常再次遮蔽原始 500 响应。
                LOGGER.error("生成错误追踪信息失败，保留 JSON 错误响应", trackingException);
                trackingText = null;
            }
        }

        // 根据客户端期望的类型返回响应
        if (isHtmlExpected()) {
            TemplateInstance instance = error.data("message", message);
            instance.data("status", status.getStatusCode());
            instance.data("errorType", errorType);
            instance.data("trackingId", trackingId);
            instance.data("trackingText", trackingText);
            instance.data("errorCode", errorCode);

            return Response.status(status)
                    .type(MediaType.TEXT_HTML)
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .header("Pragma", "no-cache")
                    .header("Expires", "0")
                    .entity(instance)
                    .build();
        }

        // 默认返回 JSON
        ErrorResponse errorResponse = new ErrorResponse(message, errorType, trackingId, trackingText, errorCode);
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .entity(errorResponse)
                .build();
    }

    private boolean isHtmlExpected() {
        // API clients need a machine-readable error even when the browser sends
        // Accept: */*. Existing pages may keep older JavaScript cached, so do
        // not let content negotiation turn an API error into the HTML error page.
        if (request != null && request.path() != null
                && (request.path().startsWith("/api/") || request.path().startsWith("/user/api/"))) {
            return false;
        }
        if (headers == null) {
            return false;
        }
        List<MediaType> acceptableMediaTypes = headers.getAcceptableMediaTypes();
        for (int i = 0; i < acceptableMediaTypes.size(); i++) {
            MediaType mediaType = acceptableMediaTypes.get(i);
            if (mediaType.isCompatible(MediaType.TEXT_HTML_TYPE)) {
                return true;
            }
        }
        return false;
    }

    private String generateTrackingText(Throwable exception) {
        StringBuilder sb = new StringBuilder();
        OffsetDateTime now = OffsetDateTime.now();
        sb.append("Timestamp: ").append(now.toString()).append("\n");
        if (request != null) {
            sb.append("Path: ").append(request.path()).append("\n");
            sb.append("Method: ").append(request.method().toString()).append("\n");
            sb.append("IP: ").append(request.remoteAddress() == null
                    ? "unknown" : request.remoteAddress().host()).append("\n");

            String userAgent = request.getHeader("User-Agent");
            if (userAgent != null) {
                sb.append("User-Agent: ").append(userAgent).append("\n");
            }
        }

        // 包含完整的堆栈轨迹
        if (exception != null) {
            sb.append("\n--- Stack Trace ---\n");
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            exception.printStackTrace(pw);
            sb.append(sw.toString());
        }

        String trackingData = sb.toString();
        // 使用 RsaHelper 的混合加密 (RSA + AES)
        return rsaHelper.encrypt(trackingData);
    }

    static String resolveSafeWebExceptionMessage(WebApplicationException exception, Response.Status status) {
        if (exception instanceof NotSupportedException) {
            return "不支持的请求内容类型";
        }
        if (exception instanceof NotAllowedException) {
            return "不支持的请求方法";
        }
        if (exception instanceof NotAcceptableException) {
            return "不支持的响应内容类型";
        }
        String raw = exception.getMessage();
        if (raw == null || raw.isBlank() || isFrameworkInternalMessage(raw)) {
            return genericMessageForStatus(status, exception instanceof NotFoundException);
        }
        return SensitiveMessageSanitizer.sanitize(raw);
    }

    static boolean isFrameworkInternalMessage(String message) {
        if (message == null || message.isBlank()) {
            return true;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("@consumes")
                || lower.contains("@produces")
                || lower.contains("unable to find matching target resource")
                || lower.contains("resteasy")
                || lower.contains("jakarta.ws.rs.")
                || lower.contains("org.jboss.")
                || lower.contains("io.quarkus.")
                || lower.contains("com.fasterxml.jackson.")
                || lower.startsWith("http 4")
                || lower.startsWith("http 5");
    }

    private static String genericMessageForStatus(Response.Status status, boolean notFound) {
        if (notFound || status == Response.Status.NOT_FOUND) {
            return "请求的资源不存在";
        }
        if (status == null) {
            return "请求处理失败";
        }
        return switch (status) {
            case BAD_REQUEST -> "请求参数无效";
            case UNAUTHORIZED -> "未授权访问";
            case FORBIDDEN -> "无权访问该资源";
            case METHOD_NOT_ALLOWED -> "不支持的请求方法";
            case NOT_ACCEPTABLE -> "不支持的响应内容类型";
            case CONFLICT -> "请求存在冲突";
            case UNSUPPORTED_MEDIA_TYPE -> "不支持的请求内容类型";
            case TOO_MANY_REQUESTS -> "请求过于频繁，请稍后再试";
            default -> status.getFamily() == Response.Status.Family.SERVER_ERROR
                    ? "服务器内部错误，请联系管理员"
                    : "请求处理失败";
        };
    }
}
