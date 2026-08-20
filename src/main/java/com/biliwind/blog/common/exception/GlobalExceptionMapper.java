package com.biliwind.blog.common.exception;

import com.biliwind.blog.common.dto.ErrorResponse;
import com.biliwind.blog.common.helper.RsaHelper;
import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
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
            status = Response.Status.fromStatusCode(webExResponse.getStatus());
            message = SensitiveMessageSanitizer.sanitize(exception.getMessage());
            if (message == null) {
                message = "请求处理失败";
            } else if (message.isEmpty()) {
                message = "请求处理失败";
            }
            errorType = "WebException";
        } else {
            LOGGER.error("未捕获的全局异常: ", exception);
        }

        // 处理追踪信息 (仅针对 5xx 错误)
        String trackingId = null;
        String trackingText = null;
        Response.Status.Family family = status.getFamily();
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

            return Response.status(status)
                    .type(MediaType.TEXT_HTML)
                    .entity(instance)
                    .build();
        }

        // 默认返回 JSON
        ErrorResponse errorResponse = new ErrorResponse(message, errorType, trackingId, trackingText);
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(errorResponse)
                .build();
    }

    private boolean isHtmlExpected() {
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
}
